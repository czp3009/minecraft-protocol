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

/** UTF-8 and configured JSON operations for caller-supplied exact paths. */
class Utf8JsonFileStore internal constructor(
    val rawFileStore: RawFileStore,
    val json: Json,
) {
    constructor(
        fileSystem: FileSystem = systemFileSystem,
        json: Json = Json,
    ) : this(RawFileStore(fileSystem), json)

    internal constructor(worldFileAccess: WorldFileAccess, json: Json = Json) :
            this(RawFileStore(worldFileAccess), json)

    val fileSystem: FileSystem
        get() = rawFileStore.fileSystem

    fun readJsonElement(path: Path): JsonElement = readJson(path, JsonElement.serializer())

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> readJson(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = readJson(path) { source ->
        withOkioIoFailures {
            json.decodeFromSource(deserializationStrategy, source.asKotlinxIoRawSource().buffered())
        }
    }

    inline fun <reified T> readJson(path: Path): T = readJson(path, json.serializersModule.serializer())

    fun <T> readJson(path: Path, block: (BufferedSource) -> T): T = rawFileStore.read(path, block)

    internal fun readJsonElementOrNull(path: Path): JsonElement? =
        readJsonOrNull(path, JsonElement.serializer())

    @OptIn(ExperimentalSerializationApi::class)
    internal fun <T> readJsonOrNull(
        path: Path,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readJsonOrNull(path) { source ->
        withOkioIoFailures {
            json.decodeFromSource(deserializationStrategy, source.asKotlinxIoRawSource().buffered())
        }
    }

    internal inline fun <reified T> readJsonOrNull(path: Path): T? =
        readJsonOrNull(path, json.serializersModule.serializer())

    internal fun <T> readJsonOrNull(path: Path, block: (BufferedSource) -> T): T? =
        rawFileStore.readRegularFileOrNull(path, block)

    fun writeJsonElement(path: Path, jsonElement: JsonElement) =
        writeJson(path, jsonElement, JsonElement.serializer())

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> writeJson(
        path: Path,
        value: T,
        serializationStrategy: SerializationStrategy<T>,
    ) = writeJson(path) { sink ->
        val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
        withOkioIoFailures {
            json.encodeToSink(serializationStrategy, value, kotlinxSink)
            kotlinxSink.emit()
        }
    }

    inline fun <reified T> writeJson(path: Path, value: T) =
        writeJson(path, value, json.serializersModule.serializer())

    fun writeJson(path: Path, block: (BufferedSink) -> Unit) = rawFileStore.write(path, block)
}
