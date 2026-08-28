package com.hiczp.minecraft.world.io

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource

/** Stateless player UUID to statistics JSON path and format policy. */
class PlayerStatisticsStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val utf8JsonFileStore: Utf8JsonFileStore = Utf8JsonFileStore(),
) {
    fun readText(playerUuid: String): String = utf8JsonFileStore.readText(minecraftWorldPaths.statistics(playerUuid))

    fun readJson(playerUuid: String, json: Json = Json): JsonElement =
        utf8JsonFileStore.readJsonElement(minecraftWorldPaths.statistics(playerUuid), json)

    fun <T> read(playerUuid: String, deserializationStrategy: DeserializationStrategy<T>, json: Json = Json): T =
        utf8JsonFileStore.readJson(minecraftWorldPaths.statistics(playerUuid), deserializationStrategy, json)

    inline fun <reified T> read(playerUuid: String, json: Json = Json): T =
        read(playerUuid, json.serializersModule.serializer(), json)

    fun <T> read(playerUuid: String, block: (BufferedSource) -> T): T =
        utf8JsonFileStore.read(minecraftWorldPaths.statistics(playerUuid), block)

    fun writeText(playerUuid: String, text: String) =
        utf8JsonFileStore.writeText(minecraftWorldPaths.statistics(playerUuid), text)

    fun writeJson(playerUuid: String, jsonElement: JsonElement, json: Json = Json) =
        utf8JsonFileStore.writeJsonElement(minecraftWorldPaths.statistics(playerUuid), jsonElement, json)

    fun <T> write(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        json: Json = Json,
    ) = utf8JsonFileStore.writeJson(minecraftWorldPaths.statistics(playerUuid), serializationStrategy, value, json)

    inline fun <reified T> write(playerUuid: String, value: T, json: Json = Json) =
        write(playerUuid, json.serializersModule.serializer(), value, json)

    fun write(playerUuid: String, block: (BufferedSink) -> Unit) =
        utf8JsonFileStore.write(minecraftWorldPaths.statistics(playerUuid), block)
}
