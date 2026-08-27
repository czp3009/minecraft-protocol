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
    val minecraftWorldPaths: MinecraftWorldPaths,
    val nbtFileStore: NbtFileStore = NbtFileStore(),
) {
    init {
        require(minecraftWorldPaths.root == minecraftWorldPaths.levelData.parent)
    }

    fun readDocument(): NbtDocument = read(nbtFileStore.nbtFormat::decodeDocumentFromSource)

    fun <T> read(deserializationStrategy: DeserializationStrategy<T>): T =
        read { source -> nbtFileStore.nbtFormat.decodeFromSource(deserializationStrategy, source) }

    fun <T> read(block: (KotlinxSource) -> T): T {
        val primaryFailure = try {
            return nbtFileStore.read(minecraftWorldPaths.levelData, block = block)
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            failure
        }
        val fallback = try {
            nbtFileStore.read(minecraftWorldPaths.previousLevelData, block = block)
        } catch (fallbackFailure: Throwable) {
            if (!fallbackFailure.isRecoverableNbtReadFailure()) {
                throw fallbackFailure
            }
            primaryFailure.addSuppressed(fallbackFailure)
            throw primaryFailure
        }
        if (!nbtFileStore.liveReadOnly) promotePrevious(primaryFailure)
        return fallback
    }

    internal fun readDocumentForSharedAccess(): CoordinatedRead<NbtDocument> =
        readForSharedAccess(nbtFileStore.nbtFormat::decodeDocumentFromSource)

    internal fun <T> readForSharedAccess(
        deserializationStrategy: DeserializationStrategy<T>,
    ): CoordinatedRead<T> = readForSharedAccess { source -> nbtFileStore.nbtFormat.decodeFromSource(deserializationStrategy, source) }

    internal fun <T> readForSharedAccess(block: (KotlinxSource) -> T): CoordinatedRead<T> = try {
        CoordinatedRead.Complete(nbtFileStore.read(minecraftWorldPaths.levelData, block = block))
    } catch (failure: Throwable) {
        if (!failure.isRecoverableNbtReadFailure()) throw failure
        CoordinatedRead.RequiresExclusive
    }

    fun writeDocument(nbtDocument: NbtDocument) {
        write { sink -> nbtFileStore.nbtFormat.encodeDocumentToSink(nbtDocument, sink) }
    }

    fun <T> write(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = write { sink -> nbtFileStore.nbtFormat.encodeToSink(serializationStrategy, value, sink) }

    fun write(block: (KotlinxSink) -> Unit) {
        val temporary = nbtFileStore.writeSyncedTemporary(minecraftWorldPaths.root, block = block)
        try {
            nbtFileStore.fileSystem.replaceWithBackup(
                temporary = temporary,
                target = minecraftWorldPaths.levelData,
                backup = minecraftWorldPaths.previousLevelData,
            )
        } catch (failure: Throwable) {
            nbtFileStore.fileSystem.deleteIfExistsPreserving(temporary, failure)
            throw failure
        }
    }

    private fun promotePrevious(primaryFailure: Throwable) {
        val fileSystem = nbtFileStore.fileSystem
        val corrupted = minecraftWorldPaths.root / temporaryFileName(
            random = Random.nextLong().toULong(),
            prefix = "${minecraftWorldPaths.levelData.name}_corrupted_",
        )
        try {
            fileSystem.replaceWithoutRollback(
                replacement = minecraftWorldPaths.previousLevelData,
                target = minecraftWorldPaths.levelData,
                displaced = corrupted,
            )
        } catch (failure: Throwable) {
            throwPromotionFailure(primaryFailure, failure)
        }
    }
}

class PlayerDataStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val nbtFileStore: NbtFileStore = NbtFileStore(),
) {
    fun readDocument(playerUuid: String): NbtDocument? =
        read(playerUuid, nbtFileStore.nbtFormat::decodeDocumentFromSource)

    fun <T> read(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = read(playerUuid) { source ->
        nbtFileStore.nbtFormat.decodeFromSource(deserializationStrategy, source)
    }

    fun <T> read(playerUuid: String, block: (KotlinxSource) -> T): T? {
        val primary = minecraftWorldPaths.playerData(playerUuid)
        val previous = minecraftWorldPaths.previousPlayerData(playerUuid)
        val primaryExists = nbtFileStore.fileSystem.metadataOrNull(primary)?.isRegularFile == true
        if (primaryExists) {
            val primaryFailure = try {
                return nbtFileStore.read(primary, block = block)
            } catch (failure: Throwable) {
                if (!failure.isRecoverableNbtReadFailure()) throw failure
                failure
            }
            if (!nbtFileStore.liveReadOnly) {
                try {
                    copyCorrupted(primary)
                } catch (copyFailure: Throwable) {
                    if (copyFailure !is IOException) throw copyFailure
                    primaryFailure.addSuppressed(copyFailure)
                }
            }
            return try {
                if (
                    nbtFileStore.fileSystem.metadataOrNull(previous)
                        ?.isRegularFile != true
                ) {
                    throw primaryFailure
                }
                nbtFileStore.read(previous, block = block)
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
            nbtFileStore.fileSystem.metadataOrNull(previous)
                ?.isRegularFile != true
        ) {
            return null
        }
        return nbtFileStore.read(previous, block = block)
    }

    internal fun readDocumentForSharedAccess(playerUuid: String): CoordinatedRead<NbtDocument?> =
        readForSharedAccess(playerUuid, nbtFileStore.nbtFormat::decodeDocumentFromSource)

    internal fun <T> readForSharedAccess(
        playerUuid: String,
        block: (KotlinxSource) -> T,
    ): CoordinatedRead<T?> {
        val primary = minecraftWorldPaths.playerData(playerUuid)
        val previous = minecraftWorldPaths.previousPlayerData(playerUuid)
        if (nbtFileStore.fileSystem.metadataOrNull(primary)?.isRegularFile == true) {
            return try {
                CoordinatedRead.Complete(nbtFileStore.read(primary, block = block))
            } catch (failure: Throwable) {
                if (!failure.isRecoverableNbtReadFailure()) throw failure
                CoordinatedRead.RequiresExclusive
            }
        }
        if (nbtFileStore.fileSystem.metadataOrNull(previous)?.isRegularFile != true) {
            return CoordinatedRead.Complete(null)
        }
        return CoordinatedRead.Complete(nbtFileStore.read(previous, block = block))
    }

    fun writeDocument(playerUuid: String, nbtDocument: NbtDocument) {
        write(playerUuid) { sink -> nbtFileStore.nbtFormat.encodeDocumentToSink(nbtDocument, sink) }
    }

    fun <T> write(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = write(playerUuid) { sink ->
        nbtFileStore.nbtFormat.encodeToSink(serializationStrategy, value, sink)
    }

    fun write(playerUuid: String, block: (KotlinxSink) -> Unit) {
        val target = minecraftWorldPaths.playerData(playerUuid)
        val parent = checkNotNull(target.parent)
        val temporary = nbtFileStore.writeSyncedTemporary(parent, block = block)
        try {
            nbtFileStore.fileSystem.replaceWithBackup(
                temporary = temporary,
                target = target,
                backup = minecraftWorldPaths.previousPlayerData(playerUuid),
            )
        } catch (failure: Throwable) {
            nbtFileStore.fileSystem.deleteIfExistsPreserving(temporary, failure)
            throw failure
        }
    }

    private fun copyCorrupted(primary: Path) {
        val fileSystem = nbtFileStore.fileSystem
        val parent = checkNotNull(primary.parent)
        var temporaryFileSink: TemporaryFileSink? = null
        try {
            val source = fileSystem.source(primary)
            useResource(source, { it.close() }) {
                val destination = fileSystem.openUniqueTemporarySink(
                    directory = parent,
                    prefix = "${primary.name}_corrupted_",
                )
                temporaryFileSink = destination
                val bufferedSink = destination.sink.buffer()
                useResource(bufferedSink, { it.close() }) {
                    bufferedSink.writeAll(source)
                }
            }
        } catch (failure: Throwable) {
            temporaryFileSink?.let { opened ->
                fileSystem.deleteIfExistsPreserving(opened.path, failure)
            }
            throw failure
        }
    }
}

class SavedDataFileStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val dimensionDirectory: DimensionDirectory = DimensionDirectory.Overworld,
    val nbtFileStore: NbtFileStore = NbtFileStore(),
) {
    fun readDocument(identifier: String): NbtDocument? =
        read(identifier, nbtFileStore.nbtFormat::decodeDocumentFromSource)

    fun <T> read(
        identifier: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = read(identifier) { source ->
        nbtFileStore.nbtFormat.decodeFromSource(deserializationStrategy, source)
    }

    fun <T> read(identifier: String, block: (KotlinxSource) -> T): T? {
        val path = minecraftWorldPaths.savedData(identifier, dimensionDirectory)
        if (nbtFileStore.fileSystem.metadataOrNull(path)?.isRegularFile != true) {
            return null
        }
        val compression = detectSavedDataCompression(path)
        return nbtFileStore.read(path, compression, block)
    }

    fun writeDocument(identifier: String, nbtDocument: NbtDocument) {
        write(identifier) { sink -> nbtFileStore.nbtFormat.encodeDocumentToSink(nbtDocument, sink) }
    }

    fun <T> write(
        identifier: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = write(identifier) { sink ->
        nbtFileStore.nbtFormat.encodeToSink(serializationStrategy, value, sink)
    }

    fun write(identifier: String, block: (KotlinxSink) -> Unit) {
        nbtFileStore.write(minecraftWorldPaths.savedData(identifier, dimensionDirectory), block = block)
    }

    private fun detectSavedDataCompression(path: Path): Compression {
        val bufferedSource = nbtFileStore.openSource(path).buffer()
        return useResource(bufferedSource, { it.close() }) {
            if (
                bufferedSource.request(2L) &&
                bufferedSource.buffer[0L] == GZIP_MAGIC_FIRST &&
                bufferedSource.buffer[1L] == GZIP_MAGIC_SECOND
            ) {
                Compression.GZIP
            } else {
                Compression.NONE
            }
        }
    }
}

class Utf8JsonFileStore internal constructor(internal val worldFileAccess: WorldFileAccess) {
    constructor(fileSystem: FileSystem = systemFileSystem) : this(
        worldFileAccess = WorldFileAccess.mutable(fileSystem),
    )

    val fileSystem: FileSystem
        get() = worldFileAccess.fileSystem

    fun readText(path: Path): String = read(path) { source -> source.readString() }

    /** Lends the UTF-8 file source for the duration of [block]. */
    fun <T> read(path: Path, block: (KotlinxSource) -> T): T =
        worldFileAccess.readFile(path) { bufferedSource, _ ->
            withOkioIoExceptions("Cannot read UTF-8 file $path") {
                block(bufferedSource.asKotlinxIoRawSource().buffered())
            }
        }

    fun readJson(path: Path, json: Json = Json): JsonElement =
        readJson(path, JsonElement.serializer(), json)

    fun <T> readJson(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = read(path) { source -> json.decodeFromSource(deserializationStrategy, source) }

    fun writeText(path: Path, text: String) = write(path) { sink -> sink.writeString(text) }

    /** Directly truncates and streams the final file. */
    fun write(path: Path, block: (KotlinxSink) -> Unit) {
        worldFileAccess.requireWritable()
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

    fun writeJson(path: Path, jsonElement: JsonElement, json: Json = Json) =
        writeJson(path, JsonElement.serializer(), jsonElement, json)

    fun <T> writeJson(
        path: Path,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = write(path) { sink -> json.encodeToSink(serializationStrategy, value, sink) }
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

private const val GZIP_MAGIC_FIRST: Byte = 0x1F
private const val GZIP_MAGIC_SECOND: Byte = 0x8B.toByte()
