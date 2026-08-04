package com.hiczp.minecraft.test

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MinecraftTestProcessTest {
    init {
        configureHostedTestSupportForJvmTests()
    }

    @Test
    fun logMarkersWakeOnlyTheMatchingWaiters() = runTest {
        withFixture { process ->
            process.waitForLog(STARTED, TIMEOUT)
            val alpha = async(start = CoroutineStart.UNDISPATCHED) {
                process.waitForLog("alpha", TIMEOUT)
            }
            val beta = async(start = CoroutineStart.UNDISPATCHED) {
                process.waitForLog("beta", TIMEOUT)
            }

            process.sendLine("alpha")
            alpha.await()
            assertFalse(beta.isCompleted)

            process.sendLine("beta")
            beta.await()
        }
    }

    @Test
    fun markerAlreadyInTheBoundedLogCompletesImmediately() = runTest {
        withFixture { process ->
            process.waitForLog(STARTED, TIMEOUT)
            process.sendLine("buffered")
            process.waitForLog("ack:buffered", TIMEOUT)

            process.waitForLog("buffered", TIMEOUT)
            assertTrue("buffered" in process.logText())
        }
    }

    @Test
    fun parentExitDoesNotWaitForAChildThatInheritedItsOutputPipe() = runTest {
        val workingDirectory = HostedMinecraftTestSupport.newScratchDirectory()
        val process = MinecraftTestProcess.start(
            command = listOf(
                HostedMinecraftTestSupport.layout.javaExecutable.toString(),
                processFixtureSource("PipeParent.java").toString(),
                processFixtureSource("PipeChild.java").toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "inherited-output-fixture",
        )
        val resource = HostedMinecraftTestSupport.manageTestResource(
            workingDirectory,
        ) {
            process.close()
            runCatching { process.awaitExit() }
        }
        try {
            process.waitForLog(PARENT_READY, TIMEOUT)
            assertNull(process.awaitExitWithin(100.milliseconds))
            process.sendLine(SPAWN_CHILD_AND_EXIT)
            assertEquals(0, process.awaitExitWithin(TIMEOUT))
        } finally {
            resource.close()
            HostedMinecraftTestSupport.awaitCleanup()
        }
    }

    @Test
    fun processExitBeforeMarkerFailsTheWaiter() = runTest {
        withFixture { process ->
            process.waitForLog(STARTED, TIMEOUT)
            val missing = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    process.waitForLog("never-emitted", TIMEOUT)
                }.exceptionOrNull()
            }

            process.sendLine(EXIT)
            val failure = missing.await()
            assertIs<IllegalStateException>(failure)
            assertTrue(failure.message.orEmpty().contains("before log marker"))
        }
    }

    @Test
    fun cancellingALogWaiterDoesNotConsumeLaterMarkers() = runTest {
        withFixture { process ->
            process.waitForLog(STARTED, TIMEOUT)
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                process.waitForLog("later", TIMEOUT)
            }
            cancelled.cancelAndJoin()

            process.sendLine("later")
            process.waitForLog("ack:later", TIMEOUT)
            process.waitForLog("later", TIMEOUT)
        }
    }

    @Test
    fun closeIsIdempotentAfterNaturalExit() = runTest {
        withFixture { process ->
            process.waitForLog(STARTED, TIMEOUT)
            process.sendLine(EXIT)
            assertNotNull(process.awaitExitWithin(TIMEOUT))
            process.close()
            process.close()
        }
    }

    private suspend fun withFixture(
        block: suspend (MinecraftTestProcess) -> Unit,
    ) {
        val fixture = startFixture()
        try {
            block(fixture.process)
        } finally {
            fixture.close()
            HostedMinecraftTestSupport.awaitCleanup()
        }
    }

    private suspend fun startFixture(): ProcessFixture {
        val workingDirectory = HostedMinecraftTestSupport.newScratchDirectory()
        val source = processFixtureSource("Fixture.java")
        val process = MinecraftTestProcess.start(
            command = listOf(
                HostedMinecraftTestSupport.layout.javaExecutable.toString(),
                source.toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "minecraft-test-process-fixture-log",
        )
        val resource = HostedMinecraftTestSupport.manageTestResource(
            workingDirectory,
        ) {
            process.close()
            runCatching { process.awaitExit() }
        }
        return ProcessFixture(process, resource)
    }

    private class ProcessFixture(
        val process: MinecraftTestProcess,
        private val resource: ManagedMinecraftTestResource,
    ) : AutoCloseable {
        override fun close() {
            resource.close()
        }
    }

    private companion object {
        const val STARTED = "fixture-started"
        const val EXIT = "fixture-exit"
        const val PARENT_READY = "parent-ready"
        const val SPAWN_CHILD_AND_EXIT = "spawn-child-and-exit"
        val TIMEOUT = 10.seconds
    }
}
