package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class MutableRegionFileTest {
    @Test
    fun opensCanonicalNamesAndRejectsEverythingElse() {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()

        val store = MutableRegionFile.open(
            regionFile = directory / "r.3.-1.mca",
            fileSystem = fileSystem,
        )
        assertEquals(RegionPosition(3, -1), store.position)
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
                MutableRegionFile.open(path, fileSystem)
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
        val chunkNbtFormat = CompressedNbtFormat()

        val store = MutableRegionFile.open(path, fileSystem)
        try {
            assertNull(store.readCompressedChunk(position.local))
            assertFalse(store.hasChunk(position.local))

            store.writeCompressedChunk(
                position.local,
                chunkNbtFormat.encodeDocument(document, Compression.NONE),
            )
            val read = checkNotNull(store.readCompressedChunk(position.local))
            assertEquals(document, chunkNbtFormat.decodeDocument(read))
            assertTrue(store.hasChunk(position.local))
            assertEquals(setOf(position.local), store.readAnvilRegion().chunks.keys)
        } finally {
            store.close()
        }

        // The directory store reads bytes produced by the file-level store.
        val directoryStore = RegionStorage(path.parent!!, fileSystem)
        try {
            assertEquals(document, directoryStore.readChunkNbtDocument(position))
        } finally {
            directoryStore.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun clearsExplicitlyAndKeepsRawChunks() = runTest {
        val fileSystem = FakeFileSystem()
        val store = MutableRegionFile.open("/w/r.0.0.mca".toPath(), fileSystem)
        val position = ChunkPosition(31, 31)
        try {
            store.writeCompressedChunk(
                position.local,
                CompressedChunk(
                    compression = Compression.NONE,
                    compressedBytes = byteArrayOf(7, 8, 9),
                ),
            )
            val read = store.readCompressedChunk(position.local)
            assertNotNull(read)
            assertContentEquals(
                byteArrayOf(7, 8, 9),
                checkNotNull(read.toByteArray()),
            )

            store.removeChunk(position.local)
            assertFalse(store.hasChunk(position.local))
            assertNull(store.readCompressedChunk(position.local))
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun localCoordinatesAreResolvedAgainstTheOpenedRegion() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/w".toPath()
        val store = MutableRegionFile.open(directory / "r.-2.3.mca", fileSystem)
        val local = LocalChunkPosition(31, 1)
        try {
            store.writeCompressedChunk(
                local,
                CompressedChunk(Compression.NONE, byteArrayOf(4)),
            )
            assertContentEquals(byteArrayOf(4), store.readCompressedChunk(local).bytesOrNull())
            assertFalse(fileSystem.exists(directory / "c.31.1.mcc"))
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun knownLengthWritesStreamInlineAndExternalPayloads() {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val store = MutableRegionFile.open(directory / "r.0.0.mca", fileSystem)
        val inlinePosition = LocalChunkPosition(0, 0)
        val externalPosition = LocalChunkPosition(1, 0)
        val inline = ByteArray(32 * 1_024) { it.toByte() }
        val external = ByteArray(
            (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1) * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES + 1,
        ) { (it * 3).toByte() }

        try {
            store.writeCompressedChunk(inlinePosition, Compression.NONE, inline.size.toLong()) { sink ->
                sink.write(inline)
            }
            store.writeCompressedChunk(
                externalPosition,
                Compression.ZLIB,
                external.size.toLong(),
            ) { sink -> sink.write(external) }

            store.withCompressedChunkSource(inlinePosition) { info, source ->
                assertEquals(AnvilChunkPlacement.INLINE, info.placement)
                assertEquals(inline.size.toLong(), info.compressedByteCount)
                assertContentEquals(inline, source.readByteArray())
            }
            store.withCompressedChunkSource(externalPosition) { info, source ->
                assertEquals(AnvilChunkPlacement.EXTERNAL, info.placement)
                assertEquals(external.size.toLong(), info.compressedByteCount)
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
        val store = MutableRegionFile.open(directory / "r.0.0.mca", fileSystem, syncWrites = false)
        val position = LocalChunkPosition(0, 0)
        val original = byteArrayOf(1, 2, 3)
        store.writeCompressedChunk(position, CompressedChunk(Compression.NONE, original))

        try {
            assertFailsWith<WorldIOException> {
                store.writeCompressedChunk(position, Compression.NONE, compressedByteCount = 2) { sink ->
                    sink.writeByte(9)
                }
            }
            assertContentEquals(original, checkNotNull(store.readCompressedChunk(position)).toByteArray())

            assertFailsWith<WorldIOException> {
                store.writeCompressedChunk(position, Compression.NONE, compressedByteCount = 1) { sink ->
                    sink.write(byteArrayOf(8, 9))
                }
            }
            assertContentEquals(original, checkNotNull(store.readCompressedChunk(position)).toByteArray())

            val firstExternalLength =
                (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1L) * REGION_SECTOR_BYTES -
                        REGION_CHUNK_RECORD_HEADER_BYTES + 1L
            assertFailsWith<WorldIOException> {
                store.writeCompressedChunk(position, Compression.NONE, firstExternalLength) { }
            }
            assertContentEquals(original, checkNotNull(store.readCompressedChunk(position)).toByteArray())
            assertTrue(fileSystem.list(directory).none { it.name.startsWith(".mcc-") })
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun streamingReadsMustConsumeTheLentPayload() {
        val fileSystem = FakeFileSystem()
        val store = MutableRegionFile.open("/world/region/r.0.0.mca".toPath(), fileSystem)
        val position = LocalChunkPosition(0, 0)
        store.writeCompressedChunk(
            position,
            CompressedChunk(Compression.NONE, byteArrayOf(1, 2, 3)),
        )

        try {
            assertFailsWith<WorldIOException> {
                store.withCompressedChunkSource(position) { _, source -> source.readByte() }
            }
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                checkNotNull(store.readCompressedChunk(position)).toByteArray(),
            )
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }
}
