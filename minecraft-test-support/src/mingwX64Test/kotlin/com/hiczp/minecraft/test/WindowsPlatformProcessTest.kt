package com.hiczp.minecraft.test

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WindowsPlatformProcessTest {
    @Test
    fun parentExitDoesNotWaitForAChildThatInheritedItsOutputPipe() = runTest(
        timeout = 30.seconds,
    ) {
        val environment = MinecraftTestEnvironment.forModule(
            moduleName = "minecraft-test-support",
            minecraftVersion = "test-version",
        )
        val workingDirectory = environment.freshWorkDirectory(
            "windows-inherited-output-fixture",
        )
        val childSource = Path(workingDirectory, "PipeChild.java")
        val parentSource = Path(workingDirectory, "PipeParent.java")
        childSource.atomicWriteText(CHILD_SOURCE)
        parentSource.atomicWriteText(PARENT_SOURCE)

        MinecraftTestProcess.start(
            command = listOf(
                environment.javaExecutable.toString(),
                parentSource.toString(),
                childSource.toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "windows-inherited-output-fixture",
        ).use { process ->
            process.waitForLog(PARENT_READY, 10.seconds)
            assertNull(process.awaitExitWithin(100.milliseconds))
            process.sendLine(SPAWN_CHILD_AND_EXIT)

            assertEquals(0, process.awaitExitWithin(10.seconds))
        }
    }

    private companion object {
        const val PARENT_READY = "parent-ready"
        const val SPAWN_CHILD_AND_EXIT = "spawn-child-and-exit"
        val CHILD_SOURCE =
            """
            class PipeChild {
                public static void main(String[] args) throws Exception {
                    System.in.read();
                }
            }
            """.trimIndent() + "\n"
        val PARENT_SOURCE =
            """
            import java.io.BufferedReader;
            import java.io.InputStreamReader;
            import java.nio.file.Path;

            class PipeParent {
                public static void main(String[] args) throws Exception {
                    var input = new BufferedReader(
                        new InputStreamReader(System.in)
                    );
                    System.out.println("parent-ready");
                    if (!"spawn-child-and-exit".equals(input.readLine())) {
                        throw new IllegalStateException("unexpected command");
                    }
                    var java = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        "java"
                    );
                    new ProcessBuilder(java.toString(), args[0])
                        .inheritIO()
                        .start();
                }
            }
            """.trimIndent() + "\n"
    }
}
