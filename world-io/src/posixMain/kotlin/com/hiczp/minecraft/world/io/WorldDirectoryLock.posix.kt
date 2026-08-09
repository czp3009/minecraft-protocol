package com.hiczp.minecraft.world.io

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.*

internal actual fun acquireWorldDirectoryLock(
    path: Path,
): WorldDirectoryLock {
    val descriptor = openPosixWorldLock(path, create = true)
    if (descriptor == -1) {
        throw posixLockIoFailure("open", path, errno)
    }

    try {
        /*
         * This is the native form of the official FileChannel sequence. A
         * POSIX record lock is advisory, so another writer may open and write
         * the marker before F_SETLK reports contention. Keep that order: it is
         * observably different from Windows mandatory byte-range locking.
         */
        writeWorldLockMarker(descriptor, path)
        syncWorldLock(descriptor, path)
        val key = tryAcquirePosixFileLock(descriptor, path)
            ?: throw worldAlreadyLockedException(
                absoluteWorldLockPath(path),
            )
        return PosixWorldDirectoryLock(descriptor, path, key)
    } catch (failure: Throwable) {
        // Cleanup is required for every failed acquisition; rethrow the
        // original failure unchanged after closing the descriptor.
        closeAllPreserving(
            failure,
            { closePosixFile(descriptor, path) },
        )
        throw failure
    }
}

internal actual fun isWorldDirectoryLocked(path: Path): Boolean {
    val descriptor = openPosixWorldLock(path, create = false)
    if (descriptor == -1) {
        return when (val openError = errno) {
            EACCES -> true
            ENOENT -> false
            else -> throw posixLockIoFailure("open", path, openError)
        }
    }

    var acquiredKey: PosixFileKey? = null
    var failure: Throwable? = null
    try {
        acquiredKey = tryAcquirePosixFileLock(descriptor, path)
        return acquiredKey == null
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        closeAllPreserving(
            failure,
            {
                if (acquiredKey != null) {
                    unlockPosixFile(descriptor, path)
                }
            },
            { closePosixFile(descriptor, path) },
            {
                acquiredKey?.let { key ->
                    removeInProcessLock(path, key)
                }
            },
        )
    }
}

private class PosixWorldDirectoryLock(
    private var descriptor: Int,
    private val path: Path,
    private val key: PosixFileKey,
) : WorldDirectoryLock {
    private var valid = true

    override val isValid: Boolean
        get() = valid

    override fun close() {
        val openDescriptor = descriptor
        if (openDescriptor == CLOSED_DESCRIPTOR) return
        descriptor = CLOSED_DESCRIPTOR

        try {
            if (valid) {
                unlockPosixFile(openDescriptor, path)
                valid = false
            }
        } finally {
            try {
                closePosixFile(openDescriptor, path)
            } finally {
                removeInProcessLock(path, key)
                valid = false
            }
        }
    }
}

private fun openPosixWorldLock(path: Path, create: Boolean): Int {
    var descriptor: Int
    do {
        descriptor = if (create) {
            open(
                path.toString(),
                O_WRONLY or O_CREAT,
                S_IRUSR or S_IWUSR or
                        S_IRGRP or S_IWGRP or
                        S_IROTH or S_IWOTH,
            )
        } else {
            open(path.toString(), O_WRONLY)
        }
        // UnixNativeDispatcher.open uses OpenJDK's RESTARTABLE macro.
    } while (descriptor == -1 && errno == EINTR)
    return descriptor
}

private fun tryAcquirePosixFileLock(
    descriptor: Int,
    path: Path,
): PosixFileKey? {
    // fcntl locks are process-scoped rather than descriptor-scoped. The
    // synchronized device/inode registry reproduces FileChannel's refusal of
    // an overlapping lock held elsewhere in this process.
    val key = posixFileKey(descriptor, path)
    return withInProcessLockRegistry(path) {
        if (!IN_PROCESS_LOCK_KEYS.add(key)) {
            return@withInProcessLockRegistry null
        }
        when (val lockError = setPosixLock(descriptor, F_WRLCK)) {
            0 -> key
            EACCES, EAGAIN -> {
                IN_PROCESS_LOCK_KEYS.remove(key)
                null
            }

            else -> {
                IN_PROCESS_LOCK_KEYS.remove(key)
                throw posixLockIoFailure("lock", path, lockError)
            }
        }
    }
}

private fun unlockPosixFile(
    descriptor: Int,
    path: Path,
) {
    val unlockError = setPosixLock(descriptor, F_UNLCK)
    if (unlockError != 0) {
        throw posixLockIoFailure("unlock", path, unlockError)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun posixFileKey(descriptor: Int, path: Path): PosixFileKey =
    memScoped {
        val metadata = alloc<stat>()
        var result: Int
        do {
            result = fstat(descriptor, metadata.ptr)
            // OpenJDK's native FileKey initialization retries fstat on EINTR.
        } while (result != 0 && errno == EINTR)
        if (result != 0) {
            throw posixLockIoFailure("inspect", path, errno)
        }
        PosixFileKey(
            device = metadata.st_dev.toString(),
            inode = metadata.st_ino.toString(),
        )
    }

@OptIn(ExperimentalForeignApi::class)
private fun setPosixLock(descriptor: Int, type: Int): Int = memScoped {
    val lock = alloc<flock>()
    lock.l_type = type.convert()
    lock.l_whence = SEEK_SET.convert()
    lock.l_start = 0
    // OpenJDK maps FileChannel.tryLock(0, Long.MAX_VALUE, false) to l_len 0.
    lock.l_len = 0
    if (fcntl(descriptor, F_SETLK, lock.ptr) == 0) 0 else errno
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
private fun writeWorldLockMarker(descriptor: Int, path: Path) {
    WORLD_LOCK_MARKER.usePinned { marker ->
        var result: Long
        do {
            /*
             * Convert ssize_t immediately: its width differs across Native
             * targets, while FileChannel.write exposes a stable Int result.
             */
            result = write(
                descriptor,
                marker.addressOf(0),
                WORLD_LOCK_MARKER.size.convert(),
            ).toLong()
        } while (result < 0L && errno == EINTR)
        if (result < 0L) {
            throw posixLockIoFailure("write marker to", path, errno)
        }
    }
}

private fun syncWorldLock(descriptor: Int, path: Path) {
    var result: Int
    do {
        result = fsync(descriptor)
        // FileChannel.force repeats the native operation after EINTR.
    } while (result != 0 && errno == EINTR)
    if (result != 0) {
        throw posixLockIoFailure("durably sync", path, errno)
    }
}

private fun closePosixFile(descriptor: Int, path: Path) {
    if (close(descriptor) != 0) {
        throw posixLockIoFailure("close lock file", path, errno)
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withInProcessLockRegistry(
    path: Path,
    block: () -> T,
): T {
    val lockError = pthread_mutex_lock(IN_PROCESS_LOCK_MUTEX.ptr)
    if (lockError != 0) {
        throw posixLockIoFailure(
            "lock in-process registry for",
            path,
            lockError,
        )
    }
    var failure: Throwable? = null
    try {
        return block()
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        val unlockError = pthread_mutex_unlock(IN_PROCESS_LOCK_MUTEX.ptr)
        if (unlockError != 0) {
            val unlockFailure = posixLockIoFailure(
                "unlock in-process registry for",
                path,
                unlockError,
            )
            failure?.addSuppressed(unlockFailure) ?: throw unlockFailure
        }
    }
}

private fun removeInProcessLock(path: Path, key: PosixFileKey) {
    withInProcessLockRegistry(path) {
        IN_PROCESS_LOCK_KEYS.remove(key)
    }
}

private fun absoluteWorldLockPath(path: Path): String =
    if (path.isAbsolute) {
        path.toString()
    } else {
        (FileSystem.SYSTEM.canonicalize(".".toPath()) / path).toString()
    }

private fun posixLockIoFailure(
    operation: String,
    path: Path,
    error: Int,
): WorldIOException = WorldIOException(
    "Could not $operation world lock $path (errno $error)",
)

private data class PosixFileKey(
    val device: String,
    val inode: String,
)

private const val CLOSED_DESCRIPTOR = -1
private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
private val IN_PROCESS_LOCK_KEYS = mutableSetOf<PosixFileKey>()

@OptIn(ExperimentalForeignApi::class)
private val IN_PROCESS_LOCK_MUTEX =
    nativeHeap.alloc<pthread_mutex_t>().also { mutex ->
        check(pthread_mutex_init(mutex.ptr, null) == 0)
    }
