package com.hiczp.minecraft.test

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class TestResourceConfigurationTest {
    @Test
    fun automaticPortIsSelectedAndReleasedThroughPortableSockets() = runTest {
        val selectedPort = selectAvailableLoopbackPort()
        assertTrue(selectedPort in 1..65_535)

        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().bind(
                hostname = "127.0.0.1",
                port = selectedPort,
            ).use { listener ->
                assertEquals(selectedPort, listener.port)
            }
        }
    }

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
