package com.hiczp.minecraft.test

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class MinecraftTestProcessTest {
    @Test
    fun logMarkersWakeOnlyTheMatchingWaiters() = runTest {
        startFixture().use { process ->
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
        startFixture().use { process ->
            process.waitForLog(STARTED, TIMEOUT)
            process.sendLine("buffered")
            process.waitForLog("ack:buffered", TIMEOUT)

            process.waitForLog("buffered", TIMEOUT)
            assertTrue(process.logContains("buffered"))
        }
    }

    @Test
    fun processExitBeforeMarkerFailsTheWaiter() = runTest {
        startFixture().use { process ->
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
        startFixture().use { process ->
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
        startFixture().use { process ->
            process.waitForLog(STARTED, TIMEOUT)
            process.sendLine(EXIT)
            assertNotNull(process.awaitExitWithin(TIMEOUT))
            process.close()
            process.close()
        }
    }

    private suspend fun startFixture(): MinecraftTestProcess {
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "minecraft-test-support",
            minecraftVersion = "test-version",
        )
        val workingDirectory = environment.freshWorkDirectory(
            "process-fixture",
        )
        val source = Path(workingDirectory, "Fixture.java")
        source.atomicWriteText(JAVA_FIXTURE_SOURCE)
        return MinecraftTestProcess.start(
            command = listOf(
                environment.javaExecutable.toString(),
                source.toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "minecraft-test-process-fixture-log",
        )
    }

    private companion object {
        const val STARTED = "fixture-started"
        const val EXIT = "fixture-exit"
        val TIMEOUT = 10.seconds
        val JAVA_FIXTURE_SOURCE =
            """
            import java.io.BufferedReader;
            import java.io.BufferedWriter;
            import java.io.InputStreamReader;
            import java.io.OutputStreamWriter;

            class Fixture {
                public static void main(String[] args) throws Exception {
                    var input = new BufferedReader(
                        new InputStreamReader(System.in)
                    );
                    var output = new BufferedWriter(
                        new OutputStreamWriter(System.out)
                    );
                    output.write("fixture-started\n");
                    output.flush();
                    String command;
                    while ((command = input.readLine()) != null) {
                        if (command.equals("fixture-exit")) return;
                        output.write(command);
                        output.write("\nack:");
                        output.write(command);
                        output.write("\n");
                        output.flush();
                    }
                }
            }
            """.trimIndent() + "\n"
    }
}
