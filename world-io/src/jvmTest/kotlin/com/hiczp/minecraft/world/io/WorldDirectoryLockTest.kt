package com.hiczp.minecraft.world.io

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.*

class WorldDirectoryLockTest {
    @Test
    fun leaseUsesOfficialMarkerAndRejectsASecondInstance() = runTest {
        val temporaryDirectory = Files.createTempDirectory(
            "world-io-directory-lock-",
        )
        val root = temporaryDirectory.toOkioPath()
        val lockPath = root / "session.lock"
        val original = "old-lock-tail".encodeToByteArray()
        try {
            FileSystem.SYSTEM.write(lockPath) {
                write(original)
            }

            val access = MinecraftWorldAccess.open(root)
            try {
                assertTrue(MinecraftWorldAccess.isLocked(root))
                assertFails {
                    MinecraftWorldAccess.open(root)
                }
            } finally {
                access.close()
            }

            assertFalse(MinecraftWorldAccess.isLocked(root))
            assertTrue(FileSystem.SYSTEM.exists(lockPath))
            val stored = FileSystem.SYSTEM.read(lockPath) {
                readByteArray()
            }
            assertContentEquals(WORLD_LOCK_MARKER, stored.copyOf(3))
            assertContentEquals(
                original.copyOfRange(3, original.size),
                stored.copyOfRange(3, stored.size),
            )
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun leaseInteroperatesWithAnOfficialStyleLockInAnotherProcess() = runTest {
        val temporaryDirectory = Files.createTempDirectory(
            "world-io-process-lock-",
        )
        val root = temporaryDirectory.toOkioPath()
        val process = ProcessBuilder(
            "java",
            "-cp",
            lockHolderClasspath(),
            LOCK_HOLDER_MAIN_CLASS,
            temporaryDirectory.toString(),
        ).redirectErrorStream(true).start()
        try {
            val readiness = process.inputReader().readLine()
            check(readiness == LOCK_HOLDER_READY) {
                "Lock holder failed before readiness: $readiness"
            }
            assertTrue(MinecraftWorldAccess.isLocked(root))
            val expectedMessage = captureOfficialStyleLockFailureMessage(
                temporaryDirectory.resolve("session.lock"),
            )
            val failure = assertFails {
                MinecraftWorldAccess.open(root)
            }
            assertEquals(expectedMessage, failure.message)

            process.outputStream.write(LOCK_HOLDER_RELEASE)
            process.outputStream.flush()
            process.outputStream.close()
            check(process.waitFor() == 0) {
                "Lock holder exited with ${process.exitValue()}"
            }

            val access = MinecraftWorldAccess.open(root)
            access.close()
            assertFalse(MinecraftWorldAccess.isLocked(root))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
            temporaryDirectory.toFile().deleteRecursively()
        }
    }
}

private fun captureOfficialStyleLockFailureMessage(path: Path): String {
    val failure = try {
        FileChannel.open(path, CREATE, WRITE).use { channel ->
            val marker = ByteBuffer.wrap(WORLD_LOCK_MARKER)
            channel.position(0)
            while (marker.hasRemaining()) channel.write(marker)
            channel.force(true)
            val lock = channel.tryLock()
                ?: throw IOException(
                    "${path.toAbsolutePath()}: $OFFICIAL_WORLD_ALREADY_LOCKED_REASON",
                )
            lock.use {
                error("Official-style lock unexpectedly acquired: $path")
            }
        }
        null
    } catch (caught: Throwable) {
        caught
    }
    return checkNotNull(failure?.message) {
        "Official-style lock failure had no message for $path"
    }
}

private fun lockHolderClasspath(): String = listOf(
    WorldLockProcessMain::class.java,
    Unit::class.java,
).map { type ->
    File(type.protectionDomain.codeSource.location.toURI()).absolutePath
}.distinct().joinToString(File.pathSeparator)

private const val LOCK_HOLDER_MAIN_CLASS =
    "com.hiczp.minecraft.world.io.WorldLockProcessMain"
private const val LOCK_HOLDER_READY = "LOCKED"
private const val LOCK_HOLDER_RELEASE = 1
private const val OFFICIAL_WORLD_ALREADY_LOCKED_REASON =
    "already locked (possibly by other Minecraft instance?)"
private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
