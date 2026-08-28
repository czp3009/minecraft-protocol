package com.hiczp.minecraft.world.io

import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.io.decodeFromSource
import kotlinx.serialization.json.io.encodeToSink
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path

/** Stateless UTF-8 and JSON operations for caller-supplied exact paths. */
class Utf8JsonFileStore internal constructor(
    val rawFileStore: RawFileStore,
) {
    constructor(fileSystem: FileSystem = systemFileSystem) : this(RawFileStore(fileSystem))

    internal constructor(worldFileAccess: WorldFileAccess) : this(RawFileStore(worldFileAccess))

    val fileSystem: FileSystem
        get() = rawFileStore.fileSystem

    fun readText(path: Path): String = read(path) { source -> source.readUtf8() }

    fun <T> read(path: Path, block: (BufferedSource) -> T): T = rawFileStore.read(path, block)

    fun readJsonElement(path: Path, json: Json = Json): JsonElement =
        readJson(path, JsonElement.serializer(), json)

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> readJson(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
        json: Json = Json,
    ): T = read(path) { source ->
        withOkioIoFailures {
            json.decodeFromSource(deserializationStrategy, source.asKotlinxIoRawSource().buffered())
        }
    }

    inline fun <reified T> readJson(path: Path, json: Json = Json): T =
        readJson(path, json.serializersModule.serializer(), json)

    fun writeText(path: Path, text: String) = write(path) { sink -> sink.writeUtf8(text) }

    fun write(path: Path, block: (BufferedSink) -> Unit) = rawFileStore.write(path, block)

    fun writeJsonElement(path: Path, jsonElement: JsonElement, json: Json = Json) =
        writeJson(path, JsonElement.serializer(), jsonElement, json)

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> writeJson(
        path: Path,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = write(path) { sink ->
        val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
        withOkioIoFailures {
            json.encodeToSink(serializationStrategy, value, kotlinxSink)
            kotlinxSink.emit()
        }
    }

    inline fun <reified T> writeJson(path: Path, value: T, json: Json = Json) =
        writeJson(path, json.serializersModule.serializer(), value, json)
}
