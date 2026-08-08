package com.hiczp.minecraft.test

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class TestResourceConfigurationTest {
    @Test
    fun officialServerConfigurationRejectsInvalidResourceLimits() {
        assertFailsWith<IllegalArgumentException> {
            OfficialMinecraftServerConfiguration(
                startupTimeout = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OfficialMinecraftServerConfiguration(
                stopTimeout = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OfficialMinecraftServerConfiguration(maximumBindAttempts = 0)
        }
    }

    @Test
    fun headlessClientConfigurationRejectsUnsafeProcessInputs() {
        assertFailsWith<IllegalArgumentException> {
            HeadlessMinecraftClientConfiguration("bad name")
        }
        assertFailsWith<IllegalArgumentException> {
            HeadlessMinecraftClientConfiguration(
                playerName = "Player",
                startupTimeout = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HeadlessMinecraftClientConfiguration(
                playerName = "Player",
                stopTimeout = Duration.ZERO,
            )
        }
    }

    @Test
    fun serviceConfigurationAndResourceValuesRoundTripDirectly() {
        val configuration = OfficialMinecraftServerConfiguration(
            properties = mapOf("level-name" to "round-trip"),
            startupTimeout = 1.nanoseconds,
        )
        assertEquals(
            configuration,
            Json.decodeFromString(
                Json.encodeToString(configuration),
            ),
        )

        val resource: MinecraftTestResource = OfficialMinecraftServer(
            id = "server-id",
            endpoint = MinecraftTestEndpoint("127.0.0.1", 25_565),
        )
        assertEquals(
            resource,
            Json.decodeFromString<MinecraftTestResource>(
                Json.encodeToString(resource),
            ),
        )
    }
}
