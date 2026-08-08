package com.hiczp.minecraft.world.io

import okio.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
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
         * before tryLock is reached. POSIX locks are advisory, so contention
         * normally reaches tryLock and its null result instead. Do not convert
         * the earlier Windows marker-write IOException into that lock result.
         */
        channel.write(WORLD_LOCK_MARKER.duplicate())
        channel.force(true)
        val lock = channel.tryLock() ?: throw worldAlreadyLockedException(
            nioPath.toAbsolutePath().toString(),
        )
        return JavaNioWorldDirectoryLock(channel, lock)
    } catch (failure: IOException) {
        try {
            channel.close()
        } catch (closeFailure: IOException) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
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
