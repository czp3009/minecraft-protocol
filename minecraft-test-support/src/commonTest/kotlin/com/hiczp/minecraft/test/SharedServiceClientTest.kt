package com.hiczp.minecraft.test

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedServiceClientTest {
    @Test
    fun closingClientWaitsForExistingUsersAndDetachesFromNewCalls() = runTest {
        var nextClient = 0
        val closedClients = mutableListOf<Int>()
        val clients = SharedServiceClient(
            createClient = { ++nextClient },
            closeClient = closedClients::add,
        )
        val firstUseStarted = CompletableDeferred<Unit>()
        val releaseFirstUse = CompletableDeferred<Unit>()
        val firstUse = async(start = CoroutineStart.UNDISPATCHED) {
            clients.use { client ->
                firstUseStarted.complete(Unit)
                releaseFirstUse.await()
                client
            }
        }
        firstUseStarted.await()

        clients.use(closeAfter = true) { client ->
            assertEquals(1, client)
        }
        assertTrue(closedClients.isEmpty())
        assertEquals(2, clients.use { client -> client })

        releaseFirstUse.complete(Unit)
        assertEquals(1, firstUse.await())
        assertEquals(listOf(1), closedClients)

        clients.use(closeAfter = true) { client ->
            assertEquals(2, client)
        }
        assertEquals(listOf(1, 2), closedClients)
    }

    @Test
    fun failedCallDetachesClientWithoutClosingConcurrentUses() = runTest {
        var nextClient = 0
        val closedClients = mutableListOf<Int>()
        val clients = SharedServiceClient(
            createClient = { ++nextClient },
            closeClient = closedClients::add,
        )
        val firstUseStarted = CompletableDeferred<Unit>()
        val releaseFirstUse = CompletableDeferred<Unit>()
        val firstUse = async(start = CoroutineStart.UNDISPATCHED) {
            clients.use { client ->
                firstUseStarted.complete(Unit)
                releaseFirstUse.await()
                client
            }
        }
        firstUseStarted.await()

        val expectedFailure = IllegalStateException("RPC failed")
        val actualFailure = try {
            clients.use { throw expectedFailure }
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            failure
        }
        assertEquals(expectedFailure, actualFailure)
        assertTrue(closedClients.isEmpty())
        assertEquals(2, clients.use { client -> client })

        releaseFirstUse.complete(Unit)
        assertEquals(1, firstUse.await())
        assertEquals(listOf(1), closedClients)
        clients.use(closeAfter = true) {}
    }
}
