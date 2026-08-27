package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class RegionBatchIoTest {
    @Test
    fun completeAndStreamingRegionWritesShareOneBatchPrimitive() {
        val base = FakeFileSystem()
        val path = "/world/region/r.0.0.mca".toPath()
        val countingMutableRegionFileSystem = CountingMutableRegionFileSystem(base, path)
        val mutableRegionFile = MutableRegionFile.open(path, countingMutableRegionFileSystem, syncWrites = false)
        val firstPosition = LocalChunkPosition(0, 0)
        val externalPosition = LocalChunkPosition(1, 0)
        val replacementPosition = LocalChunkPosition(2, 0)
        val regionPosition = RegionPosition(0, 0)
        val firstAbsolute = regionPosition.chunk(firstPosition)
        val externalAbsolute = regionPosition.chunk(externalPosition)
        val first = byteArrayOf(1, 2, 3)
        val external = ByteArray(firstExternalChunkLength().toInt()) { index -> index.toByte() }
        val replacement = byteArrayOf(7, 8, 9, 10)
        var escapedRead: RegionReadScope? = null
        var escapedWrite: RegionReplacementScope? = null

        try {
            mutableRegionFile.replaceRegion(
                listOf(
                    RegionChunkInput(firstPosition, CompressedChunk(Compression.NONE, first)),
                    RegionChunkInput(externalPosition, CompressedChunk(Compression.ZLIB, external)),
                ),
            )

            assertEquals(1, countingMutableRegionFileSystem.headerWrites)
            assertEquals(AnvilChunkPlacement.INLINE, mutableRegionFile.readChunkInfo(firstPosition)?.anvilChunkPlacement)
            assertEquals(AnvilChunkPlacement.EXTERNAL, mutableRegionFile.readChunkInfo(externalPosition)?.anvilChunkPlacement)
            assertContentEquals(first, mutableRegionFile.readCompressedChunk(firstAbsolute).bytesOrNull())
            assertContentEquals(external, mutableRegionFile.readCompressedChunk(externalPosition).bytesOrNull())
            assertFailsWith<IllegalArgumentException> { mutableRegionFile.readCompressedChunk(ChunkPosition(32, 0)) }

            mutableRegionFile.withReadScope {
                escapedRead = this
                assertEquals(regionPosition, this.regionPosition)
                assertEquals(listOf(firstPosition, externalPosition), localChunkPositions.toList())
                assertEquals(listOf(firstAbsolute, externalAbsolute), chunkPositions.toList())
                assertContentEquals(first, readCompressedChunk(localChunkPosition = firstPosition).bytesOrNull())
                withCompressedChunkSource(localChunkPosition = externalPosition) { regionChunkInfo, source ->
                    assertEquals(AnvilChunkPlacement.EXTERNAL, regionChunkInfo.anvilChunkPlacement)
                    assertEquals(external.size.toLong(), regionChunkInfo.compressedByteCount)
                    assertContentEquals(external, source.readByteArray())
                }
            }
            assertFailsWith<IllegalStateException> { checkNotNull(escapedRead).localChunkPositions }

            countingMutableRegionFileSystem.headerWrites = 0
            mutableRegionFile.replaceRegion {
                escapedWrite = this
                assertEquals(regionPosition, this.regionPosition)
                writeCompressedChunk(
                    localChunkPosition = replacementPosition,
                    compression = Compression.GZIP,
                    compressedByteCount = replacement.size.toLong(),
                ) { sink -> sink.write(replacement) }
            }

            assertEquals(1, countingMutableRegionFileSystem.headerWrites)
            assertEquals(setOf(replacementPosition), mutableRegionFile.readAnvilRegion().chunks.keys)
            assertContentEquals(replacement, mutableRegionFile.readCompressedChunk(replacementPosition).bytesOrNull())
            assertFalse(base.exists(path.parent!! / "c.1.0.mcc"))
            assertFailsWith<IllegalStateException> {
                checkNotNull(escapedWrite).writeCompressedChunk(firstPosition, inlineChunk(1))
            }

            mutableRegionFile.clear()
            assertTrue(mutableRegionFile.readAnvilRegion().chunks.isEmpty())
            assertTrue(base.exists(path))
        } finally {
            mutableRegionFile.close()
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun failedStreamingBatchLeavesThePreviousRegionAndCleansStaging() {
        val base = FakeFileSystem()
        val path = "/world/region/r.0.0.mca".toPath()
        val countingMutableRegionFileSystem = CountingMutableRegionFileSystem(base, path)
        val mutableRegionFile = MutableRegionFile.open(path, countingMutableRegionFileSystem, syncWrites = false)
        val retainedPosition = LocalChunkPosition(0, 0)
        val failedPosition = LocalChunkPosition(1, 0)
        val retained = byteArrayOf(1, 2, 3)
        mutableRegionFile.writeCompressedChunk(retainedPosition, CompressedChunk(Compression.NONE, retained))
        val committedHeaderWrites = countingMutableRegionFileSystem.headerWrites

        try {
            assertFailsWith<WorldIOException> {
                mutableRegionFile.replaceRegion {
                    writeCompressedChunk(retainedPosition, inlineChunk(9))
                    writeCompressedChunk(failedPosition, Compression.NONE, firstExternalChunkLength()) {}
                }
            }

            assertEquals(committedHeaderWrites, countingMutableRegionFileSystem.headerWrites)
            assertContentEquals(retained, mutableRegionFile.readCompressedChunk(retainedPosition).bytesOrNull())
            assertNull(mutableRegionFile.readCompressedChunk(failedPosition))
            assertTrue(base.list(path.parent!!).none { it.name.startsWith(".mcc-") })
        } finally {
            mutableRegionFile.close()
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun worldStoreExposesCompleteAndStreamingRegionPaths() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val regionPosition = RegionPosition(-1, 2)
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(31, 31)
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )

        try {
            regionStorage.replaceRegion(
                regionPosition,
                listOf(
                    RegionChunkInput(first, inlineChunk(1)),
                    RegionChunkInput(second, inlineChunk(2)),
                ),
            )
            assertEquals(setOf(first, second), regionStorage.readAnvilRegion(regionPosition)?.chunks?.keys)
            assertContentEquals(byteArrayOf(1), regionStorage.readCompressedChunk(regionPosition, first).bytesOrNull())

            val streamed = regionStorage.withReadScope(regionPosition) {
                localChunkPositions.associateWith { localChunkPosition ->
                    withCompressedChunkSource(regionPosition.chunk(localChunkPosition)) { _, source -> source.readByteArray() }
                }
            }
            assertContentEquals(byteArrayOf(1), streamed[first])
            assertContentEquals(byteArrayOf(2), streamed[second])

            regionStorage.replaceRegion(regionPosition) {
                writeCompressedChunk(regionPosition.chunk(second), Compression.NONE, 1L) { sink ->
                    sink.write(byteArrayOf(3))
                }
            }
            assertNull(regionStorage.readCompressedChunk(regionPosition.chunk(first)))
            assertContentEquals(byteArrayOf(3), regionStorage.readCompressedChunk(regionPosition.chunk(second)).bytesOrNull())
        } finally {
            regionStorage.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun explicitRegionPinsOneHandleWhileOneShotCallsRemainLightweight() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        val regionPosition = RegionPosition(0, 0)
        val path = directory / "r.0.0.mca"
        val countingMutableRegionFileSystem = CountingMutableRegionFileSystem(base, path)
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = countingMutableRegionFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(1, 0)

        regionStorage.openRegion(regionPosition).use { regionHandle ->
            assertEquals(regionPosition, regionHandle.regionPosition)
            assertEquals(1, regionStorage.activeRegionUsers(regionPosition))
            regionHandle.writeCompressedChunk(first, inlineChunk(1))
            regionHandle.writeCompressedChunk(regionPosition.chunk(second), inlineChunk(2))
            assertContentEquals(
                byteArrayOf(1),
                regionHandle.readCompressedChunk(regionPosition.chunk(first)).bytesOrNull(),
            )
            assertContentEquals(byteArrayOf(2), regionHandle.readCompressedChunk(second).bytesOrNull())
            assertFailsWith<IllegalArgumentException> {
                regionHandle.readCompressedChunk(ChunkPosition(32, 0))
            }
            assertEquals(1, countingMutableRegionFileSystem.mutableOpens)
            assertEquals(0, countingMutableRegionFileSystem.closes)
        }

        assertEquals(1, countingMutableRegionFileSystem.mutableOpens)
        assertEquals(1, countingMutableRegionFileSystem.closes)
        assertEquals(0, regionStorage.activeRegionCount())

        regionStorage.readCompressedChunk(regionPosition, first)
        regionStorage.readCompressedChunk(regionPosition.chunk(second))
        assertEquals(3, countingMutableRegionFileSystem.mutableOpens)
        assertEquals(3, countingMutableRegionFileSystem.closes)

        val unopened = regionStorage.openRegion(RegionPosition(1, 0))
        assertFalse(unopened.hasRegion())
        assertTrue(unopened.readChunkInfos().isEmpty())
        unopened.close()
        unopened.close()
        assertEquals(3, countingMutableRegionFileSystem.mutableOpens)
        assertFailsWith<IllegalStateException> { unopened.readChunkInfos() }

        regionStorage.close()
        base.checkNoOpenFiles()
    }
}

internal class CountingMutableRegionFileSystem(
    delegate: FileSystem,
    private val target: Path,
) : ForwardingFileSystem(delegate) {
    var mutableOpens = 0
    var liveOpens = 0
    var closes = 0
    var headerReads = 0
    var headerWrites = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        if (file != target) return fileHandle
        mutableOpens++
        return countingHandle(fileHandle, readWrite = true)
    }

    override fun openReadOnly(file: Path): FileHandle {
        val fileHandle = super.openReadOnly(file)
        if (file != target) return fileHandle
        liveOpens++
        return countingHandle(fileHandle, readWrite = false)
    }

    private fun countingHandle(
        delegate: FileHandle,
        readWrite: Boolean,
    ): FileHandle = object : FileHandle(readWrite) {
        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int {
            if (fileOffset == 0L) headerReads++
            return delegate.read(fileOffset, array, arrayOffset, byteCount)
        }

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) {
            if (fileOffset == 0L && byteCount == REGION_HEADER_BYTES) headerWrites++
            delegate.write(fileOffset, array, arrayOffset, byteCount)
        }

        override fun protectedFlush() = delegate.flush()

        override fun protectedResize(size: Long) = delegate.resize(size)

        override fun protectedSize(): Long = delegate.size()

        override fun protectedClose() {
            closes++
            delegate.close()
        }
    }
}

private fun firstExternalChunkLength(): Long =
    (REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD - 1L) * REGION_SECTOR_BYTES -
            REGION_CHUNK_RECORD_HEADER_BYTES + 1L

private fun inlineChunk(value: Int): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = byteArrayOf(value.toByte()),
)
