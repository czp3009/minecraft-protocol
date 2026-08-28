package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.Compression
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.BufferedSink
import okio.BufferedSource
import okio.Path

/**
 * Uncoordinated exact-path file access that still participates in a mutable world's close barrier.
 * Paths are neither resolved against the world root nor mapped to semantic logical keys.
 */
class MinecraftWorldDirectFiles internal constructor(
    private val worldOperationLifecycle: WorldOperationLifecycle,
    private val rawFileStore: RawFileStore,
    private val nbtFileStore: NbtFileStore,
    private val utf8JsonFileStore: Utf8JsonFileStore,
) {
    suspend fun readBytes(path: Path): ByteArray = withOperation { rawFileStore.readBytes(path) }

    suspend fun <T> read(path: Path, block: (BufferedSource) -> T): T =
        withOperation { rawFileStore.read(path, block) }

    suspend fun writeBytes(path: Path, bytes: ByteArray) = withOperation { rawFileStore.writeBytes(path, bytes) }

    suspend fun write(path: Path, block: (BufferedSink) -> Unit) =
        withOperation { rawFileStore.write(path, block) }

    suspend fun readNbtDocument(path: Path, compression: Compression = Compression.GZIP): NbtDocument =
        withOperation { nbtFileStore.readDocument(path, compression) }

    suspend fun <T> readNbt(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        compression: Compression = Compression.GZIP,
    ): T = withOperation { nbtFileStore.read(path, deserializationStrategy, compression) }

    suspend fun <T> readNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSource) -> T,
    ): T = withOperation { nbtFileStore.read(path, compression, block) }

    suspend fun writeNbtDocument(
        path: Path,
        nbtDocument: NbtDocument,
        compression: Compression = Compression.GZIP,
    ) = withOperation { nbtFileStore.writeDocument(path, nbtDocument, compression) }

    suspend fun <T> writeNbt(
        path: Path,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        compression: Compression = Compression.GZIP,
    ) = withOperation { nbtFileStore.write(path, serializationStrategy, value, compression) }

    suspend fun writeNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSink) -> Unit,
    ) = withOperation { nbtFileStore.write(path, compression, block) }

    suspend fun readJsonText(path: Path): String = withOperation { utf8JsonFileStore.readText(path) }

    suspend fun <T> readJson(path: Path, block: (BufferedSource) -> T): T =
        withOperation { utf8JsonFileStore.read(path, block) }

    suspend fun readJsonElement(path: Path, json: Json = Json): JsonElement =
        withOperation { utf8JsonFileStore.readJson(path, json) }

    suspend fun <T> readJson(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = withOperation { utf8JsonFileStore.readJson(path, deserializationStrategy, json) }

    suspend fun writeJsonText(path: Path, text: String) = withOperation { utf8JsonFileStore.writeText(path, text) }

    suspend fun writeJson(path: Path, block: (BufferedSink) -> Unit) =
        withOperation { utf8JsonFileStore.write(path, block) }

    suspend fun writeJsonElement(path: Path, jsonElement: JsonElement, json: Json = Json) =
        withOperation { utf8JsonFileStore.writeJson(path, jsonElement, json) }

    suspend fun <T> writeJson(
        path: Path,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = withOperation { utf8JsonFileStore.writeJson(path, serializationStrategy, value, json) }

    private suspend fun <T> withOperation(block: () -> T): T = worldOperationLifecycle.withOperation { block() }
}

/** Exact-path, synchronous, read-only access for a live world observer. */
class LiveMinecraftWorldDirectFiles internal constructor(
    private val rawFileStore: RawFileStore,
    private val nbtFileStore: NbtFileStore,
    private val utf8JsonFileStore: Utf8JsonFileStore,
) {
    fun readBytes(path: Path): ByteArray = rawFileStore.readBytes(path)

    fun <T> read(path: Path, block: (BufferedSource) -> T): T = rawFileStore.read(path, block)

    fun readNbtDocument(path: Path, compression: Compression = Compression.GZIP): NbtDocument =
        nbtFileStore.readDocument(path, compression)

    fun <T> readNbt(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        compression: Compression = Compression.GZIP,
    ): T = nbtFileStore.read(path, deserializationStrategy, compression)

    fun <T> readNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSource) -> T,
    ): T = nbtFileStore.read(path, compression, block)

    fun readJsonText(path: Path): String = utf8JsonFileStore.readText(path)

    fun <T> readJson(path: Path, block: (BufferedSource) -> T): T = utf8JsonFileStore.read(path, block)

    fun readJsonElement(path: Path, json: Json = Json): JsonElement = utf8JsonFileStore.readJson(path, json)

    fun <T> readJson(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = utf8JsonFileStore.readJson(path, deserializationStrategy, json)
}
