package com.hiczp.minecraft.test

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopbackPortSelectionTest {
    @Test
    fun automaticPortIsSelectedAndReleased() = runTest {
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
}
