package com.hiczp.minecraft.world.format

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerStatisticsTest {
    @Test
    fun statisticsRetainsDynamicTypeAndIdentifierMaps() {
        val expected = PlayerStatistics(
            stats = mapOf(
                "minecraft:mined" to mapOf(
                    "minecraft:stone" to 42,
                    "example:custom_block" to 7,
                ),
                "example:custom_type" to mapOf("example:value" to Int.MAX_VALUE),
            ),
            dataVersion = 4_903,
        )
        val encoded = Json.encodeToString(expected)

        assertEquals(expected, Json.decodeFromString<PlayerStatistics>(encoded))
        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement<PlayerStatistics>(
                buildJsonObject { put("stats", JsonObject(emptyMap())) },
            )
        }
    }
}
