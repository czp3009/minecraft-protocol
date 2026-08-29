package com.hiczp.minecraft.world.format

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerAdvancementsTest {
    @Test
    fun advancementRootStreamsHeterogeneousMapEntriesInAnyOrder() {
        val advancements = linkedMapOf<String, PlayerAdvancements.Progress>()
        repeat(2_048) { index ->
            advancements["example:advancement_$index"] = PlayerAdvancements.Progress(
                criteria = mapOf("criterion_$index" to "2026-08-18 00:00:00 +0000"),
                done = index % 2 == 0,
            )
        }
        val expected = PlayerAdvancements(4_903, advancements)
        val encoded = Json.encodeToString(expected)

        assertEquals(expected, Json.decodeFromString<PlayerAdvancements>(encoded))
        val reordered = buildJsonObject {
            put(
                "example:first",
                buildJsonObject {
                    put(
                        "criteria",
                        buildJsonObject {
                            put("criterion", JsonPrimitive("2026-08-18 00:00:00 +0000"))
                        },
                    )
                    put("done", JsonPrimitive(true))
                },
            )
            put("DataVersion", JsonPrimitive(4_903))
        }
        assertEquals(
            PlayerAdvancements(
                dataVersion = 4_903,
                advancements = mapOf(
                    "example:first" to PlayerAdvancements.Progress(
                        criteria = mapOf("criterion" to "2026-08-18 00:00:00 +0000"),
                        done = true,
                    ),
                ),
            ),
            Json.decodeFromJsonElement<PlayerAdvancements>(reordered),
        )
    }

    @Test
    fun advancementRootRequiresDataVersionAndProgressRejectsUnknownFields() {
        val progress = buildJsonObject {
            put("criteria", JsonObject(emptyMap()))
            put("done", JsonPrimitive(false))
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement<PlayerAdvancements>(
                buildJsonObject { put("example:missing_version", progress) },
            )
        }
        val validJson = Json.encodeToString(
            PlayerAdvancements(4_903, emptyMap()),
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PlayerAdvancements>(validJson.dropLast(1))
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement<PlayerAdvancements>(
                buildJsonObject {
                    put("DataVersion", JsonPrimitive(4_903))
                    put(
                        "example:unknown_field",
                        buildJsonObject {
                            put("criteria", JsonObject(emptyMap()))
                            put("done", JsonPrimitive(false))
                            put("future", JsonPrimitive(true))
                        },
                    )
                },
            )
        }
    }
}
