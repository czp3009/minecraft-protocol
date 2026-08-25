package com.hiczp.minecraft.test.host

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopbackPortSelectionTest {
    @Test
    fun automaticPortIsSelectedAndReleased() = runTest {
        var lastBindFailure: Throwable? = null
        repeat(MAXIMUM_BIND_ATTEMPTS) {
            val selectedPort = selectAvailableLoopbackPort()
            assertTrue(selectedPort in 1..65_535)

            try {
                SelectorManager(Dispatchers.Default).use { selector ->
                    aSocket(selector).tcp().bind(
                        hostname = "127.0.0.1",
                        port = selectedPort,
                    ).use { listener ->
                        assertEquals(selectedPort, listener.port)
                    }
                }
                return@runTest
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                lastBindFailure = failure
            }
        }
        throw AssertionError(
            "Every selected loopback port was claimed before the test could rebind it",
            lastBindFailure,
        )
    }

    private companion object {
        const val MAXIMUM_BIND_ATTEMPTS = 5
    }
}
