package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtSerializationException
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlinx.io.IOException as KotlinxIOException

class LevelDataStore(
    val paths: MinecraftWorldPaths,
    val nbtFiles: NbtFileStore = NbtFileStore(),
) {
    init {
        require(paths.root == paths.levelData.parent)
    }

    suspend fun read(): NbtDocument {
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
        promotePrevious(primaryFailure)
        return fallback
    }

    suspend fun write(document: NbtDocument) {
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
    suspend fun read(playerUuid: String): NbtDocument? {
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
            try {
                copyCorrupted(primary)
            } catch (copyFailure: Throwable) {
                if (copyFailure !is IOException) throw copyFailure
                primaryFailure.addSuppressed(copyFailure)
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

    suspend fun write(playerUuid: String, document: NbtDocument) {
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
        var failure: Throwable? = null
        var temporary: TemporaryFileSink? = null
        val source = fileSystem.source(primary)
        try {
            val destination = fileSystem.openUniqueTemporarySink(
                directory = parent,
                prefix = "${primary.name}_corrupted_",
            )
            temporary = destination
            val buffer = Buffer()
            while (true) {
                val read = source.read(buffer, 8_192L)
                if (read < 0) break
                if (read == 0L) {
                    throw WorldIOException(
                        "Source made no progress while copying $primary",
                    )
                }
                destination.sink.write(buffer, read)
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            try {
                source.close()
            } catch (closeFailure: Throwable) {
                val current = failure
                if (current == null) {
                    failure = closeFailure
                } else {
                    current.addSuppressed(closeFailure)
                }
            }
            temporary?.let { opened ->
                try {
                    opened.sink.close()
                } catch (closeFailure: Throwable) {
                    val current = failure
                    if (current == null) {
                        failure = closeFailure
                    } else {
                        current.addSuppressed(closeFailure)
                    }
                }
            }
        }
        failure?.let {
            temporary?.let { opened ->
                fileSystem.deleteIfExistsPreserving(opened.path, it)
            }
            throw it
        }
    }
}

class SavedDataFileStore(
    val paths: MinecraftWorldPaths,
    val dimension: DimensionDirectory = DimensionDirectory.Overworld,
    val nbtFiles: NbtFileStore = NbtFileStore(),
) {
    suspend fun read(identifier: String): NbtDocument? {
        val path = paths.savedData(identifier, dimension)
        if (nbtFiles.fileSystem.metadataOrNull(path)?.isRegularFile != true) {
            return null
        }
        val compression = detectSavedDataCompression(path)
        return nbtFiles.read(path, compression)
    }

    suspend fun write(identifier: String, document: NbtDocument) {
        nbtFiles.writeDirect(
            path = paths.savedData(identifier, dimension),
            document = document,
            compression = NbtFileCompression.GZIP,
        )
    }

    private fun detectSavedDataCompression(path: Path): NbtFileCompression {
        val handle = nbtFiles.fileSystem.openReadOnly(path)
        var failure: Throwable? = null
        try {
            val magic = ByteArray(2)
            var read = 0
            while (read < magic.size) {
                val count = handle.read(
                    read.toLong(),
                    magic,
                    read,
                    magic.size - read,
                )
                if (count < 0) break
                if (count == 0) {
                    throw WorldIOException(
                        "File handle made no progress while reading $path",
                    )
                }
                read += count
            }
            return if (
                read == magic.size &&
                magic[0] == GZIP_MAGIC_FIRST &&
                magic[1] == GZIP_MAGIC_SECOND
            ) {
                NbtFileCompression.GZIP
            } else {
                NbtFileCompression.NONE
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(failure, handle::close)
        }
    }
}

class Utf8JsonFileStore(
    val fileSystem: FileSystem = systemFileSystem,
    val maximumBytes: Int = 16 * 1_048_576,
) {
    init {
        require(maximumBytes >= 0)
    }

    fun read(path: Path): String =
        fileSystem.readFileWithinLimit(path, maximumBytes).decodeToString()

    fun write(path: Path, json: String) {
        val bytes = json.encodeToByteArray()
        if (bytes.size > maximumBytes) {
            throw WorldIOException(
                "UTF-8 JSON size ${bytes.size} exceeds configured limit $maximumBytes",
            )
        }
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        val sink = fileSystem.sink(path)
        val buffer = Buffer().apply { write(bytes) }
        var failure: Throwable? = null
        try {
            sink.write(buffer, bytes.size.toLong())
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(failure, sink::close)
        }
    }
}

private fun Throwable.isRecoverableNbtReadFailure(): Boolean {
    if (this is CancellationException) return false
    return this is IOException ||
            this is KotlinxIOException ||
            this is NbtSerializationException
}

private fun throwPromotionFailure(
    primaryFailure: Throwable,
    promotionFailure: Throwable,
): Nothing {
    if (promotionFailure is CancellationException) throw promotionFailure
    if (promotionFailure !is IOException) throw promotionFailure
    primaryFailure.addSuppressed(promotionFailure)
    throw primaryFailure
}

private val GZIP_MAGIC_FIRST: Byte = 0x1F
private val GZIP_MAGIC_SECOND: Byte = 0x8B.toByte()
