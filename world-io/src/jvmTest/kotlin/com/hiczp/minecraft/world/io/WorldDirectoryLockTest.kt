package com.hiczp.minecraft.world.io

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
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
                assertFailsWith<OverlappingFileLockException> {
                    MinecraftWorldAccess.isLocked(root)
                }
                val actual = assertFails { MinecraftWorldAccess.open(root) }
                val expected = officialStyleOverlappingAcquisitionFailure()
                assertEquals(expected::class, actual::class)
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
    fun acquisitionDoesNotRelabelAnOpenFailureAsLockContention() {
        val temporaryDirectory = Files.createTempDirectory(
            "world-io-lock-open-failure-",
        )
        val root = temporaryDirectory.toOkioPath()
        try {
            Files.createDirectory(temporaryDirectory.resolve("session.lock"))

            val failure = assertFailsWith<IOException> {
                MinecraftWorldAccess.open(root)
            }
            assertFalse(failure is WorldLockException)
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
            assertFailsWith<IOException> {
                MinecraftWorldAccess.open(root)
            }

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

private fun officialStyleOverlappingAcquisitionFailure(): Throwable {
    val directory = Files.createTempDirectory(
        "world-io-official-lock-oracle-",
    )
    val lockPath = directory.resolve("session.lock")
    try {
        return FileChannel.open(lockPath, CREATE, WRITE).use { firstChannel ->
            firstChannel.tryLock().use {
                assertFails { acquireOfficialStyleLock(lockPath) }
            }
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private fun acquireOfficialStyleLock(path: Path) {
    val channel = FileChannel.open(path, CREATE, WRITE)
    try {
        channel.write(officialMarkerBuffer())
        channel.force(true)
        channel.tryLock()?.use { return }
        throw WorldLockException(
            "${path.toAbsolutePath()}: $WORLD_LOCK_ALREADY_LOCKED_REASON",
        )
    } catch (failure: IOException) {
        try {
            channel.close()
        } catch (closeFailure: IOException) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

private fun officialMarkerBuffer(): ByteBuffer =
    ByteBuffer.allocateDirect(WORLD_LOCK_MARKER.size).apply {
        put(WORLD_LOCK_MARKER)
        flip()
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
private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
