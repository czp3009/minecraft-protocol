package com.hiczp.minecraft.test.host

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MinecraftTestProcessTest {
    init {
        configureHostedTestSupportForJvmTests()
    }

    @Test
    fun logWaitersPreserveMatchingBufferingAndCancellationSemantics() = runTest {
        withFixture { minecraftTestProcess ->
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)

            val alpha = async(start = CoroutineStart.UNDISPATCHED) {
                minecraftTestProcess.waitForLog("alpha", TIMEOUT)
            }
            val beta = async(start = CoroutineStart.UNDISPATCHED) {
                minecraftTestProcess.waitForLog("beta", TIMEOUT)
            }

            minecraftTestProcess.sendLine("alpha")
            alpha.await()
            assertFalse(beta.isCompleted)

            minecraftTestProcess.sendLine("beta")
            beta.await()

            minecraftTestProcess.sendLine("buffered")
            minecraftTestProcess.waitForLog("ack:buffered", TIMEOUT)
            minecraftTestProcess.waitForLog("buffered", TIMEOUT)
            assertTrue("buffered" in minecraftTestProcess.logText())

            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                minecraftTestProcess.waitForLog("later", TIMEOUT)
            }
            cancelled.cancelAndJoin()
            minecraftTestProcess.sendLine("later")
            minecraftTestProcess.waitForLog("ack:later", TIMEOUT)
            minecraftTestProcess.waitForLog("later", TIMEOUT)
        }
    }

    @Test
    fun commandWaitersRejectMarkersEmittedBeforeTheirCommand() = runTest {
        withFixture { minecraftTestProcess ->
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)
            minecraftTestProcess.sendLineAndWait("old", "ack:old", TIMEOUT)

            val failure = assertFailsWith<IllegalStateException> {
                minecraftTestProcess.sendLineAndWait(
                    line = "new",
                    marker = "ack:old",
                    timeout = 100.milliseconds,
                )
            }

            assertTrue(failure.message.orEmpty().contains("did not emit"))
        }
    }

    @Test
    fun commandWaitersCanCorrelateOneOfSeveralNewMarkers() = runTest {
        withFixture { minecraftTestProcess ->
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)

            val processOutputMatch = minecraftTestProcess.sendLineAndWaitForAny(
                line = "new",
                markers = listOf("not-emitted", "ack:new"),
                timeout = TIMEOUT,
            )

            assertTrue("ack:new" in processOutputMatch.line)
        }
    }

    @Test
    fun prematureOutputClosureFailsWaitersWhileProcessIsAlive() = runTest {
        withFixture { minecraftTestProcess ->
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)
            minecraftTestProcess.sendLine(CLOSE_OUTPUT)

            val failure = assertFailsWith<IllegalStateException> {
                minecraftTestProcess.waitForLog("never-emitted", TIMEOUT)
            }

            assertTrue(failure.message.orEmpty().contains("output failed"))
            assertTrue(minecraftTestProcess.isAlive)
        }
    }

    @Test
    fun parentExitDoesNotWaitForAChildThatInheritedItsOutputPipe() = runTest {
        val workingDirectory = HostedMinecraftTestSupport.newScratchDirectory()
        val minecraftTestProcess = MinecraftTestProcess.start(
            command = listOf(
                "java",
                processFixtureSource("PipeParent.java").toString(),
                processFixtureSource("PipeChild.java").toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "inherited-output-fixture",
        )
        val managedMinecraftTestResource = HostedMinecraftTestSupport.manageTestResource(
            workingDirectory,
        ) {
            minecraftTestProcess.terminate()
        }
        try {
            minecraftTestProcess.waitForLog(PARENT_READY, TIMEOUT)
            assertNull(minecraftTestProcess.awaitExitWithin(100.milliseconds))
            minecraftTestProcess.sendLine(SPAWN_CHILD_AND_EXIT)
            assertEquals(0, minecraftTestProcess.awaitExitWithin(TIMEOUT))
        } finally {
            managedMinecraftTestResource.close()
            HostedMinecraftTestSupport.awaitCleanup()
        }
    }

    @Test
    fun processExitFailsWaitersAndTerminationRemainsIdempotent() = runTest {
        withFixture { minecraftTestProcess ->
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)
            val missing = async(start = CoroutineStart.UNDISPATCHED) {
                val failure = try {
                    minecraftTestProcess.waitForLog("never-emitted", TIMEOUT)
                    null
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    failure
                }
                checkNotNull(failure) { "Missing marker unexpectedly appeared" }
            }

            minecraftTestProcess.sendLine(EXIT)
            val failure = missing.await()
            assertIs<IllegalStateException>(failure)
            assertTrue(failure.message.orEmpty().contains("before log marker"))
            assertNotNull(minecraftTestProcess.awaitExitWithin(TIMEOUT))
            minecraftTestProcess.terminate()
            minecraftTestProcess.terminate()
        }
    }

    @Test
    fun processExitDrainsTrailingOutputBeforePublishingExit() = runTest {
        withFixture { minecraftTestProcess ->
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)
            minecraftTestProcess.sendLine(OUTPUT_AND_EXIT)

            assertEquals(0, minecraftTestProcess.awaitExit())
            assertTrue("exit-output-9999" in minecraftTestProcess.logText())
        }
    }

    @Test
    fun terminateForcesAProcessThatIgnoresItsShutdownCommand() = runTest {
        val workingDirectory = HostedMinecraftTestSupport.newScratchDirectory()
        val minecraftTestProcess = MinecraftTestProcess.start(
            command = listOf(
                "java",
                processFixtureSource("Fixture.java").toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "forced-minecraft-test-process-fixture",
            shutdownCommand = IGNORED_SHUTDOWN_COMMAND,
        )
        val managedMinecraftTestResource = HostedMinecraftTestSupport.manageTestResource(
            workingDirectory,
        ) {
            minecraftTestProcess.terminate()
        }
        try {
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)
            minecraftTestProcess.sendLine(IGNORED_SHUTDOWN_COMMAND)
            minecraftTestProcess.waitForLog(
                "ack:$IGNORED_SHUTDOWN_COMMAND",
                TIMEOUT,
            )

            val exitCode = minecraftTestProcess.terminate(
                gracefulTimeout = 500.milliseconds,
                forcedTimeout = TIMEOUT,
            )

            assertFalse(minecraftTestProcess.isAlive)
            assertEquals(exitCode, minecraftTestProcess.exitCode)
        } finally {
            managedMinecraftTestResource.close()
            HostedMinecraftTestSupport.awaitCleanup()
        }
    }

    @Test
    fun terminationTimeoutIncludesWaitingForTheCommandLock() = runTest {
        withFixture { minecraftTestProcess ->
            minecraftTestProcess.waitForLog(STARTED, TIMEOUT)
            val blockedCommand = async(start = CoroutineStart.UNDISPATCHED) {
                val failure = try {
                    minecraftTestProcess.sendLineAndWait(
                        line = "blocked-command",
                        marker = "never-emitted",
                        timeout = TIMEOUT,
                    )
                    null
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    failure
                }
                checkNotNull(failure) { "Blocked command unexpectedly completed" }
            }
            minecraftTestProcess.waitForLog("ack:blocked-command", TIMEOUT)

            val exitCode = withContext(Dispatchers.Default) {
                withTimeout(2.seconds) {
                    minecraftTestProcess.terminate(
                        gracefulTimeout = 100.milliseconds,
                        forcedTimeout = TIMEOUT,
                    )
                }
            }

            assertFalse(minecraftTestProcess.isAlive)
            assertEquals(exitCode, minecraftTestProcess.exitCode)
            assertIs<IllegalStateException>(blockedCommand.await())
        }
    }

    private suspend fun withFixture(
        block: suspend (MinecraftTestProcess) -> Unit,
    ) {
        val processFixture = startFixture()
        try {
            block(processFixture.minecraftTestProcess)
        } finally {
            processFixture.close()
            HostedMinecraftTestSupport.awaitCleanup()
        }
    }

    private suspend fun startFixture(): ProcessFixture {
        val workingDirectory = HostedMinecraftTestSupport.newScratchDirectory()
        val source = processFixtureSource("Fixture.java")
        val minecraftTestProcess = MinecraftTestProcess.start(
            command = listOf(
                "java",
                source.toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "minecraft-test-process-fixture-log",
            shutdownCommand = EXIT,
        )
        val managedMinecraftTestResource = HostedMinecraftTestSupport.manageTestResource(
            workingDirectory,
        ) {
            minecraftTestProcess.terminate()
        }
        return ProcessFixture(minecraftTestProcess, managedMinecraftTestResource)
    }

    private class ProcessFixture(
        val minecraftTestProcess: MinecraftTestProcess,
        private val managedMinecraftTestResource: ManagedMinecraftTestResource,
    ) : AutoCloseable {
        override fun close() {
            managedMinecraftTestResource.close()
        }
    }

    private companion object {
        const val STARTED = "fixture-started"
        const val EXIT = "fixture-exit"
        const val OUTPUT_AND_EXIT = "fixture-output-and-exit"
        const val CLOSE_OUTPUT = "fixture-close-output"
        const val IGNORED_SHUTDOWN_COMMAND = "fixture-ignore-stop"
        const val PARENT_READY = "parent-ready"
        const val SPAWN_CHILD_AND_EXIT = "spawn-child-and-exit"
        val TIMEOUT = 10.seconds
    }
}
