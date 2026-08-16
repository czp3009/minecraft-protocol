@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.minecraft.world.io

import kotlinx.cinterop.*
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.use
import platform.windows.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner

internal actual fun FileSystem.openRandomAccessReadWrite(
    path: Path,
): FileHandle {
    /*
     * Preserve Okio for injected and fake filesystems. Only the MinGW system
     * filesystem is bypassed: Okio 3.18.1 routes it through WindowsFileHandle,
     * whose resize and synchronous ReadFile/WriteFile result handling are
     * incorrect. The defect is in that native system handle, not in Okio's
     * FileSystem abstraction generally.
     */
    if (this !== FileSystem.SYSTEM) return openReadWrite(path)
    return openWindowsFileHandle(
        path = path,
        readWrite = true,
        creationDisposition = OPEN_ALWAYS.toUInt(),
    )
}

internal actual fun FileSystem.createRandomAccessReadWrite(
    path: Path,
): FileHandle {
    if (this !== FileSystem.SYSTEM) {
        return openReadWrite(path, mustCreate = true)
    }
    return openWindowsFileHandle(
        path = path,
        readWrite = true,
        creationDisposition = CREATE_NEW.toUInt(),
    )
}

internal actual fun FileSystem.openTruncatedReadWrite(
    path: Path,
): FileHandle {
    if (this !== FileSystem.SYSTEM) {
        sink(path).use {}
        return openReadWrite(path, mustExist = true)
    }
    return openWindowsFileHandle(
        path = path,
        readWrite = true,
        creationDisposition = CREATE_ALWAYS.toUInt(),
    )
}

internal actual fun FileSystem.openLiveReadOnly(path: Path): FileHandle {
    if (this !== FileSystem.SYSTEM) return openReadOnly(path)
    return openWindowsFileHandle(
        path = path,
        readWrite = false,
        creationDisposition = OPEN_EXISTING.toUInt(),
    )
}

internal actual fun syncSystemFilePath(path: Path) = Unit

/*
 * Okio 3.18.1's MinGW WindowsFileHandle reports valid Win32 results as
 * failures. Its resize treats a zero SetFilePointer result as failure and then
 * formats unchanged ERROR_SUCCESS as "The operation completed successfully."
 * It also omits the documented byte-count pointer for synchronous ReadFile and
 * WriteFile calls and relies on OVERLAPPED.InternalHigh. JVM Okio instead uses
 * synchronized RandomAccessFile operations and cannot enter either Windows-
 * only path. Keep a synchronous Win32 handle here, retain explicit offsets,
 * and consume every operation's documented result directly.
 */
private class MingwSystemFileHandle(
    private val path: Path,
    readWrite: Boolean,
    private val handle: COpaquePointer,
) : FileHandle(readWrite = readWrite) {
    private val synchronization = WindowsHandleSynchronization()

    override fun protectedRead(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Int {
        /*
         * Do not delegate this operation to Okio's WindowsFileHandle. It passes
         * null for lpNumberOfBytesRead on a synchronous handle and then reads
         * OVERLAPPED.InternalHigh. Use the documented byte-count result and
         * continue short reads, matching JVM JvmFileHandle semantics.
         */
        if (byteCount == 0) return 0
        require(fileOffset >= 0L)
        return withCriticalSection {
            array.usePinned { pinned ->
                memScoped {
                    var offset = fileOffset
                    var remaining = byteCount
                    var targetOffset = arrayOffset
                    var totalRead = 0
                    while (remaining > 0) {
                        val overlapped = allocOffset(offset)
                        val bytesRead = alloc<DWORDVar>()
                        if (
                            ReadFile(
                                hFile = handle,
                                lpBuffer = pinned.addressOf(targetOffset),
                                nNumberOfBytesToRead = remaining.toUInt(),
                                lpNumberOfBytesRead = bytesRead.ptr,
                                lpOverlapped = overlapped.ptr,
                            ) == 0
                        ) {
                            val error = GetLastError()
                            if (error == ERROR_HANDLE_EOF.toUInt()) break
                            throw windowsFileFailure("read", path, error)
                        }
                        val read = bytesRead.value.toInt()
                        if (read == 0) break
                        offset += read
                        remaining -= read
                        targetOffset += read
                        totalRead += read
                    }
                    if (totalRead == 0) -1 else totalRead
                }
            }
        }
    }

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ) {
        /*
         * Okio's MinGW implementation has the corresponding write bug: it
         * supplies no lpNumberOfBytesWritten and trusts InternalHigh. Consume
         * the synchronous API's byte count directly and finish short writes,
         * as RandomAccessFile.write does on the JVM path.
         */
        if (byteCount == 0) return
        require(fileOffset >= 0L)
        withCriticalSection {
            array.usePinned { pinned ->
                memScoped {
                    var offset = fileOffset
                    var remaining = byteCount
                    var sourceOffset = arrayOffset
                    while (remaining > 0) {
                        val overlapped = allocOffset(offset)
                        val bytesWritten = alloc<DWORDVar>()
                        if (
                            WriteFile(
                                hFile = handle,
                                lpBuffer = pinned.addressOf(sourceOffset),
                                nNumberOfBytesToWrite = remaining.toUInt(),
                                lpNumberOfBytesWritten = bytesWritten.ptr,
                                lpOverlapped = overlapped.ptr,
                            ) == 0
                        ) {
                            throw windowsFileFailure(
                                "write",
                                path,
                                GetLastError(),
                            )
                        }
                        val written = bytesWritten.value.toInt()
                        if (written <= 0) {
                            throw WorldIOException(
                                "Could not write file $path: wrote no bytes",
                            )
                        }
                        offset += written
                        remaining -= written
                        sourceOffset += written
                    }
                }
            }
        }
    }

    override fun protectedFlush() {
        withCriticalSection {
            if (FlushFileBuffers(handle) == 0) {
                throw windowsFileFailure(
                    "durably sync",
                    path,
                    GetLastError(),
                )
            }
        }
    }

    override fun protectedResize(size: Long) {
        /*
         * Okio 3.18.1 treats SetFilePointer returning zero as failure, although
         * zero is the valid new position for resize(0). GetLastError then still
         * contains ERROR_SUCCESS, producing "The operation completed
         * successfully." Also, Win32 EOF extension leaves the new bytes
         * undefined while Okio FileHandle promises zero bytes, so growth is
         * implemented explicitly and only shrinking uses FileEndOfFileInfo.
         */
        require(size >= 0L)
        withCriticalSection {
            val currentSize = fileSize()
            if (size > currentSize) {
                writeZeros(currentSize, size - currentSize)
                return@withCriticalSection
            }
            memScoped {
                val end = alloc<FILE_END_OF_FILE_INFO>()
                end.EndOfFile.set(size)
                if (
                    SetFileInformationByHandle(
                        hFile = handle,
                        FileInformationClass = _FILE_INFO_BY_HANDLE_CLASS.FileEndOfFileInfo,
                        lpFileInformation = end.ptr,
                        dwBufferSize = sizeOf<FILE_END_OF_FILE_INFO>().toUInt(),
                    ) == 0
                ) {
                    throw windowsFileFailure(
                        "resize",
                        path,
                        GetLastError(),
                    )
                }
            }
        }
    }

    override fun protectedSize(): Long = withCriticalSection {
        fileSize()
    }

    private fun fileSize(): Long = memScoped {
        val size = alloc<LARGE_INTEGER>()
        if (GetFileSizeEx(handle, size.ptr) == 0) {
            throw windowsFileFailure(
                "inspect",
                path,
                GetLastError(),
            )
        }
        size.toLongValue()
    }

    private fun writeZeros(
        fileOffset: Long,
        byteCount: Long,
    ) {
        val zeros = ByteArray(minOf(byteCount, ZERO_FILL_BUFFER_SIZE.toLong()).toInt())
        zeros.usePinned { pinned ->
            memScoped {
                var offset = fileOffset
                var remaining = byteCount
                while (remaining > 0L) {
                    val toWrite = minOf(remaining, zeros.size.toLong()).toInt()
                    var chunkOffset = offset
                    var chunkRemaining = toWrite
                    var bufferOffset = 0
                    while (chunkRemaining > 0) {
                        val overlapped = allocOffset(chunkOffset)
                        val bytesWritten = alloc<DWORDVar>()
                        if (
                            WriteFile(
                                hFile = handle,
                                lpBuffer = pinned.addressOf(bufferOffset),
                                nNumberOfBytesToWrite = chunkRemaining.toUInt(),
                                lpNumberOfBytesWritten = bytesWritten.ptr,
                                lpOverlapped = overlapped.ptr,
                            ) == 0
                        ) {
                            throw windowsFileFailure(
                                "extend",
                                path,
                                GetLastError(),
                            )
                        }
                        val written = bytesWritten.value.toInt()
                        if (written <= 0) {
                            throw WorldIOException(
                                "Could not extend file $path: wrote no bytes",
                            )
                        }
                        chunkOffset += written
                        chunkRemaining -= written
                        bufferOffset += written
                    }
                    offset += toWrite
                    remaining -= toWrite
                }
            }
        }
    }

    override fun protectedClose() {
        synchronization.withLock {
            if (CloseHandle(handle) == 0) {
                throw windowsFileFailure(
                    "close",
                    path,
                    GetLastError(),
                )
            }
        }
    }

    private inline fun <T> withCriticalSection(block: () -> T): T =
        synchronization.withLock(block)
}

private const val ZERO_FILL_BUFFER_SIZE = 8192

@OptIn(ExperimentalNativeApi::class)
private class WindowsHandleSynchronization {
    /*
     * Okio 3.18.1's non-JVM Lock.withLock is a no-op, whereas its JVM
     * JvmFileHandle synchronizes every random-access operation. A native
     * critical section preserves FileHandle's concurrent-use contract and
     * prevents offset-sensitive operations from racing with resize or close.
     */
    private val criticalSection = nativeHeap.alloc<CRITICAL_SECTION>().also {
        InitializeCriticalSection(it.ptr)
    }

    /*
     * Do not delete the critical section in protectedClose(): another thread
     * may already have passed FileHandle's close check and be waiting here.
     * Reclaim it only after this synchronization object becomes unreachable.
     */
    @Suppress("unused")
    private val cleaner: Cleaner = createCleaner(criticalSection) {
        DeleteCriticalSection(it.ptr)
        nativeHeap.free(it.rawPtr)
    }

    inline fun <T> withLock(block: () -> T): T {
        EnterCriticalSection(criticalSection.ptr)
        try {
            return block()
        } finally {
            LeaveCriticalSection(criticalSection.ptr)
        }
    }
}

private fun openWindowsFileHandle(
    path: Path,
    readWrite: Boolean,
    creationDisposition: UInt,
): FileHandle {
    /*
     * Open a synchronous handle deliberately. The OVERLAPPED values below are
     * used only to provide explicit random-access offsets; without
     * FILE_FLAG_OVERLAPPED, ReadFile and WriteFile still complete before
     * returning. Read/write/delete sharing also keeps live readers compatible
     * with official-server writes and replacements, unlike Okio's narrower
     * MinGW sharing mode.
     */
    val handle = CreateFileW(
        lpFileName = path.toString(),
        dwDesiredAccess = if (readWrite) {
            GENERIC_READ or GENERIC_WRITE.toUInt()
        } else {
            GENERIC_READ
        },
        dwShareMode = (FILE_SHARE_READ or
                FILE_SHARE_WRITE or
                FILE_SHARE_DELETE).toUInt(),
        lpSecurityAttributes = null,
        dwCreationDisposition = creationDisposition,
        dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
        hTemplateFile = null,
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        throw windowsFileFailure("open", path, GetLastError())
    }
    return MingwSystemFileHandle(path, readWrite, handle)
}

private fun kotlinx.cinterop.MemScope.allocOffset(
    offset: Long,
): OVERLAPPED {
    val overlapped = alloc<OVERLAPPED>()
    overlapped.Internal = 0u
    overlapped.InternalHigh = 0u
    overlapped.Offset = offset.toUInt()
    overlapped.OffsetHigh = (offset ushr 32).toUInt()
    overlapped.hEvent = null
    return overlapped
}

private fun LARGE_INTEGER.set(value: Long) {
    LowPart = value.toUInt()
    HighPart = (value ushr 32).toInt()
}

private fun LARGE_INTEGER.toLongValue(): Long =
    (HighPart.toLong() shl 32) or
            (LowPart.toLong() and 0xffffffffL)

private fun windowsFileFailure(
    operation: String,
    path: Path,
    error: UInt,
): WorldIOException = WorldIOException(
    "Could not $operation file $path (Win32 error $error)",
)
