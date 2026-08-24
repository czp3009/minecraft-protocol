package com.hiczp.minecraft.test.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostedFixtureResourcesTest {
    @Test
    fun closingOwnerCancelsInFlightCreationAndRejectsLateCreation() = runTest {
        val resources = HostedFixtureResources(maximumParallelUsages = 1)
        val creationStarted = CompletableDeferred<Unit>()
        val creation = async(start = CoroutineStart.UNDISPATCHED) {
            resources.withOwnerCreation("owner") {
                creationStarted.complete(Unit)
                awaitCancellation()
            }
        }

        creationStarted.await()
        resources.closeOwner("owner")
        creation.join()

        assertTrue(creation.isCancelled)
        assertFailsWith<IllegalStateException> {
            resources.withOwnerCreation("owner") { Unit }
        }
    }
}
