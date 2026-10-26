package com.hiczp.minecraft.world.io

import okio.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path as NioPath

internal actual fun acquireWorldDirectoryLock(path: Path): WorldDirectoryLock = synchronized(IN_PROCESS_LOCK_KEYS) {
    val nioPath = NioPath.of(path.toString())
    if (existingFileKey(nioPath) in IN_PROCESS_LOCK_KEYS) {
        throw worldAlreadyLockedException(nioPath.toAbsolutePath().toString())
    }
    val fileChannel = FileChannel.open(nioPath, CREATE, WRITE)
    try {
        val fileKey = checkNotNull(existingFileKey(nioPath)) { "World lock disappeared after opening: $path" }
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
        fileChannel.write(WORLD_LOCK_MARKER.duplicate())
        fileChannel.force(true)
        val fileLock = try {
            fileChannel.tryLock()
        } catch (failure: OverlappingFileLockException) {
            throw worldAlreadyLockedException(
                nioPath.toAbsolutePath().toString(),
                failure,
            )
        } ?: throw worldAlreadyLockedException(
            nioPath.toAbsolutePath().toString(),
        )
        IN_PROCESS_LOCK_KEYS.add(fileKey)
        JavaNioWorldDirectoryLock(fileChannel, fileLock, fileKey)
    } catch (failure: Throwable) {
        // A mandatory Windows byte lock can reject the marker write before
        // tryLock. Re-probe before classifying that otherwise ordinary I/O
        // error, so unrelated failures retain their public Okio I/O type.
        val mapped = if (
            failure !is IOException || failure is WorldLockException ||
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
            fileChannel.close()
        } catch (closeFailure: IOException) {
            mapped.addSuppressed(closeFailure)
        }
        throw mapped
    }
}

internal actual fun isWorldDirectoryLocked(path: Path): Boolean = synchronized(IN_PROCESS_LOCK_KEYS) {
    try {
        val nioPath = NioPath.of(path.toString())
        if (existingFileKey(nioPath) in IN_PROCESS_LOCK_KEYS) return@synchronized true
        FileChannel.open(nioPath, WRITE).use { fileChannel ->
            fileChannel.tryLock().use { fileLock ->
                fileLock == null
            }
        }
    } catch (_: AccessDeniedException) {
        true
    } catch (_: NoSuchFileException) {
        false
    } catch (_: OverlappingFileLockException) {
        true
    }
}

private fun existingFileKey(path: NioPath): Any? = try {
    Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() ?: path.toRealPath()
} catch (_: NoSuchFileException) {
    null
}

private fun worldLockAppearsHeld(path: Path): Boolean = try {
    isWorldDirectoryLocked(path)
} catch (_: IOException) {
    false
}

private class JavaNioWorldDirectoryLock(
    private val fileChannel: FileChannel,
    private val fileLock: FileLock,
    private val fileKey: Any,
) : WorldDirectoryLock {
    override val isValid: Boolean
        get() = fileLock.isValid

    override fun close(): Unit = synchronized(IN_PROCESS_LOCK_KEYS) {
        if (!fileChannel.isOpen) return@synchronized
        closeAllPreserving(
            null,
            { if (fileLock.isValid) fileLock.release() },
            { if (fileChannel.isOpen) fileChannel.close() },
            { IN_PROCESS_LOCK_KEYS.remove(fileKey) },
        )
    }
}

// DirectoryLock's second-channel probe is unsafe for a lease held by this process on POSIX: closing any descriptor
// for that inode releases all process-associated locks. Check inode identity before opening, including path aliases,
// and serialize acquisition, probing and release so another library operation cannot open it during acquisition.
private val IN_PROCESS_LOCK_KEYS = mutableSetOf<Any>()

private val WORLD_LOCK_MARKER = "☃".encodeToByteArray().let { marker ->
    ByteBuffer.allocateDirect(marker.size).apply {
        put(marker)
        flip()
    }
}
