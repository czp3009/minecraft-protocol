@file:OptIn(ExperimentalForeignApi::class)

package com.hiczp.minecraft.world.io

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path
import platform.windows.*

internal actual fun acquireWorldDirectoryLock(
    path: Path,
): WorldDirectoryLock {
    val opened = openWindowsWorldLock(path, create = true)
    val handle = opened.handle
        ?: throw windowsLockIoFailure("open", path, opened.error)

    try {
        /*
         * OpenJDK deliberately gives CreateFileW read/write/delete sharing,
         * so a competing writer handle normally opens successfully. The
         * existing LockFileEx range is mandatory, however: WriteFile can fail
         * here before the non-blocking lock attempt. ERROR_LOCK_VIOLATION is
         * therefore exposed as the same public contention type as a failed
         * non-blocking lock attempt.
         */
        writeWorldLockMarker(handle, path)
        forceWorldLock(handle, path)
        val windowsFileKey = tryAcquireWindowsFileLock(handle, path)
            ?: throw worldAlreadyLockedException(
                absoluteWorldLockPath(path),
            )
        return MingwWorldDirectoryLock(handle, path, windowsFileKey)
    } catch (failure: Throwable) {
        // Cleanup is required for every failed acquisition; rethrow the
        // original failure unchanged after closing the native handle.
        closeAllPreserving(
            failure,
            { closeWindowsFile(handle, path) },
        )
        throw failure
    }
}

internal actual fun isWorldDirectoryLocked(path: Path): Boolean {
    val opened = openWindowsWorldLock(path, create = false)
    val handle = opened.handle ?: return when (opened.error) {
        ERROR_ACCESS_DENIED.toUInt() -> true
        ERROR_FILE_NOT_FOUND.toUInt(),
        ERROR_PATH_NOT_FOUND.toUInt(),
            -> false

        else -> throw windowsLockIoFailure("open", path, opened.error)
    }

    var acquiredKey: WindowsFileKey? = null
    var failure: Throwable? = null
    try {
        acquiredKey = tryAcquireWindowsFileLock(handle, path)
        return acquiredKey == null
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        closeAllPreserving(
            failure,
            {
                if (acquiredKey != null) {
                    unlockWindowsFile(handle, path)
                }
            },
            { closeWindowsFile(handle, path) },
            { acquiredKey?.let(::removeInProcessLock) },
        )
    }
}

private class MingwWorldDirectoryLock(
    handle: COpaquePointer,
    private val path: Path,
    private val windowsFileKey: WindowsFileKey,
) : WorldDirectoryLock {
    private var handle: COpaquePointer? = handle

    override val isValid: Boolean
        get() = handle != null

    override fun close() {
        val openHandle = handle ?: return
        handle = null
        closeAllPreserving(
            null,
            { unlockWindowsFile(openHandle, path) },
            { closeWindowsFile(openHandle, path) },
            { removeInProcessLock(windowsFileKey) },
        )
    }
}

private fun openWindowsWorldLock(
    path: Path,
    create: Boolean,
): WindowsOpenResult {
    val handle = CreateFileW(
        lpFileName = path.toString(),
        dwDesiredAccess = GENERIC_WRITE.toUInt(),
        dwShareMode = (FILE_SHARE_READ or
                FILE_SHARE_WRITE or
                FILE_SHARE_DELETE).toUInt(),
        lpSecurityAttributes = null,
        dwCreationDisposition = (if (create) OPEN_ALWAYS else OPEN_EXISTING).toUInt(),
        dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
        hTemplateFile = null,
    )
    return if (handle == null || handle == INVALID_HANDLE_VALUE) {
        WindowsOpenResult(error = GetLastError())
    } else {
        WindowsOpenResult(handle = handle)
    }
}

private fun writeWorldLockMarker(handle: COpaquePointer, path: Path) {
    WORLD_LOCK_MARKER.usePinned { marker ->
        memScoped {
            val written = alloc<DWORDVar>()
            if (
                WriteFile(
                    hFile = handle,
                    lpBuffer = marker.addressOf(0),
                    nNumberOfBytesToWrite = WORLD_LOCK_MARKER.size.toUInt(),
                    lpNumberOfBytesWritten = written.ptr,
                    lpOverlapped = null,
                ) == 0
            ) {
                val error = GetLastError()
                if (error == ERROR_LOCK_VIOLATION.toUInt()) {
                    throw worldAlreadyLockedException(
                        absoluteWorldLockPath(path),
                    )
                }
                throw windowsLockIoFailure(
                    "write marker to",
                    path,
                    error,
                )
            }
        }
    }
}

private fun forceWorldLock(handle: COpaquePointer, path: Path) {
    if (FlushFileBuffers(handle) != 0) return
    val error = GetLastError()
    // OpenJDK's FileDispatcherImpl.force0 ignores this Win32 result.
    if (error != ERROR_ACCESS_DENIED.toUInt()) {
        throw windowsLockIoFailure("durably sync", path, error)
    }
}

private fun tryAcquireWindowsFileLock(
    handle: COpaquePointer,
    path: Path,
): WindowsFileKey? {
    // Keep the same in-process overlap contract as Java even if Win32's
    // response varies with handle sharing and filesystem implementation.
    val windowsFileKey = windowsFileKey(handle, path)
    return withInProcessLockRegistry {
        if (!IN_PROCESS_LOCK_KEYS.add(windowsFileKey)) {
            return@withInProcessLockRegistry null
        }
        when (val lockError = setWindowsFileLock(handle, lock = true)) {
            null -> windowsFileKey
            ERROR_LOCK_VIOLATION.toUInt() -> {
                IN_PROCESS_LOCK_KEYS.remove(windowsFileKey)
                null
            }

            else -> {
                IN_PROCESS_LOCK_KEYS.remove(windowsFileKey)
                throw windowsLockIoFailure("lock", path, lockError)
            }
        }
    }
}

private fun unlockWindowsFile(
    handle: COpaquePointer,
    path: Path,
) {
    val unlockError = setWindowsFileLock(handle, lock = false)
    if (
        unlockError != null &&
        unlockError != ERROR_NOT_LOCKED.toUInt()
    ) {
        throw windowsLockIoFailure("unlock", path, unlockError)
    }
}

private fun setWindowsFileLock(
    handle: COpaquePointer,
    lock: Boolean,
): UInt? = memScoped {
    val overlapped = alloc<OVERLAPPED>()
    overlapped.Internal = 0u
    overlapped.InternalHigh = 0u
    overlapped.Offset = 0u
    overlapped.OffsetHigh = 0u
    overlapped.hEvent = null

    val result = if (lock) {
        // OpenJDK maps FileChannel.tryLock(0, Long.MAX_VALUE, false) to this
        // two-word Win32 range and requests an immediate exclusive result.
        LockFileEx(
            hFile = handle,
            dwFlags = (LOCKFILE_FAIL_IMMEDIATELY or
                    LOCKFILE_EXCLUSIVE_LOCK).toUInt(),
            dwReserved = 0u,
            nNumberOfBytesToLockLow = UInt.MAX_VALUE,
            nNumberOfBytesToLockHigh = Int.MAX_VALUE.toUInt(),
            lpOverlapped = overlapped.ptr,
        )
    } else {
        UnlockFileEx(
            hFile = handle,
            dwReserved = 0u,
            nNumberOfBytesToUnlockLow = UInt.MAX_VALUE,
            nNumberOfBytesToUnlockHigh = Int.MAX_VALUE.toUInt(),
            lpOverlapped = overlapped.ptr,
        )
    }
    if (result != 0) return@memScoped null
    var error = GetLastError()
    // Network filesystems may complete LockFileEx asynchronously even though
    // the public operation is synchronous; wait for that one OS result here.
    if (error == ERROR_IO_PENDING.toUInt()) {
        val transferred = alloc<DWORDVar>()
        if (
            GetOverlappedResult(
                handle,
                overlapped.ptr,
                transferred.ptr,
                TRUE,
            ) != 0
        ) {
            return@memScoped null
        }
        error = GetLastError()
    }
    error
}

private fun windowsFileKey(
    handle: COpaquePointer,
    path: Path,
): WindowsFileKey = memScoped {
    val information = alloc<BY_HANDLE_FILE_INFORMATION>()
    if (GetFileInformationByHandle(handle, information.ptr) == 0) {
        throw windowsLockIoFailure("inspect", path, GetLastError())
    }
    WindowsFileKey(
        volumeSerialNumber = information.dwVolumeSerialNumber,
        fileIndexHigh = information.nFileIndexHigh,
        fileIndexLow = information.nFileIndexLow,
    )
}

private fun closeWindowsFile(handle: COpaquePointer, path: Path) {
    if (CloseHandle(handle) == 0) {
        throw windowsLockIoFailure("close lock file", path, GetLastError())
    }
}

private inline fun <T> withInProcessLockRegistry(block: () -> T): T {
    EnterCriticalSection(IN_PROCESS_LOCK_CRITICAL_SECTION.ptr)
    try {
        return block()
    } finally {
        LeaveCriticalSection(IN_PROCESS_LOCK_CRITICAL_SECTION.ptr)
    }
}

private fun removeInProcessLock(windowsFileKey: WindowsFileKey) {
    withInProcessLockRegistry {
        IN_PROCESS_LOCK_KEYS.remove(windowsFileKey)
    }
}

private fun absoluteWorldLockPath(path: Path): String =
    if (path.isAbsolute) path.toString()
    else FileSystem.SYSTEM.canonicalize(path).toString()

private fun windowsLockIoFailure(
    operation: String,
    path: Path,
    error: UInt,
): WorldIOException = WorldIOException(
    "Could not $operation world lock $path (Win32 error $error)",
)

private data class WindowsOpenResult(
    val handle: COpaquePointer? = null,
    val error: UInt = ERROR_SUCCESS.toUInt(),
)

private data class WindowsFileKey(
    val volumeSerialNumber: UInt,
    val fileIndexHigh: UInt,
    val fileIndexLow: UInt,
)

private val WORLD_LOCK_MARKER = "☃".encodeToByteArray()
private val IN_PROCESS_LOCK_KEYS = mutableSetOf<WindowsFileKey>()

private val IN_PROCESS_LOCK_CRITICAL_SECTION =
    nativeHeap.alloc<CRITICAL_SECTION>().also { criticalSection ->
        InitializeCriticalSection(criticalSection.ptr)
    }
