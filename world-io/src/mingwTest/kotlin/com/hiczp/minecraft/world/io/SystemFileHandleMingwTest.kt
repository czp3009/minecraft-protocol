package com.hiczp.minecraft.world.io

import okio.FileSystem
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
            FileSystem.SYSTEM.openRandomAccessReadWrite(path).use { handle ->
                val offset = 4096L
                val bytes = byteArrayOf(1, 2, 3, 4)
                handle.write(offset, bytes, 0, bytes.size)
                val actual = ByteArray(bytes.size)
                assertEquals(
                    bytes.size,
                    handle.read(offset, actual, 0, actual.size),
                )
                assertContentEquals(bytes, actual)
                handle.resize(0L)
                assertEquals(0L, handle.size())
                handle.resize(64L)
                val extended = ByteArray(64)
                assertEquals(
                    extended.size,
                    handle.read(0L, extended, 0, extended.size),
                )
                assertContentEquals(ByteArray(64), extended)
                handle.flush()
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
        } catch (failure: okio.IOException) {
            if (!fileSystem.exists(candidate)) throw failure
        }
    }
    throw WorldIOException(
        "Could not create a system file-handle test directory",
    )
}
