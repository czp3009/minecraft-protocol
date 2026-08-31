package com.hiczp.minecraft.world.io

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SystemFileHandleMingwTest {
    @Test
    fun systemRandomAccessUsesValidWin32Results() {
        withMingwSystemHandleTemporaryDirectory { root ->
            val path = root / "r.0.0.mca"
            FileSystem.SYSTEM.openRandomAccessReadWrite(path).use { fileHandle ->
                val offset = 4096L
                val bytes = byteArrayOf(1, 2, 3, 4)
                fileHandle.write(offset, bytes, 0, bytes.size)
                val actual = ByteArray(bytes.size)
                assertEquals(
                    bytes.size,
                    fileHandle.read(offset, actual, 0, actual.size),
                )
                assertContentEquals(bytes, actual)
                fileHandle.resize(0L)
                assertEquals(0L, fileHandle.size())
                fileHandle.resize(64L)
                val extended = ByteArray(64)
                assertEquals(
                    extended.size,
                    fileHandle.read(0L, extended, 0, extended.size),
                )
                assertContentEquals(ByteArray(64), extended)
                fileHandle.flush()
            }
        }
    }
}

private inline fun withMingwSystemHandleTemporaryDirectory(
    block: (Path) -> Unit,
) {
    val fileSystem = FileSystem.SYSTEM
    val root = createMingwSystemHandleTemporaryDirectory(fileSystem)
    try {
        block(root)
    } finally {
        fileSystem.deleteRecursively(root, mustExist = false)
    }
}

private fun createMingwSystemHandleTemporaryDirectory(
    fileSystem: FileSystem,
): Path {
    repeat(256) {
        val candidate = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                temporaryFileName(
                    Random.nextLong().toULong(),
                    prefix = "world-io-system-handle-",
                )
        try {
            fileSystem.createDirectory(candidate, mustCreate = true)
            return candidate
        } catch (failure: IOException) {
            if (!fileSystem.exists(candidate)) throw failure
        }
    }
    throw WorldIOException(
        "Could not create a system file-handle test directory",
    )
}
