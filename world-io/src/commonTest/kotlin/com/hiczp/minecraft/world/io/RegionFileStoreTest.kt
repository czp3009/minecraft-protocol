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
        val chunkNbtFormat = RegionChunkNbtFormat()

        val store = RegionFileStore.open(path, fileSystem)
        try {
            assertNull(store.read(position))
            assertFalse(store.exists(position))

            store.write(
                position,
                chunkNbtFormat.encode(document, Compression.NONE),
            )
            val read = checkNotNull(store.read(position))
            assertEquals(document, chunkNbtFormat.decode(read))
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
                    store.read(outside)
                }
            }
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun knownLengthWritesStreamInlineAndExternalPayloads() {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val store = RegionFileStore.open(directory / "r.0.0.mca", fileSystem)
        val inlinePosition = ChunkPosition(0, 0)
        val externalPosition = ChunkPosition(1, 0)
        val inline = ByteArray(32 * 1_024) { it.toByte() }
        val external = ByteArray(
            (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1) * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES + 1,
        ) { (it * 3).toByte() }

        try {
            store.write(inlinePosition, Compression.NONE, inline.size.toLong()) { write(inline) }
            store.write(externalPosition, Compression.ZLIB, external.size.toLong()) { write(external) }

            store.read(inlinePosition) { info, source ->
                assertFalse(info.external)
                assertEquals(inline.size.toLong(), info.compressedLength)
                assertContentEquals(inline, source.readByteArray())
            }
            store.read(externalPosition) { info, source ->
                assertTrue(info.external)
                assertEquals(external.size.toLong(), info.compressedLength)
                assertContentEquals(external, source.readByteArray())
            }
            assertTrue(fileSystem.exists(directory / "c.1.0.mcc"))
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun declaredLengthFailuresLeaveTheCommittedChunkAndCleanupExternalTemporaries() {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val store = RegionFileStore.open(directory / "r.0.0.mca", fileSystem, syncWrites = false)
        val position = ChunkPosition(0, 0)
        val original = byteArrayOf(1, 2, 3)
        store.write(position, RegionChunk(Compression.NONE, RegionChunkPayload.Inline(original)))

        try {
            assertFailsWith<WorldIOException> {
                store.write(position, Compression.NONE, compressedLength = 2) { writeByte(9) }
            }
            assertContentEquals(original, checkNotNull(store.read(position)).payload.compressedBytes)

            assertFailsWith<WorldIOException> {
                store.write(position, Compression.NONE, compressedLength = 1) {
                    write(byteArrayOf(8, 9))
                }
            }
            assertContentEquals(original, checkNotNull(store.read(position)).payload.compressedBytes)

            val firstExternalLength =
                (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1L) * REGION_SECTOR_BYTES -
                        REGION_CHUNK_RECORD_HEADER_BYTES + 1L
            assertFailsWith<WorldIOException> {
                store.write(position, Compression.NONE, firstExternalLength) { }
            }
            assertContentEquals(original, checkNotNull(store.read(position)).payload.compressedBytes)
            assertTrue(fileSystem.list(directory).none { it.name.startsWith(".mcc-") })
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun streamingReadsMustConsumeTheLentPayload() {
        val fileSystem = FakeFileSystem()
        val store = RegionFileStore.open("/world/region/r.0.0.mca".toPath(), fileSystem)
        val position = ChunkPosition(0, 0)
        store.write(
            position,
            RegionChunk(Compression.NONE, RegionChunkPayload.Inline(byteArrayOf(1, 2, 3))),
        )

        try {
            assertFailsWith<WorldIOException> {
                store.read(position) { _, source -> source.readByte() }
            }
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                checkNotNull(store.read(position)).payload.compressedBytes,
            )
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }
}
