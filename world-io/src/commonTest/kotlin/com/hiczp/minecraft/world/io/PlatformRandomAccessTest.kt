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
            fileSystem.openRandomAccessReadWrite(path).use { handle ->
                repeat(2_000) { iteration ->
                    val offset = (iteration * 37) % (expected.size - 64)
                    val bytes = ByteArray(64) { index ->
                        (iteration xor index).toByte()
                    }
                    handle.write(offset.toLong(), bytes, 0, bytes.size)
                    bytes.copyInto(expected, offset)

                    val actual = ByteArray(bytes.size)
                    assertEquals(
                        bytes.size,
                        handle.read(offset.toLong(), actual, 0, actual.size),
                    )
                    assertContentEquals(bytes, actual)
                }
                handle.resize(expected.size.toLong())
                assertEquals(expected.size.toLong(), handle.size())
                handle.flush()
            }

            fileSystem.openTruncatedReadWrite(path).use { handle ->
                assertEquals(0L, handle.size())
                handle.write(0L, expected, 0, expected.size)
                handle.resize(expected.size.toLong())
                handle.flush()
            }
            assertContentEquals(
                expected,
                fileSystem.read(path) { readByteArray() },
            )

            fileSystem.openRandomAccessReadWrite(path).use { handle ->
                handle.resize((expected.size + 64).toLong())
            }
            assertContentEquals(
                ByteArray(64),
                fileSystem.openLiveReadOnly(path).use { handle ->
                    ByteArray(64).also { extended ->
                        assertEquals(
                            extended.size,
                            handle.read(
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
            fileSystem.createRandomAccessReadWrite(path).use { handle ->
                handle.write(0L, byteArrayOf(1, 2, 3), 0, 3)
            }
            assertFailsWith<IOException> {
                fileSystem.createRandomAccessReadWrite(path).close()
            }

            fileSystem.openLiveReadOnly(path).use { handle ->
                assertFailsWith<IllegalStateException> {
                    handle.write(0L, byteArrayOf(9), 0, 1)
                }
                assertFailsWith<IllegalStateException> {
                    handle.resize(0L)
                }
                assertFailsWith<IllegalStateException> {
                    handle.flush()
                }
                val actual = ByteArray(3)
                assertEquals(3, handle.read(0L, actual, 0, actual.size))
                assertContentEquals(byteArrayOf(1, 2, 3), actual)
            }
        }
    }
}

private inline fun withRandomAccessTestFileSystem(
    block: (FileSystem, Path) -> Unit,
) {
    val fileSystem = FakeFileSystem()
    val root = "/random-access".toPath()
    fileSystem.createDirectory(root)
    try {
        block(fileSystem, root)
    } finally {
        fileSystem.checkNoOpenFiles()
    }
}
