package com.hiczp.minecraft.test

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

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
}
