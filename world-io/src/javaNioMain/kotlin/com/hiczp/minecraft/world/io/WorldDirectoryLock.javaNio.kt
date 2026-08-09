package com.hiczp.minecraft.world.io

import okio.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.Path as NioPath

internal actual fun acquireWorldDirectoryLock(path: Path): WorldDirectoryLock {
    val nioPath = NioPath.of(path.toString())
    val channel = FileChannel.open(nioPath, CREATE, WRITE)
    try {
        /*
         * This intentionally mirrors the official DirectoryLock.create.
         * OpenJDK allows shared writer handles on Windows, so a competing
         * FileChannel.open normally succeeds there. LockFileEx byte locks are
         * mandatory, however, and WriteFile can reject this marker write
         * before tryLock is reached. A successful lock probe identifies that
         * platform-specific write failure as the same public contention case.
         * POSIX locks are advisory, so contention normally reaches tryLock and
         * its null result instead.
         */
        channel.write(WORLD_LOCK_MARKER.duplicate())
        channel.force(true)
        val lock = try {
            channel.tryLock()
        } catch (failure: OverlappingFileLockException) {
            throw worldAlreadyLockedException(
                nioPath.toAbsolutePath().toString(),
                failure,
            )
        } ?: throw worldAlreadyLockedException(
            nioPath.toAbsolutePath().toString(),
        )
        return JavaNioWorldDirectoryLock(channel, lock)
    } catch (failure: IOException) {
        // A mandatory Windows byte lock can reject the marker write before
        // tryLock. Re-probe before classifying that otherwise ordinary I/O
        // error, so unrelated failures retain their public Okio I/O type.
        val mapped = if (
            failure is WorldLockException ||
            !worldLockAppearsHeld(path)
        ) {
            failure
        } else {
            worldAlreadyLockedException(
                nioPath.toAbsolutePath().toString(),
                failure,
            )
        }
        try {
            channel.close()
        } catch (closeFailure: IOException) {
            mapped.addSuppressed(closeFailure)
        }
        throw mapped
    }
}

internal actual fun isWorldDirectoryLocked(path: Path): Boolean = try {
    FileChannel.open(NioPath.of(path.toString()), WRITE).use { channel ->
        channel.tryLock().use { lock ->
            lock == null
        }
    }
} catch (_: AccessDeniedException) {
    true
} catch (_: NoSuchFileException) {
    false
} catch (_: OverlappingFileLockException) {
    true
}

private fun worldLockAppearsHeld(path: Path): Boolean = try {
    isWorldDirectoryLocked(path)
} catch (_: IOException) {
    false
}

private class JavaNioWorldDirectoryLock(
    private val channel: FileChannel,
    private val lock: FileLock,
) : WorldDirectoryLock {
    override val isValid: Boolean
        get() = lock.isValid

    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } finally {
            if (channel.isOpen) channel.close()
        }
    }
}

private val WORLD_LOCK_MARKER = "☃".encodeToByteArray().let { marker ->
    ByteBuffer.allocateDirect(marker.size).apply {
        put(marker)
        flip()
    }
}
