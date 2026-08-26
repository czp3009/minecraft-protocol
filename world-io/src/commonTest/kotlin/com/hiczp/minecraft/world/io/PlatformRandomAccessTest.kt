package com.hiczp.minecraft.world.io

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformRandomAccessTest {
    @Test
    fun readsWritesResizesAndTruncates() {
        withRandomAccessTestFileSystem { fileSystem, root ->
            val path = root / "random.dat"
            val expected = ByteArray(16 * 1024)
            fileSystem.openRandomAccessReadWrite(path).use { fileHandle ->
                repeat(2_000) { iteration ->
                    val offset = (iteration * 37) % (expected.size - 64)
                    val byteArray = ByteArray(64) { index ->
                        (iteration xor index).toByte()
                    }
                    fileHandle.write(offset.toLong(), byteArray, 0, byteArray.size)
                    byteArray.copyInto(expected, offset)

                    val actual = ByteArray(byteArray.size)
                    assertEquals(
                        byteArray.size,
                        fileHandle.read(offset.toLong(), actual, 0, actual.size),
                    )
                    assertContentEquals(byteArray, actual)
                }
                fileHandle.resize(expected.size.toLong())
                assertEquals(expected.size.toLong(), fileHandle.size())
                fileHandle.flush()
            }

            fileSystem.openTruncatedReadWrite(path).use { fileHandle ->
                assertEquals(0L, fileHandle.size())
                fileHandle.write(0L, expected, 0, expected.size)
                fileHandle.resize(expected.size.toLong())
                fileHandle.flush()
            }
            assertContentEquals(
                expected,
                fileSystem.read(path) { readByteArray() },
            )

            fileSystem.openRandomAccessReadWrite(path).use { fileHandle ->
                fileHandle.resize((expected.size + 64).toLong())
            }
            assertContentEquals(
                ByteArray(64),
                fileSystem.openLiveReadOnly(path).use { fileHandle ->
                    ByteArray(64).also { extended ->
                        assertEquals(
                            extended.size,
                            fileHandle.read(
                                expected.size.toLong(),
                                extended,
                                0,
                                extended.size,
                            ),
                        )
                    }
                },
            )
        }
    }

    @Test
    fun uniqueCreationIsAtomicAndReadOnlyRejectsMutations() {
        withRandomAccessTestFileSystem { fileSystem, root ->
            val path = root / "unique.dat"
            fileSystem.createRandomAccessReadWrite(path).use { fileHandle ->
                fileHandle.write(0L, byteArrayOf(1, 2, 3), 0, 3)
            }
            assertFailsWith<IOException> {
                fileSystem.createRandomAccessReadWrite(path).close()
            }

            fileSystem.openLiveReadOnly(path).use { fileHandle ->
                assertFailsWith<IllegalStateException> {
                    fileHandle.write(0L, byteArrayOf(9), 0, 1)
                }
                assertFailsWith<IllegalStateException> {
                    fileHandle.resize(0L)
                }
                assertFailsWith<IllegalStateException> {
                    fileHandle.flush()
                }
                val actual = ByteArray(3)
                assertEquals(3, fileHandle.read(0L, actual, 0, actual.size))
                assertContentEquals(byteArrayOf(1, 2, 3), actual)
            }
        }
    }
}

private inline fun withRandomAccessTestFileSystem(
    block: (FileSystem, Path) -> Unit,
) {
    val fakeFileSystem = FakeFileSystem()
    val root = "/random-access".toPath()
    fakeFileSystem.createDirectory(root)
    try {
        block(fakeFileSystem, root)
    } finally {
        fakeFileSystem.checkNoOpenFiles()
    }
}
