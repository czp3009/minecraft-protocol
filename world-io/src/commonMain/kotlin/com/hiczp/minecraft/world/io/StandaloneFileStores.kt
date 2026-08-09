package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtSerializationException
import com.hiczp.minecraft.world.format.RegionFormatException
import okio.*
import kotlin.random.Random

class LevelDataStore(
    val paths: MinecraftWorldPaths,
    val nbtFiles: NbtFileStore = NbtFileStore(),
) {
    init {
        require(paths.root == paths.levelData.parent)
    }

    fun read(): NbtDocument {
        val primaryFailure = try {
            return nbtFiles.read(paths.levelData)
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            failure
        }
        val fallback = try {
            nbtFiles.read(paths.previousLevelData)
        } catch (fallbackFailure: Throwable) {
            if (!fallbackFailure.isRecoverableNbtReadFailure()) {
                throw fallbackFailure
            }
            primaryFailure.addSuppressed(fallbackFailure)
            throw primaryFailure
        }
        if (!nbtFiles.liveReadOnly) promotePrevious(primaryFailure)
        return fallback
    }

    fun write(document: NbtDocument) {
        val temporary = nbtFiles.writeSyncedTemporary(paths.root, document)
        try {
            nbtFiles.fileSystem.replaceWithBackup(
                temporary = temporary,
                target = paths.levelData,
                backup = paths.previousLevelData,
            )
        } catch (failure: Throwable) {
            nbtFiles.fileSystem.deleteIfExistsPreserving(temporary, failure)
            throw failure
        }
    }

    private fun promotePrevious(primaryFailure: Throwable) {
        val fileSystem = nbtFiles.fileSystem
        val corrupted = paths.root / temporaryFileName(
            random = Random.nextLong().toULong(),
            prefix = "${paths.levelData.name}_corrupted_",
        )
        try {
            fileSystem.replaceWithoutRollback(
                replacement = paths.previousLevelData,
                target = paths.levelData,
                displaced = corrupted,
            )
        } catch (failure: Throwable) {
            throwPromotionFailure(primaryFailure, failure)
        }
    }
}

class PlayerDataStore(
    val paths: MinecraftWorldPaths,
    val nbtFiles: NbtFileStore = NbtFileStore(),
) {
    fun read(playerUuid: String): NbtDocument? {
        val primary = paths.playerData(playerUuid)
        val previous = paths.previousPlayerData(playerUuid)
        val primaryExists =
            nbtFiles.fileSystem.metadataOrNull(primary)?.isRegularFile == true
        if (primaryExists) {
            val primaryFailure = try {
                return nbtFiles.read(primary)
            } catch (failure: Throwable) {
                if (!failure.isRecoverableNbtReadFailure()) throw failure
                failure
            }
            if (!nbtFiles.liveReadOnly) {
                try {
                    copyCorrupted(primary)
                } catch (copyFailure: Throwable) {
                    if (copyFailure !is IOException) throw copyFailure
                    primaryFailure.addSuppressed(copyFailure)
                }
            }
            return try {
                if (
                    nbtFiles.fileSystem.metadataOrNull(previous)
                        ?.isRegularFile != true
                ) {
                    throw primaryFailure
                }
                nbtFiles.read(previous)
            } catch (fallbackFailure: Throwable) {
                if (!fallbackFailure.isRecoverableNbtReadFailure()) {
                    throw fallbackFailure
                }
                if (fallbackFailure !== primaryFailure) {
                    primaryFailure.addSuppressed(fallbackFailure)
                }
                throw primaryFailure
            }
        }
        if (
            nbtFiles.fileSystem.metadataOrNull(previous)
                ?.isRegularFile != true
        ) {
            return null
        }
        return nbtFiles.read(previous)
    }

    fun write(playerUuid: String, document: NbtDocument) {
        val target = paths.playerData(playerUuid)
        val parent = checkNotNull(target.parent)
        val temporary = nbtFiles.writeSyncedTemporary(parent, document)
        try {
            nbtFiles.fileSystem.replaceWithBackup(
                temporary = temporary,
                target = target,
                backup = paths.previousPlayerData(playerUuid),
            )
        } catch (failure: Throwable) {
            nbtFiles.fileSystem.deleteIfExistsPreserving(temporary, failure)
            throw failure
        }
    }

    private fun copyCorrupted(primary: Path) {
        val fileSystem = nbtFiles.fileSystem
        val parent = checkNotNull(primary.parent)
        var temporary: TemporaryFileSink? = null
        try {
            fileSystem.source(primary).use { source ->
                val destination = fileSystem.openUniqueTemporarySink(
                    directory = parent,
                    prefix = "${primary.name}_corrupted_",
                )
                temporary = destination
                destination.sink.buffer().use { sink ->
                    sink.writeAll(source)
                }
            }
        } catch (failure: Throwable) {
            temporary?.let { opened ->
                fileSystem.deleteIfExistsPreserving(opened.path, failure)
            }
            throw failure
        }
    }
}

class SavedDataFileStore(
    val paths: MinecraftWorldPaths,
    val dimension: DimensionDirectory = DimensionDirectory.Overworld,
    val nbtFiles: NbtFileStore = NbtFileStore(),
) {
    fun read(identifier: String): NbtDocument? {
        val path = paths.savedData(identifier, dimension)
        if (nbtFiles.fileSystem.metadataOrNull(path)?.isRegularFile != true) {
            return null
        }
        val compression = detectSavedDataCompression(path)
        return nbtFiles.read(path, compression)
    }

    fun write(identifier: String, document: NbtDocument) {
        nbtFiles.writeDirect(
            path = paths.savedData(identifier, dimension),
            document = document,
            compression = NbtFileCompression.GZIP,
        )
    }

    private fun detectSavedDataCompression(path: Path): NbtFileCompression {
        return nbtFiles.openSource(path).buffer().use { source ->
            if (
                source.request(2L) &&
                source.buffer[0L] == GZIP_MAGIC_FIRST &&
                source.buffer[1L] == GZIP_MAGIC_SECOND
            ) {
                NbtFileCompression.GZIP
            } else {
                NbtFileCompression.NONE
            }
        }
    }
}

class Utf8JsonFileStore internal constructor(
    internal val files: WorldFileAccess,
    val maximumBytes: Int = 16 * 1_048_576,
) {
    constructor(
        fileSystem: FileSystem = systemFileSystem,
        maximumBytes: Int = 16 * 1_048_576,
    ) : this(
        files = WorldFileAccess.mutable(fileSystem),
        maximumBytes = maximumBytes,
    )

    val fileSystem: FileSystem
        get() = files.fileSystem

    init {
        require(maximumBytes >= 0)
    }

    fun read(path: Path): String =
        files.readFileWithinLimit(path, maximumBytes).decodeToString()

    fun write(path: Path, json: String) {
        files.requireWritable()
        val bytes = json.encodeToByteArray()
        if (bytes.size > maximumBytes) {
            throw WorldIOException(
                "UTF-8 JSON size ${bytes.size} exceeds configured limit $maximumBytes",
            )
        }
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        fileSystem.write(path) {
            write(bytes)
        }
    }
}

private fun Throwable.isRecoverableNbtReadFailure(): Boolean {
    return this is IOException ||
            this is NbtSerializationException ||
            this is RegionFormatException
}

private fun throwPromotionFailure(
    primaryFailure: Throwable,
    promotionFailure: Throwable,
): Nothing {
    if (promotionFailure !is IOException) throw promotionFailure
    primaryFailure.addSuppressed(promotionFailure)
    throw primaryFailure
}

private val GZIP_MAGIC_FIRST: Byte = 0x1F
private val GZIP_MAGIC_SECOND: Byte = 0x8B.toByte()
