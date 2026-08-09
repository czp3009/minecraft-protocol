@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.minecraft.world.io

import kotlinx.cinterop.*
import okio.*
import platform.windows.*

internal actual fun FileSystem.openTruncatedReadWrite(
    path: Path,
): FileHandle {
    /*
     * Okio 3.18.1's MinGW WindowsFileHandle treats SetFilePointer returning
     * zero as a failure. Zero is the successful position when truncating to
     * the beginning of a file, so resize(0) throws with a stale Win32 error.
     * Truncate through Okio's streaming path before opening the random-access
     * handle. JVM, Node, and POSIX retain the ordinary single-handle resize.
     */
    sink(path).use {}
    return openReadWrite(path, mustExist = true)
}

internal actual fun FileSystem.openLiveReadOnly(path: Path): FileHandle {
    if (this !== FileSystem.SYSTEM) return openReadOnly(path)
    val handle = CreateFileW(
        lpFileName = path.toString(),
        dwDesiredAccess = GENERIC_READ,
        dwShareMode = (FILE_SHARE_READ or
                FILE_SHARE_WRITE or
                FILE_SHARE_DELETE).toUInt(),
        lpSecurityAttributes = null,
        dwCreationDisposition = OPEN_EXISTING.toUInt(),
        dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
        hTemplateFile = null,
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        throw windowsLiveReadFailure("open", path, GetLastError())
    }
    return MingwLiveReadOnlyFileHandle(path, handle)
}

internal actual fun syncSystemFilePath(path: Path) = Unit

private class MingwLiveReadOnlyFileHandle(
    private val path: Path,
    private val handle: COpaquePointer,
) : FileHandle(readWrite = false) {
    override fun protectedRead(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Int {
        if (byteCount == 0) return 0
        val read = array.usePinned { pinned ->
            memScoped {
                val overlapped = alloc<OVERLAPPED>()
                overlapped.Offset = fileOffset.toUInt()
                overlapped.OffsetHigh = (fileOffset ushr 32).toUInt()
                val result = ReadFile(
                    hFile = handle,
                    lpBuffer = pinned.addressOf(arrayOffset),
                    nNumberOfBytesToRead = byteCount.toUInt(),
                    lpNumberOfBytesRead = null,
                    lpOverlapped = overlapped.ptr,
                )
                if (result == 0) {
                    val error = GetLastError()
                    if (error == ERROR_HANDLE_EOF.toUInt()) {
                        return@memScoped 0
                    }
                    throw windowsLiveReadFailure("read", path, error)
                }
                overlapped.InternalHigh.toInt()
            }
        }
        return if (read == 0) -1 else read
    }

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Nothing = readOnlyOperation("write")

    override fun protectedFlush(): Nothing = readOnlyOperation("flush")

    override fun protectedResize(size: Long): Nothing =
        readOnlyOperation("resize")

    override fun protectedSize(): Long = memScoped {
        val size = alloc<LARGE_INTEGER>()
        if (GetFileSizeEx(handle, size.ptr) == 0) {
            throw windowsLiveReadFailure("inspect", path, GetLastError())
        }
        (size.HighPart.toLong() shl 32) or
                (size.LowPart.toLong() and 0xffffffffL)
    }

    override fun protectedClose() {
        if (CloseHandle(handle) == 0) {
            throw windowsLiveReadFailure("close", path, GetLastError())
        }
    }

    private fun readOnlyOperation(operation: String): Nothing =
        throw IllegalStateException(
            "Cannot $operation live read-only file $path",
        )
}

private fun windowsLiveReadFailure(
    operation: String,
    path: Path,
    error: UInt,
): IOException = WorldIOException(
    "Could not $operation live read-only file $path (Win32 error $error)",
)
