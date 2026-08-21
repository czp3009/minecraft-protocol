@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtSerializationException
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionFormatException
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.io.decodeFromSource
import kotlinx.serialization.json.io.encodeToSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import kotlin.random.Random
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

class LevelDataStore(
    val paths: MinecraftWorldPaths,
    val nbtFiles: NbtFileStore = NbtFileStore(),
) {
    init {
        require(paths.root == paths.levelData.parent)
    }

    fun readDocument(): NbtDocument = read(nbtFiles.nbt::decodeDocumentFromSource)

    fun <T> read(deserializer: DeserializationStrategy<T>): T =
        read { source -> nbtFiles.nbt.decodeFromSource(deserializer, source) }

    fun <T> read(block: (KotlinxSource) -> T): T {
        val primaryFailure = try {
            return nbtFiles.read(paths.levelData, block = block)
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            failure
        }
        val fallback = try {
            nbtFiles.read(paths.previousLevelData, block = block)
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

    internal fun readDocumentForSharedAccess(): CoordinatedRead<NbtDocument> =
        readForSharedAccess(nbtFiles.nbt::decodeDocumentFromSource)

    internal fun <T> readForSharedAccess(
        deserializer: DeserializationStrategy<T>,
    ): CoordinatedRead<T> = readForSharedAccess { source -> nbtFiles.nbt.decodeFromSource(deserializer, source) }

    internal fun <T> readForSharedAccess(block: (KotlinxSource) -> T): CoordinatedRead<T> = try {
        CoordinatedRead.Complete(nbtFiles.read(paths.levelData, block = block))
    } catch (failure: Throwable) {
        if (!failure.isRecoverableNbtReadFailure()) throw failure
        CoordinatedRead.RequiresExclusive
    }

    fun writeDocument(document: NbtDocument) {
        write { sink -> nbtFiles.nbt.encodeDocumentToSink(document, sink) }
    }

    fun <T> write(
        serializer: SerializationStrategy<T>,
        value: T,
    ) = write { sink -> nbtFiles.nbt.encodeToSink(serializer, value, sink) }

    fun write(block: (KotlinxSink) -> Unit) {
        val temporary = nbtFiles.writeSyncedTemporary(paths.root, block = block)
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
    fun readDocument(playerUuid: String): NbtDocument? =
        read(playerUuid, nbtFiles.nbt::decodeDocumentFromSource)

    fun <T> read(
        playerUuid: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = read(playerUuid) { source ->
        nbtFiles.nbt.decodeFromSource(deserializer, source)
    }

    fun <T> read(playerUuid: String, block: (KotlinxSource) -> T): T? {
        val primary = paths.playerData(playerUuid)
        val previous = paths.previousPlayerData(playerUuid)
        val primaryExists = nbtFiles.fileSystem.metadataOrNull(primary)?.isRegularFile == true
        if (primaryExists) {
            val primaryFailure = try {
                return nbtFiles.read(primary, block = block)
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
                nbtFiles.read(previous, block = block)
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
        return nbtFiles.read(previous, block = block)
    }

    internal fun readDocumentForSharedAccess(playerUuid: String): CoordinatedRead<NbtDocument?> =
        readForSharedAccess(playerUuid, nbtFiles.nbt::decodeDocumentFromSource)

    internal fun <T> readForSharedAccess(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): CoordinatedRead<T?> {
        val primary = paths.playerData(playerUuid)
        val previous = paths.previousPlayerData(playerUuid)
        if (nbtFiles.fileSystem.metadataOrNull(primary)?.isRegularFile == true) {
            return try {
                CoordinatedRead.Complete(nbtFiles.read(primary, block = block))
            } catch (failure: Throwable) {
                if (!failure.isRecoverableNbtReadFailure()) throw failure
                CoordinatedRead.RequiresExclusive
            }
        }
        if (nbtFiles.fileSystem.metadataOrNull(previous)?.isRegularFile != true) {
            return CoordinatedRead.Complete(null)
        }
        return CoordinatedRead.Complete(nbtFiles.read(previous, block = block))
    }

    fun writeDocument(playerUuid: String, document: NbtDocument) {
        write(playerUuid) { sink -> nbtFiles.nbt.encodeDocumentToSink(document, sink) }
    }

    fun <T> write(
        playerUuid: String,
        serializer: SerializationStrategy<T>,
        value: T,
    ) = write(playerUuid) { sink ->
        nbtFiles.nbt.encodeToSink(serializer, value, sink)
    }

    fun write(playerUuid: String, block: (KotlinxSink) -> Unit) {
        val target = paths.playerData(playerUuid)
        val parent = checkNotNull(target.parent)
        val temporary = nbtFiles.writeSyncedTemporary(parent, block = block)
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
            val source = fileSystem.source(primary)
            useResource(source, { it.close() }) {
                val destination = fileSystem.openUniqueTemporarySink(
                    directory = parent,
                    prefix = "${primary.name}_corrupted_",
                )
                temporary = destination
                val sink = destination.sink.buffer()
                useResource(sink, { it.close() }) {
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
    fun readDocument(identifier: String): NbtDocument? =
        read(identifier, nbtFiles.nbt::decodeDocumentFromSource)

    fun <T> read(
        identifier: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = read(identifier) { source ->
        nbtFiles.nbt.decodeFromSource(deserializer, source)
    }

    fun <T> read(identifier: String, block: (KotlinxSource) -> T): T? {
        val path = paths.savedData(identifier, dimension)
        if (nbtFiles.fileSystem.metadataOrNull(path)?.isRegularFile != true) {
            return null
        }
        val compression = detectSavedDataCompression(path)
        return nbtFiles.read(path, compression, block)
    }

    fun writeDocument(identifier: String, document: NbtDocument) {
        write(identifier) { sink -> nbtFiles.nbt.encodeDocumentToSink(document, sink) }
    }

    fun <T> write(
        identifier: String,
        serializer: SerializationStrategy<T>,
        value: T,
    ) = write(identifier) { sink ->
        nbtFiles.nbt.encodeToSink(serializer, value, sink)
    }

    fun write(identifier: String, block: (KotlinxSink) -> Unit) {
        nbtFiles.write(paths.savedData(identifier, dimension), block = block)
    }

    private fun detectSavedDataCompression(path: Path): Compression {
        val source = nbtFiles.openSource(path).buffer()
        return useResource(source, { it.close() }) {
            if (
                source.request(2L) &&
                source.buffer[0L] == GZIP_MAGIC_FIRST &&
                source.buffer[1L] == GZIP_MAGIC_SECOND
            ) {
                Compression.GZIP
            } else {
                Compression.NONE
            }
        }
    }
}

class Utf8JsonFileStore internal constructor(internal val files: WorldFileAccess) {
    constructor(fileSystem: FileSystem = systemFileSystem) : this(
        files = WorldFileAccess.mutable(fileSystem),
    )

    val fileSystem: FileSystem
        get() = files.fileSystem

    fun readText(path: Path): String = read(path) { source -> source.readString() }

    /** Lends the UTF-8 file source for the duration of [block]. */
    fun <T> read(path: Path, block: (KotlinxSource) -> T): T =
        files.readFile(path) { source, _ ->
            withOkioIoExceptions("Cannot read UTF-8 file $path") {
                block(source.asKotlinxIoRawSource().buffered())
            }
        }

    fun readJson(path: Path, json: Json = Json): JsonElement =
        readJson(path, JsonElement.serializer(), json)

    fun <T> readJson(
        path: Path,
        deserializer: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = read(path) { source -> json.decodeFromSource(deserializer, source) }

    fun writeText(path: Path, text: String) = write(path) { sink -> sink.writeString(text) }

    /** Directly truncates and streams the final file. */
    fun write(path: Path, block: (KotlinxSink) -> Unit) {
        files.requireWritable()
        val parent = path.parent
            ?: throw WorldIOException("File has no parent directory: $path")
        fileSystem.createDirectories(parent)
        fileSystem.write(path) {
            withOkioIoExceptions("Cannot write UTF-8 file $path") {
                val converted = asKotlinxIoRawSink().buffered()
                block(converted)
                converted.flush()
            }
        }
    }

    fun writeJson(path: Path, element: JsonElement, json: Json = Json) =
        writeJson(path, JsonElement.serializer(), element, json)

    fun <T> writeJson(
        path: Path,
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = write(path) { sink -> json.encodeToSink(serializer, value, sink) }
}

private fun Throwable.isRecoverableNbtReadFailure(): Boolean {
    return this is IOException ||
            this is NbtSerializationException ||
            this is CompressionFormatException
}

internal sealed interface CoordinatedRead<out T> {
    data class Complete<T>(
        val value: T,
    ) : CoordinatedRead<T>

    data object RequiresExclusive : CoordinatedRead<Nothing>
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
