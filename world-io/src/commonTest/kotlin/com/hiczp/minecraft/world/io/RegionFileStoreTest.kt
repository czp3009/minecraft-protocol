package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class RegionFileStoreTest {
    @Test
    fun opensCanonicalNamesAndRejectsEverythingElse() {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()

        val store = RegionFileStore.open(
            regionFile = directory / "r.3.-1.mca",
            fileSystem = fileSystem,
        )
        assertEquals(RegionPosition(3, -1), store.regionPosition)
        assertEquals(directory / "r.3.-1.mca", store.path)
        store.close()

        listOf(
            directory / "region.mca",
            directory / "r.1.2.mcx",
            directory / "r.x.2.mca",
            directory / "r.+1.2.mca",
            directory / "r.01.2.mca",
        ).forEach { path ->
            assertFailsWith<WorldIOException> {
                RegionFileStore.open(path, fileSystem)
            }
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun readsAndWritesOneExactFileSharedWithDirectoryStores() = runTest {
        val fileSystem = FakeFileSystem()
        val path = "/world/region/r.0.0.mca".toPath()
        val position = ChunkPosition(5, 7)
        val document = NbtDocument(NbtCompound(mapOf("Value" to NbtInt(42))))

        val store = RegionFileStore.open(path, fileSystem)
        try {
            assertNull(store.readChunkNbt(position))
            assertFalse(store.exists(position))

            store.writeChunkNbt(position, document, Compression.NONE)
            assertEquals(document, store.readChunkNbt(position))
            assertTrue(store.exists(position))
            assertEquals(setOf(position.local), store.readAll().chunks.keys)
        } finally {
            store.close()
        }

        // The directory store reads bytes produced by the file-level store.
        val directoryStore = WorldRegionStore(path.parent!!, fileSystem)
        try {
            assertEquals(document, directoryStore.readChunkNbt(position))
        } finally {
            directoryStore.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun clearsThroughNullWritesAndKeepsRawChunks() = runTest {
        val fileSystem = FakeFileSystem()
        val store = RegionFileStore.open("/w/r.0.0.mca".toPath(), fileSystem)
        val position = ChunkPosition(31, 31)
        try {
            store.write(
                position,
                RegionChunk(
                    compression = Compression.NONE,
                    payload = RegionChunkPayload.Inline(byteArrayOf(7, 8, 9)),
                ),
            )
            val read = store.read(position)
            assertNotNull(read)
            assertContentEquals(
                byteArrayOf(7, 8, 9),
                checkNotNull(read.payload.compressedBytes),
            )

            store.write(position, null)
            assertFalse(store.exists(position))
            assertNull(store.read(position))
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun rejectsChunkCoordinatesOutsideTheOpenedRegion() = runTest {
        val fileSystem = FakeFileSystem()
        val store = RegionFileStore.open("/w/r.0.0.mca".toPath(), fileSystem)
        try {
            listOf(ChunkPosition(32, 0), ChunkPosition(0, -1)).forEach { outside ->
                assertFailsWith<IllegalArgumentException> { store.read(outside) }
                assertFailsWith<IllegalArgumentException> { store.exists(outside) }
                assertFailsWith<IllegalArgumentException> { store.write(outside, null) }
                assertFailsWith<IllegalArgumentException> {
                    store.readChunkNbt(outside)
                }
            }
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun configurationRejectsInvalidLimits() {
        assertFailsWith<IllegalArgumentException> {
            RegionFileStoreConfiguration(maximumCompressedChunkBytes = -1)
        }
        RegionFileStoreConfiguration()
    }
}
