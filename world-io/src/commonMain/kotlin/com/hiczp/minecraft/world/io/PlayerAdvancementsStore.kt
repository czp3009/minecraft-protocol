package com.hiczp.minecraft.world.io

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource

/** Stateless player UUID to advancements JSON path and format policy. */
class PlayerAdvancementsStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val utf8JsonFileStore: Utf8JsonFileStore = Utf8JsonFileStore(),
) {
    fun readJson(playerUuid: String): JsonElement? =
        utf8JsonFileStore.readJsonElementOrNull(minecraftWorldPaths.advancements(playerUuid))

    fun <T> read(playerUuid: String, deserializationStrategy: DeserializationStrategy<T>): T? =
        utf8JsonFileStore.readJsonOrNull(minecraftWorldPaths.advancements(playerUuid), deserializationStrategy)

    inline fun <reified T> read(playerUuid: String): T? =
        read(playerUuid, utf8JsonFileStore.json.serializersModule.serializer())

    fun <T> read(playerUuid: String, block: (BufferedSource) -> T): T? =
        utf8JsonFileStore.readJsonOrNull(minecraftWorldPaths.advancements(playerUuid), block)

    fun writeJson(playerUuid: String, jsonElement: JsonElement) =
        utf8JsonFileStore.writeJsonElement(minecraftWorldPaths.advancements(playerUuid), jsonElement)

    fun <T> write(
        playerUuid: String,
        value: T,
        serializationStrategy: SerializationStrategy<T>,
    ) = utf8JsonFileStore.writeJson(minecraftWorldPaths.advancements(playerUuid), value, serializationStrategy)

    inline fun <reified T> write(playerUuid: String, value: T) =
        write(playerUuid, value, utf8JsonFileStore.json.serializersModule.serializer())

    fun write(playerUuid: String, block: (BufferedSink) -> Unit) =
        utf8JsonFileStore.writeJson(minecraftWorldPaths.advancements(playerUuid), block)
}
