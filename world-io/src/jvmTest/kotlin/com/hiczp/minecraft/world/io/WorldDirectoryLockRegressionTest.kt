package com.hiczp.minecraft.world.io

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Kernel-lock regressions with a separate JVM oracle; no Minecraft process or Fixture Host is needed. */
class WorldDirectoryLockRegressionTest {
    @Test
    fun inspectingHeldLockPreservesExternalExclusion() = assertLeaseSurvives { directory ->
        assertTrue(isWorldDirectoryLocked(directory.resolve("session.lock").toOkioPath()))
    }

    @Test
    fun rejectedAcquisitionPreservesExternalExclusion() = assertLeaseSurvives { directory ->
        assertFailsWith<WorldLockException> {
            acquireWorldDirectoryLock(directory.resolve("session.lock").toOkioPath()).use { }
        }
    }

    @Test
    fun inspectingHardLinkPreservesExternalExclusion() = assertLeaseSurvives { directory ->
        val alias = Files.createLink(directory.resolve("alias.lock"), directory.resolve("session.lock"))
        assertTrue(isWorldDirectoryLocked(alias.toOkioPath()))
    }

    @Test
    fun rejectedHardLinkAcquisitionPreservesExternalExclusion() = assertLeaseSurvives { directory ->
        val alias = Files.createLink(directory.resolve("alias.lock"), directory.resolve("session.lock"))
        assertFailsWith<WorldLockException> {
            acquireWorldDirectoryLock(alias.toOkioPath()).use { }
        }
    }

    @Test
    fun closingLeaseAllowsExternalAcquisition() = withWorldDirectory { directory ->
        acquireWorldDirectoryLock(directory.resolve("session.lock").toOkioPath()).use {
            assertExternalLockState(directory, locked = true)
        }
        assertExternalLockState(directory, locked = false)
    }

    @Test
    fun closingOldLeaseAgainPreservesReplacementLease() = withWorldDirectory { directory ->
        val lockPath = directory.resolve("session.lock").toOkioPath()
        acquireWorldDirectoryLock(lockPath).use { original ->
            original.close()
            acquireWorldDirectoryLock(lockPath).use { replacement ->
                original.close()
                assertTrue(replacement.isValid)
                assertExternalLockState(directory, locked = true)
            }
        }
        assertExternalLockState(directory, locked = false)
    }

    private fun assertLeaseSurvives(operation: (Path) -> Unit) = withWorldDirectory { directory ->
        acquireWorldDirectoryLock(directory.resolve("session.lock").toOkioPath()).use { worldDirectoryLock ->
            assertExternalLockState(directory, locked = true)
            operation(directory)
            assertTrue(worldDirectoryLock.isValid)
            // An in-process lock registry can still report a valid lease after POSIX has released the actual lock.
            assertExternalLockState(directory, locked = true)
        }
        assertExternalLockState(directory, locked = false)
    }

    private fun withWorldDirectory(block: (Path) -> Unit) {
        val parent = Files.createDirectories(Path.of("build", "world-lock-regression"))
        val directory = Files.createTempDirectory(parent, "world-")
        try {
            block(directory)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory.toOkioPath(), mustExist = false)
        }
    }

    private fun assertExternalLockState(directory: Path, locked: Boolean) {
        val process = startWorldLockProcess(directory, probeOnly = true)
        try {
            val output = process.inputReader().readText().trim()
            assertEquals(0, process.waitFor(), output)
            assertEquals(if (locked) "LOCKED" else "UNLOCKED", output, "Kernel lock state observed by a separate process")
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}
