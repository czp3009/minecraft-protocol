package com.hiczp.minecraft.test

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WindowsPlatformProcessTest {
    @Test
    fun parentExitDoesNotWaitForAChildThatInheritedItsOutputPipe() = runTest {
        val workingDirectory = MinecraftTestSupport.newScratchDirectory()
        val childSource = processFixtureSource("PipeChild.java")
        val parentSource = processFixtureSource("PipeParent.java")

        val process = MinecraftTestProcess.start(
            command = listOf(
                MinecraftTestSupport.layout.javaExecutable.toString(),
                parentSource.toString(),
                childSource.toString(),
            ),
            workingDirectory = workingDirectory,
            threadName = "windows-inherited-output-fixture",
        )
        val resource = MinecraftTestSupport.manageTestResource(
            workingDirectory,
        ) {
            process.close()
            runCatching { process.awaitExit() }
        }
        try {
            process.waitForLog(PARENT_READY, 10.seconds)
            assertNull(process.awaitExitWithin(100.milliseconds))
            process.sendLine(SPAWN_CHILD_AND_EXIT)

            assertEquals(0, process.awaitExitWithin(10.seconds))
        } finally {
            resource.close()
            MinecraftTestSupport.awaitCleanup()
        }
    }

    private companion object {
        const val PARENT_READY = "parent-ready"
        const val SPAWN_CHILD_AND_EXIT = "spawn-child-and-exit"
    }
}
