package com.hiczp.minecraft.world.io

import okio.Path
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
    var failure: Throwable? = null
    try {
        val marker = ByteBuffer.wrap(WORLD_LOCK_MARKER)
        channel.position(0L)
        while (marker.hasRemaining()) channel.write(marker)
        channel.force(true)
        val lock = try {
            channel.tryLock()
        } catch (overlap: OverlappingFileLockException) {
            throw worldAlreadyLockedException(
                nioPath.toAbsolutePath().toString(),
                overlap,
            )
        } ?: throw worldAlreadyLockedException(
            nioPath.toAbsolutePath().toString(),
        )
        return JavaNioWorldDirectoryLock(channel, lock)
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        if (failure != null) {
            try {
                channel.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
        }
    }
}

internal actual fun isWorldDirectoryLocked(path: Path): Boolean = try {
    FileChannel.open(NioPath.of(path.toString()), WRITE).use { channel ->
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            return true
        }
        if (lock == null) {
            true
        } else {
            lock.release()
            false
        }
    }
} catch (_: AccessDeniedException) {
    true
} catch (_: NoSuchFileException) {
    false
}

private class JavaNioWorldDirectoryLock(
    private val channel: FileChannel,
    private val lock: FileLock,
) : WorldDirectoryLock {
    override val isValid: Boolean
        get() = lock.isValid

    override fun close() {
        var failure: Throwable? = null
        try {
            if (lock.isValid) lock.release()
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            try {
                if (channel.isOpen) channel.close()
            } catch (closeFailure: Throwable) {
                val current = failure
                if (current == null) {
                    failure = closeFailure
                } else {
                    current.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}

private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
