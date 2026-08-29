package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.Compression
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
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
    val nbtFormat: NbtFormat
        get() = nbtFileStore.nbtFormat

    val json: Json
        get() = utf8JsonFileStore.json

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
        compression: Compression = Compression.GZIP,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = withOperation { nbtFileStore.read(path, compression, deserializationStrategy) }

    suspend inline fun <reified T> readNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
    ): T = readNbt(path, compression, nbtFormat.serializersModule.serializer())

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
        value: T,
        compression: Compression = Compression.GZIP,
        serializationStrategy: SerializationStrategy<T>,
    ) = withOperation { nbtFileStore.write(path, value, compression, serializationStrategy) }

    suspend inline fun <reified T> writeNbt(
        path: Path,
        value: T,
        compression: Compression = Compression.GZIP,
    ) = writeNbt(path, value, compression, nbtFormat.serializersModule.serializer())

    suspend fun writeNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSink) -> Unit,
    ) = withOperation { nbtFileStore.write(path, compression, block) }

    suspend fun readJsonElement(path: Path): JsonElement =
        withOperation { utf8JsonFileStore.readJsonElement(path) }

    suspend fun <T> readJson(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = withOperation { utf8JsonFileStore.readJson(path, deserializationStrategy) }

    suspend inline fun <reified T> readJson(path: Path): T =
        readJson(path, json.serializersModule.serializer())

    suspend fun <T> readJson(path: Path, block: (BufferedSource) -> T): T =
        withOperation { utf8JsonFileStore.readJson(path, block) }

    suspend fun writeJsonElement(path: Path, jsonElement: JsonElement) =
        withOperation { utf8JsonFileStore.writeJsonElement(path, jsonElement) }

    suspend fun <T> writeJson(
        path: Path,
        value: T,
        serializationStrategy: SerializationStrategy<T>,
    ) = withOperation { utf8JsonFileStore.writeJson(path, value, serializationStrategy) }

    suspend inline fun <reified T> writeJson(path: Path, value: T) =
        writeJson(path, value, json.serializersModule.serializer())

    suspend fun writeJson(path: Path, block: (BufferedSink) -> Unit) =
        withOperation { utf8JsonFileStore.writeJson(path, block) }

    private suspend fun <T> withOperation(block: () -> T): T = worldOperationLifecycle.withOperation { block() }
}

/** Exact-path, synchronous, read-only access for a live world observer. */
class LiveMinecraftWorldDirectFiles internal constructor(
    private val rawFileStore: RawFileStore,
    private val nbtFileStore: NbtFileStore,
    private val utf8JsonFileStore: Utf8JsonFileStore,
) {
    val nbtFormat: NbtFormat
        get() = nbtFileStore.nbtFormat

    val json: Json
        get() = utf8JsonFileStore.json

    fun readBytes(path: Path): ByteArray = rawFileStore.readBytes(path)

    fun <T> read(path: Path, block: (BufferedSource) -> T): T = rawFileStore.read(path, block)

    fun readNbtDocument(path: Path, compression: Compression = Compression.GZIP): NbtDocument =
        nbtFileStore.readDocument(path, compression)

    fun <T> readNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = nbtFileStore.read(path, compression, deserializationStrategy)

    inline fun <reified T> readNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
    ): T = readNbt(path, compression, nbtFormat.serializersModule.serializer())

    fun <T> readNbt(
        path: Path,
        compression: Compression = Compression.GZIP,
        block: (BufferedSource) -> T,
    ): T = nbtFileStore.read(path, compression, block)

    fun readJsonElement(path: Path): JsonElement = utf8JsonFileStore.readJsonElement(path)

    fun <T> readJson(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = utf8JsonFileStore.readJson(path, deserializationStrategy)

    inline fun <reified T> readJson(path: Path): T = readJson(path, json.serializersModule.serializer())

    fun <T> readJson(path: Path, block: (BufferedSource) -> T): T = utf8JsonFileStore.readJson(path, block)
}
