@file:OptIn(ExperimentalForeignApi::class)

package com.hiczp.minecraft.world.io

import kotlinx.cinterop.*
import okio.*
import platform.windows.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class LiveFileSharingMingwTest {
    @Test
    fun liveHandleCoexistsWithOfficialStyleReadWriteInBothOrders() {
        withMingwTemporaryDirectory { root ->
            val target = root / "r.0.0.mca"
            FileSystem.SYSTEM.write(target) {
                write(byteArrayOf(1, 2, 3))
            }

            val serverFirst = openOfficialStyleHandle(target)
            try {
                FileSystem.SYSTEM.openLiveReadOnly(target).use { reader ->
                    writeAndForce(serverFirst, target, 9)
                    assertContentEquals(
                        byteArrayOf(9),
                        reader.readBytes(0L, 1),
                    )
                    assertContentEquals(
                        byteArrayOf(2, 3),
                        reader.readBytes(1L, 2),
                    )
                }
            } finally {
                closeWindowsHandle(serverFirst, target)
            }

            FileSystem.SYSTEM.write(target) {
                write(byteArrayOf(2, 3, 4))
            }
            FileSystem.SYSTEM.openLiveReadOnly(target).use { reader ->
                val readerFirst = openOfficialStyleHandle(target)
                try {
                    writeAndForce(readerFirst, target, 8)
                    assertContentEquals(
                        byteArrayOf(8),
                        reader.readBytes(0L, 1),
                    )
                    assertContentEquals(
                        byteArrayOf(3, 4),
                        reader.readBytes(1L, 2),
                    )
                } finally {
                    closeWindowsHandle(readerFirst, target)
                }
            }
        }
    }

    @Test
    fun liveHandleAllowsSidecarReplacementAndRetainsOldFileObject() {
        withMingwTemporaryDirectory { root ->
            val target = root / "c.0.0.mcc"
            val replacement = root / "replacement.tmp"
            FileSystem.SYSTEM.write(target) { write(byteArrayOf(4)) }
            FileSystem.SYSTEM.write(replacement) { write(byteArrayOf(7)) }

            FileSystem.SYSTEM.openLiveReadOnly(target).use { reader ->
                if (DeleteFileW(target.toString()) == 0) {
                    throw mingwSharingFailure(
                        "delete replacement target",
                        target,
                        GetLastError(),
                    )
                }
                if (
                    MoveFileExW(
                        replacement.toString(),
                        target.toString(),
                        0u,
                    ) == 0
                ) {
                    throw mingwSharingFailure(
                        "move replacement to",
                        target,
                        GetLastError(),
                    )
                }
                assertContentEquals(
                    byteArrayOf(4),
                    reader.readBytes(0L, 1),
                )
            }
            assertContentEquals(
                byteArrayOf(7),
                FileSystem.SYSTEM.read(target) { readByteArray() },
            )
        }
    }
}

private fun openOfficialStyleHandle(path: Path): COpaquePointer {
    val handle = CreateFileW(
        lpFileName = path.toString(),
        dwDesiredAccess = GENERIC_READ or GENERIC_WRITE.toUInt(),
        dwShareMode = (FILE_SHARE_READ or
                FILE_SHARE_WRITE or
                FILE_SHARE_DELETE).toUInt(),
        lpSecurityAttributes = null,
        dwCreationDisposition = OPEN_EXISTING.toUInt(),
        dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
        hTemplateFile = null,
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        throw mingwSharingFailure("open", path, GetLastError())
    }
    return handle
}

private fun writeAndForce(
    handle: COpaquePointer,
    path: Path,
    value: Int,
) {
    byteArrayOf(value.toByte()).usePinned { bytes ->
        memScoped {
            val written = alloc<DWORDVar>()
            if (
                WriteFile(
                    hFile = handle,
                    lpBuffer = bytes.addressOf(0),
                    nNumberOfBytesToWrite = 1u,
                    lpNumberOfBytesWritten = written.ptr,
                    lpOverlapped = null,
                ) == 0 || written.value != 1u
            ) {
                throw mingwSharingFailure(
                    "write",
                    path,
                    GetLastError(),
                )
            }
        }
    }
    if (FlushFileBuffers(handle) == 0) {
        throw mingwSharingFailure("flush", path, GetLastError())
    }
}

private fun closeWindowsHandle(handle: COpaquePointer, path: Path) {
    if (CloseHandle(handle) == 0) {
        throw mingwSharingFailure("close", path, GetLastError())
    }
}

private fun FileHandle.readBytes(offset: Long, byteCount: Int): ByteArray {
    val buffer = Buffer()
    val read = read(offset, buffer, byteCount.toLong())
    return if (read < 0L) ByteArray(0) else buffer.readByteArray()
}

private inline fun withMingwTemporaryDirectory(block: (Path) -> Unit) {
    val fileSystem = FileSystem.SYSTEM
    val root = createMingwTemporaryDirectory(fileSystem)
    try {
        block(root)
    } finally {
        fileSystem.deleteRecursively(root, mustExist = false)
    }
}

private fun createMingwTemporaryDirectory(fileSystem: FileSystem): Path {
    repeat(256) {
        val candidate = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                temporaryFileName(
                    Random.nextLong().toULong(),
                    prefix = "world-io-live-sharing-",
                )
        try {
            fileSystem.createDirectory(candidate, mustCreate = true)
            return candidate
        } catch (failure: IOException) {
            if (!fileSystem.exists(candidate)) throw failure
        }
    }
    throw WorldIOException(
        "Could not create a live file-sharing test directory",
    )
}

private fun mingwSharingFailure(
    operation: String,
    path: Path,
    error: UInt,
): IOException = IOException(
    "Could not $operation $path (Win32 error $error)",
)
