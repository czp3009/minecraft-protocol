package com.hiczp.minecraft.world.io

import kotlinx.cinterop.*
import okio.Path
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun acquireWorldDirectoryLock(
    path: Path,
): WorldDirectoryLock {
    val descriptor = openWindowsWorldLock(path, create = true)
    if (descriptor == -1) {
        throw windowsLockFailure("open", path, errno)
    }

    var failure: Throwable? = null
    try {
        writeWorldLockMarker(descriptor, path)
        if (_commit(descriptor) != 0) {
            throw windowsLockFailure("durably sync", path, errno)
        }
        val lockError = setWindowsLock(descriptor, WINDOWS_LOCK_EXCLUSIVE)
        if (lockError != 0) {
            if (lockError == EACCES) {
                throw worldAlreadyLockedException(
                    absoluteWorldLockPath(path),
                )
            }
            throw windowsLockFailure("lock", path, lockError)
        }
        return MingwWorldDirectoryLock(descriptor, path)
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        if (failure != null && _close(descriptor) != 0) {
            failure.addSuppressed(
                windowsLockFailure("close lock file", path, errno),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun isWorldDirectoryLocked(path: Path): Boolean {
    val descriptor = openWindowsWorldLock(path, create = false)
    if (descriptor == -1) {
        return when (val openError = errno) {
            ENOENT -> false
            EACCES -> true
            else -> throw windowsLockFailure("open", path, openError)
        }
    }

    var failure: Throwable? = null
    try {
        return when (
            val lockError = setWindowsLock(
                descriptor,
                WINDOWS_LOCK_EXCLUSIVE,
            )
        ) {
            0 -> {
                val unlockError = setWindowsLock(
                    descriptor,
                    WINDOWS_LOCK_UNLOCK,
                )
                if (unlockError != 0) {
                    throw windowsLockFailure("unlock", path, unlockError)
                }
                false
            }

            EACCES -> true
            else -> throw windowsLockFailure(
                "inspect lock for",
                path,
                lockError,
            )
        }
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        if (_close(descriptor) != 0) {
            val closeFailure = windowsLockFailure(
                "close lock file",
                path,
                errno,
            )
            if (failure == null) throw closeFailure
            failure.addSuppressed(closeFailure)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class MingwWorldDirectoryLock(
    descriptor: Int,
    private val path: Path,
) : WorldDirectoryLock {
    private var descriptor = descriptor

    override val isValid: Boolean
        get() = descriptor != CLOSED_DESCRIPTOR

    override fun close() {
        val openDescriptor = descriptor
        if (openDescriptor == CLOSED_DESCRIPTOR) return
        descriptor = CLOSED_DESCRIPTOR

        var failure: Throwable? = null
        val unlockError = setWindowsLock(
            openDescriptor,
            WINDOWS_LOCK_UNLOCK,
        )
        if (unlockError != 0) {
            failure = windowsLockFailure("unlock", path, unlockError)
        }
        if (_close(openDescriptor) != 0) {
            val closeFailure = windowsLockFailure(
                "close lock file",
                path,
                errno,
            )
            val current = failure
            if (current == null) failure = closeFailure
            else current.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun openWindowsWorldLock(path: Path, create: Boolean): Int =
    memScoped {
        val flags = _O_WRONLY or _O_BINARY or
                if (create) _O_CREAT else 0
        _wopen(
            path.toString().wcstr.ptr,
            flags,
            _S_IREAD or _S_IWRITE,
        )
    }

@OptIn(ExperimentalForeignApi::class)
private fun setWindowsLock(descriptor: Int, mode: Int): Int {
    if (_lseek(descriptor, 0, SEEK_SET) == -1) return errno
    return if (_locking(descriptor, mode, WINDOWS_LOCK_LENGTH) == 0) {
        0
    } else {
        errno
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeWorldLockMarker(descriptor: Int, path: Path) {
    if (_lseek(descriptor, 0, SEEK_SET) == -1) {
        throw windowsLockFailure("seek", path, errno)
    }
    WORLD_LOCK_MARKER.usePinned { marker ->
        var written = 0
        while (written < WORLD_LOCK_MARKER.size) {
            val result = _write(
                descriptor,
                marker.addressOf(written),
                (WORLD_LOCK_MARKER.size - written).convert(),
            )
            if (result <= 0) {
                if (errno == EACCES) {
                    throw WorldLockException(WINDOWS_LOCK_VIOLATION_MESSAGE)
                }
                throw windowsLockFailure("write marker to", path, errno)
            }
            written += result
        }
    }
}

private fun windowsLockFailure(
    operation: String,
    path: Path,
    error: Int,
): WorldLockException = WorldLockException(
    "Could not $operation world lock $path (errno $error)",
)

private fun absoluteWorldLockPath(path: Path): String =
    if (path.isAbsolute) path.toString()
    else systemFileSystem.canonicalize(path).toString()

private const val CLOSED_DESCRIPTOR = -1
private const val WINDOWS_LOCK_UNLOCK = 0
private const val WINDOWS_LOCK_EXCLUSIVE = 2
private const val WINDOWS_LOCK_LENGTH = 1
private const val WINDOWS_LOCK_VIOLATION_MESSAGE =
    "The process cannot access the file because another process has locked a portion of the file"
private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
