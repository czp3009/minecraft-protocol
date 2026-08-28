package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class MutableRegionFileTest {
    @Test
    fun opensCanonicalNamesAndRejectsEverythingElse() {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()

        val mutableRegionFile = MutableRegionFile.open(
            regionFile = directory / "r.3.-1.mca",
            fileSystem = fakeFileSystem,
        )
        assertEquals(RegionPosition(3, -1), mutableRegionFile.regionPosition)
        assertEquals(directory / "r.3.-1.mca", mutableRegionFile.path)
        mutableRegionFile.close()

        listOf(
            directory / "region.mca",
            directory / "r.1.2.mcx",
            directory / "r.x.2.mca",
            directory / "r.+1.2.mca",
            directory / "r.01.2.mca",
        ).forEach { path ->
            assertFailsWith<WorldIOException> {
                MutableRegionFile.open(path, fakeFileSystem)
            }
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun readsAndWritesOneExactFileSharedWithDirectoryStores() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val path = "/world/region/r.0.0.mca".toPath()
        val chunkPosition = ChunkPosition(5, 7)
        val nbtDocument = NbtDocument(NbtCompound(mapOf("Value" to NbtInt(42))))
        val chunkNbtFormat = CompressedNbtFormat()

        val mutableRegionFile = MutableRegionFile.open(path, fakeFileSystem)
        try {
            assertNull(mutableRegionFile.readCompressedChunk(chunkPosition.localChunkPosition))
            assertFalse(mutableRegionFile.hasChunk(chunkPosition.localChunkPosition))

            mutableRegionFile.writeCompressedChunk(
                chunkPosition.localChunkPosition,
                chunkNbtFormat.encodeDocument(nbtDocument, Compression.NONE),
            )
            val read = checkNotNull(mutableRegionFile.readCompressedChunk(chunkPosition.localChunkPosition))
            assertEquals(nbtDocument, chunkNbtFormat.decodeDocument(read))
            assertTrue(mutableRegionFile.hasChunk(chunkPosition.localChunkPosition))
            assertEquals(setOf(chunkPosition.localChunkPosition), mutableRegionFile.readAnvilRegion().chunks.keys)
        } finally {
            mutableRegionFile.close()
        }

        // The directory store reads bytes produced by the file-level store.
        val directoryStore = CoordinatedRegionStore(path.parent!!, fakeFileSystem)
        try {
            assertEquals(nbtDocument, directoryStore.readChunkNbtDocument(chunkPosition))
        } finally {
            directoryStore.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun clearsExplicitlyAndKeepsRawChunks() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val mutableRegionFile = MutableRegionFile.open("/w/r.0.0.mca".toPath(), fakeFileSystem)
        val chunkPosition = ChunkPosition(31, 31)
        try {
            mutableRegionFile.writeCompressedChunk(
                chunkPosition.localChunkPosition,
                CompressedChunk(
                    compression = Compression.NONE,
                    compressedBytes = byteArrayOf(7, 8, 9),
                ),
            )
            val read = mutableRegionFile.readCompressedChunk(chunkPosition.localChunkPosition)
            assertNotNull(read)
            assertContentEquals(
                byteArrayOf(7, 8, 9),
                checkNotNull(read.toByteArray()),
            )

            mutableRegionFile.removeChunk(chunkPosition.localChunkPosition)
            assertFalse(mutableRegionFile.hasChunk(chunkPosition.localChunkPosition))
            assertNull(mutableRegionFile.readCompressedChunk(chunkPosition.localChunkPosition))
        } finally {
            mutableRegionFile.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun localCoordinatesAreResolvedAgainstTheOpenedRegion() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/w".toPath()
        val mutableRegionFile = MutableRegionFile.open(directory / "r.-2.3.mca", fakeFileSystem)
        val localChunkPosition = LocalChunkPosition(31, 1)
        try {
            mutableRegionFile.writeCompressedChunk(
                localChunkPosition,
                CompressedChunk(Compression.NONE, byteArrayOf(4)),
            )
            assertContentEquals(byteArrayOf(4), mutableRegionFile.readCompressedChunk(localChunkPosition).bytesOrNull())
            assertFalse(fakeFileSystem.exists(directory / "c.31.1.mcc"))
        } finally {
            mutableRegionFile.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun knownLengthWritesStreamInlineAndExternalPayloads() {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val mutableRegionFile = MutableRegionFile.open(directory / "r.0.0.mca", fakeFileSystem)
        val inlinePosition = LocalChunkPosition(0, 0)
        val externalPosition = LocalChunkPosition(1, 0)
        val inline = ByteArray(32 * 1_024) { it.toByte() }
        val external = ByteArray(
            (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1) * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES + 1,
        ) { (it * 3).toByte() }

        try {
            mutableRegionFile.writeCompressedChunk(inlinePosition, Compression.NONE, inline.size.toLong()) { sink ->
                sink.write(inline)
            }
            mutableRegionFile.writeCompressedChunk(
                externalPosition,
                Compression.ZLIB,
                external.size.toLong(),
            ) { sink -> sink.write(external) }

            mutableRegionFile.withCompressedChunkSource(inlinePosition) { regionChunkInfo, source ->
                assertEquals(AnvilChunkPlacement.INLINE, regionChunkInfo.anvilChunkPlacement)
                assertEquals(inline.size.toLong(), regionChunkInfo.compressedByteCount)
                assertContentEquals(inline, source.readByteArray())
            }
            mutableRegionFile.withCompressedChunkSource(externalPosition) { regionChunkInfo, source ->
                assertEquals(AnvilChunkPlacement.EXTERNAL, regionChunkInfo.anvilChunkPlacement)
                assertEquals(external.size.toLong(), regionChunkInfo.compressedByteCount)
                assertContentEquals(external, source.readByteArray())
            }
            assertTrue(fakeFileSystem.exists(directory / "c.1.0.mcc"))
        } finally {
            mutableRegionFile.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun declaredLengthFailuresLeaveTheCommittedChunkAndCleanupExternalTemporaries() {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val mutableRegionFile = MutableRegionFile.open(directory / "r.0.0.mca", fakeFileSystem, syncWrites = false)
        val localChunkPosition = LocalChunkPosition(0, 0)
        val original = byteArrayOf(1, 2, 3)
        mutableRegionFile.writeCompressedChunk(localChunkPosition, CompressedChunk(Compression.NONE, original))

        try {
            assertFailsWith<WorldIOException> {
                mutableRegionFile.writeCompressedChunk(localChunkPosition, Compression.NONE, compressedByteCount = 2) { sink ->
                    sink.writeByte(9)
                }
            }
            assertContentEquals(original, checkNotNull(mutableRegionFile.readCompressedChunk(localChunkPosition)).toByteArray())

            assertFailsWith<WorldIOException> {
                mutableRegionFile.writeCompressedChunk(localChunkPosition, Compression.NONE, compressedByteCount = 1) { sink ->
                    sink.write(byteArrayOf(8, 9))
                }
            }
            assertContentEquals(original, checkNotNull(mutableRegionFile.readCompressedChunk(localChunkPosition)).toByteArray())

            val firstExternalLength =
                (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1L) * REGION_SECTOR_BYTES -
                        REGION_CHUNK_RECORD_HEADER_BYTES + 1L
            assertFailsWith<WorldIOException> {
                mutableRegionFile.writeCompressedChunk(localChunkPosition, Compression.NONE, firstExternalLength) { }
            }
            assertContentEquals(original, checkNotNull(mutableRegionFile.readCompressedChunk(localChunkPosition)).toByteArray())
            assertTrue(fakeFileSystem.list(directory).none { it.name.startsWith(".mcc-") })
        } finally {
            mutableRegionFile.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun streamingReadsMustConsumeTheLentPayload() {
        val fakeFileSystem = FakeFileSystem()
        val mutableRegionFile = MutableRegionFile.open("/world/region/r.0.0.mca".toPath(), fakeFileSystem)
        val localChunkPosition = LocalChunkPosition(0, 0)
        mutableRegionFile.writeCompressedChunk(
            localChunkPosition,
            CompressedChunk(Compression.NONE, byteArrayOf(1, 2, 3)),
        )

        try {
            assertFailsWith<WorldIOException> {
                mutableRegionFile.withCompressedChunkSource(localChunkPosition) { _, source -> source.readByte() }
            }
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                checkNotNull(mutableRegionFile.readCompressedChunk(localChunkPosition)).toByteArray(),
            )
        } finally {
            mutableRegionFile.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }
}
