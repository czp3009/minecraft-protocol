package com.hiczp.minecraft.buildlogic

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.tools.ToolProvider
import kotlin.test.*

class MinecraftProtocolToolSupportTest {
    @Test
    fun deleteTreeDoesNotFollowDirectorySymbolicLinks() {
        val root = Files.createTempDirectory("build-logic-delete-tree-")
        try {
            val sourceFile = root.resolve("source/nested/source.txt")
            val workDirectory = root.resolve("work")
            Files.createDirectories(sourceFile.parent)
            Files.createDirectories(workDirectory)
            Files.writeString(sourceFile, "source")
            if (!createSymbolicLink(
                    workDirectory.resolve("live-link"),
                    root.resolve("source"),
                )
            ) {
                return
            }
            Files.createSymbolicLink(
                workDirectory.resolve("dangling-link"),
                root.resolve("missing"),
            )

            workDirectory.deleteTree()

            assertFalse(
                Files.exists(workDirectory, LinkOption.NOFOLLOW_LINKS),
            )
            assertTrue(Files.isRegularFile(sourceFile))
        } finally {
            root.deleteTree()
        }
    }

    @Test
    fun timedOutProcessTerminatesDescendantsThatInheritedOutput() {
        val root = Files.createTempDirectory("build-logic-process-tree-")
        val pidFile = root.resolve("child.pid")
        try {
            compileToolProcessFixture(root)

            val failure = assertFailsWith<IllegalStateException> {
                runProcess(
                    command = toolProcessCommand(root, pidFile),
                    workingDirectory = root,
                    timeout = Duration.ofSeconds(3),
                )
            }

            assertTrue(failure.message.orEmpty().startsWith("Process timed out:"))
            assertProcessStopped(pidFile)
        } finally {
            forceProcessFromPidFile(pidFile)
            root.deleteTree()
        }
    }

    @Test
    fun interruptedProcessWaitStillTerminatesItsProcessTree() {
        val root = Files.createTempDirectory("build-logic-interrupted-process-")
        val pidFile = root.resolve("child.pid")
        val failure = AtomicReference<Throwable?>()
        compileToolProcessFixture(root)
        val runner = Thread.ofVirtual().start {
            try {
                runProcess(
                    command = toolProcessCommand(root, pidFile),
                    workingDirectory = root,
                )
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        try {
            while (!Files.isRegularFile(pidFile)) {
                check(runner.isAlive) { "Tool process runner exited before its child started" }
                Thread.onSpinWait()
            }
            runner.interrupt()
            runner.join(10_000)

            assertFalse(runner.isAlive)
            assertIs<InterruptedException>(failure.get())
            assertProcessStopped(pidFile)
        } finally {
            runner.interrupt()
            forceProcessFromPidFile(pidFile)
            runner.join(10_000)
            root.deleteTree()
        }
    }

    private fun compileToolProcessFixture(outputDirectory: Path) {
        val source = Path.of(
            checkNotNull(javaClass.getResource("/ToolProcessFixture.java")).toURI(),
        )
        assertEquals(
            0,
            checkNotNull(ToolProvider.getSystemJavaCompiler()).run(
                null,
                null,
                null,
                "-d",
                outputDirectory.toString(),
                source.toString(),
            ),
        )
    }

    private fun toolProcessCommand(
        classDirectory: Path,
        pidFile: Path,
    ): List<String> = listOf(
        "java",
        "-cp",
        classDirectory.toString(),
        "ToolProcessFixture",
        "parent",
        pidFile.toString(),
    )

    private fun assertProcessStopped(pidFile: Path) {
        processPidFiles(pidFile).forEach { current ->
            assertTrue(
                ProcessHandle.of(Files.readString(current).toLong())
                    .map { processHandle -> !processHandle.isAlive }
                    .orElse(true),
            )
        }
    }

    private fun forceProcessFromPidFile(pidFile: Path) {
        processPidFiles(pidFile)
            .filter(Files::isRegularFile)
            .forEach { current ->
                ProcessHandle.of(Files.readString(current).toLong())
                    .filter(ProcessHandle::isAlive)
                    .ifPresent(ProcessHandle::destroyForcibly)
            }
    }

    private fun processPidFiles(childPidFile: Path): List<Path> = listOf(
        childPidFile,
        childPidFile.resolveSibling("parent.pid"),
    )

    private fun createSymbolicLink(link: Path, target: Path): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
