package com.hiczp.minecraft.world.io

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun acquireWorldDirectoryLock(
    path: Path,
): WorldDirectoryLock {
    val absolutePath = absoluteWorldLockPath(path)
    val canonicalPath = canonicalLockPath(path)
    return withInProcessLockRegistry(path) {
        if (canonicalPath in IN_PROCESS_LOCK_PATHS) {
            throw worldAlreadyLockedException(absolutePath)
        }
        val descriptor = open(
            path.toString(),
            O_WRONLY or O_CREAT,
            S_IRUSR or S_IWUSR or
                    S_IRGRP or S_IWGRP or
                    S_IROTH or S_IWOTH,
        )
        if (descriptor == -1) {
            throw posixLockFailure("open", path, errno)
        }

        var lockAcquired = false
        try {
            writeWorldLockMarker(path)
            if (fsync(descriptor) != 0) {
                throw posixLockFailure("durably sync", path, errno)
            }
            val lockError = setPosixLock(descriptor, F_WRLCK)
            if (lockError != 0) {
                if (lockError == EACCES || lockError == EAGAIN) {
                    throw worldAlreadyLockedException(absolutePath)
                }
                throw posixLockFailure("lock", path, lockError)
            }
            lockAcquired = true
            check(IN_PROCESS_LOCK_PATHS.add(canonicalPath))
            return@withInProcessLockRegistry PosixWorldDirectoryLock(
                descriptor,
                path,
                canonicalPath,
            )
        } catch (failure: Throwable) {
            if (lockAcquired) {
                val unlockError = setPosixLock(descriptor, F_UNLCK)
                if (unlockError != 0) {
                    failure.addSuppressed(
                        posixLockFailure("unlock", path, unlockError),
                    )
                }
            }
            if (close(descriptor) != 0) {
                failure.addSuppressed(
                    posixLockFailure("close lock file", path, errno),
                )
            }
            throw failure
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun isWorldDirectoryLocked(path: Path): Boolean {
    val parent = path.parent
        ?: throw WorldLockException("World lock has no parent: $path")
    if (FileSystem.SYSTEM.metadataOrNull(parent)?.isDirectory != true) {
        return false
    }
    val canonicalPath = canonicalLockPath(path)
    return withInProcessLockRegistry(path) {
        if (canonicalPath in IN_PROCESS_LOCK_PATHS) {
            return@withInProcessLockRegistry true
        }
        val descriptor = open(path.toString(), O_WRONLY)
        if (descriptor == -1) {
            return@withInProcessLockRegistry when (val openError = errno) {
                ENOENT -> false
                EACCES -> true
                else -> throw posixLockFailure("open", path, openError)
            }
        }

        var failure: Throwable? = null
        try {
            return@withInProcessLockRegistry when (
                val lockError = setPosixLock(descriptor, F_WRLCK)
            ) {
                0 -> {
                    val unlockError = setPosixLock(descriptor, F_UNLCK)
                    if (unlockError != 0) {
                        throw posixLockFailure(
                            "unlock",
                            path,
                            unlockError,
                        )
                    }
                    false
                }

                EACCES, EAGAIN -> true
                else -> throw posixLockFailure(
                    "inspect lock for",
                    path,
                    lockError,
                )
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (close(descriptor) != 0) {
                val closeFailure = posixLockFailure(
                    "close lock file",
                    path,
                    errno,
                )
                val current = failure
                if (current == null) throw closeFailure
                current.addSuppressed(closeFailure)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PosixWorldDirectoryLock(
    descriptor: Int,
    private val path: Path,
    private val canonicalPath: String,
) : WorldDirectoryLock {
    private var descriptor = descriptor

    override val isValid: Boolean
        get() = descriptor != CLOSED_DESCRIPTOR

    override fun close() {
        val openDescriptor = descriptor
        if (openDescriptor == CLOSED_DESCRIPTOR) return
        descriptor = CLOSED_DESCRIPTOR

        withInProcessLockRegistry(path) {
            var failure: Throwable? = null
            val unlockError = setPosixLock(openDescriptor, F_UNLCK)
            if (unlockError != 0) {
                failure = posixLockFailure("unlock", path, unlockError)
            }
            if (close(openDescriptor) != 0) {
                val closeFailure = posixLockFailure(
                    "close lock file",
                    path,
                    errno,
                )
                val current = failure
                if (current == null) failure = closeFailure
                else current.addSuppressed(closeFailure)
            }
            IN_PROCESS_LOCK_PATHS.remove(canonicalPath)
            failure?.let { throw it }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun setPosixLock(descriptor: Int, type: Int): Int = memScoped {
    val lock = alloc<flock>()
    lock.l_type = type.convert()
    lock.l_whence = SEEK_SET.convert()
    lock.l_start = 0
    lock.l_len = 0
    if (fcntl(descriptor, F_SETLK, lock.ptr) == 0) 0 else errno
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withInProcessLockRegistry(
    path: Path,
    block: () -> T,
): T {
    val lockError = pthread_mutex_lock(IN_PROCESS_LOCK_MUTEX.ptr)
    if (lockError != 0) {
        throw posixLockFailure(
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
            val unlockFailure = posixLockFailure(
                "unlock in-process registry for",
                path,
                unlockError,
            )
            val current = failure
            if (current == null) throw unlockFailure
            current.addSuppressed(unlockFailure)
        }
    }
}

private fun canonicalLockPath(path: Path): String {
    val parent = path.parent
        ?: throw WorldLockException("World lock has no parent: $path")
    return (FileSystem.SYSTEM.canonicalize(parent) / path.name).toString()
}

private fun absoluteWorldLockPath(path: Path): String =
    if (path.isAbsolute) {
        path.toString()
    } else {
        (FileSystem.SYSTEM.canonicalize(".".toPath()) / path).toString()
    }

private fun writeWorldLockMarker(path: Path) {
    FileSystem.SYSTEM.openReadWrite(path).use { handle ->
        handle.write(
            fileOffset = 0L,
            array = WORLD_LOCK_MARKER,
            arrayOffset = 0,
            byteCount = WORLD_LOCK_MARKER.size,
        )
        handle.flush()
    }
}

private fun posixLockFailure(
    operation: String,
    path: Path,
    error: Int,
): WorldLockException = WorldLockException(
    "Could not $operation world lock $path (errno $error)",
)

private const val CLOSED_DESCRIPTOR = -1
private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
private val IN_PROCESS_LOCK_PATHS = mutableSetOf<String>()

@OptIn(ExperimentalForeignApi::class)
private val IN_PROCESS_LOCK_MUTEX =
    nativeHeap.alloc<pthread_mutex_t>().also { mutex ->
        check(pthread_mutex_init(mutex.ptr, null) == 0)
    }
