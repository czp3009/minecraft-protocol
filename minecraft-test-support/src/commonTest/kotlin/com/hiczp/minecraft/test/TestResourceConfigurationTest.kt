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
        val endpoint = MinecraftTestEndpoint("127.0.0.1", 25_565)
        assertFailsWith<IllegalArgumentException> {
            HeadlessMinecraftClientConfiguration("bad name", endpoint)
        }
        assertFailsWith<IllegalArgumentException> {
            HeadlessMinecraftClientConfiguration(
                "Player",
                MinecraftTestEndpoint("localhost", 25_565),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HeadlessMinecraftClientConfiguration(
                "Player",
                MinecraftTestEndpoint("127.0.0.1", 0),
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
