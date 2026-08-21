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
        val fileSystem = CountingMutableRegionFileSystem(base, path)
        val store = MutableRegionFile.open(path, fileSystem, syncWrites = false)
        val firstPosition = LocalChunkPosition(0, 0)
        val externalPosition = LocalChunkPosition(1, 0)
        val replacementPosition = LocalChunkPosition(2, 0)
        val regionPosition = RegionPosition(0, 0)
        val firstAbsolute = regionPosition.chunk(firstPosition)
        val externalAbsolute = regionPosition.chunk(externalPosition)
        val replacementAbsolute = regionPosition.chunk(replacementPosition)
        val first = byteArrayOf(1, 2, 3)
        val external = ByteArray(firstExternalChunkLength().toInt()) { index -> index.toByte() }
        val replacement = byteArrayOf(7, 8, 9, 10)
        var escapedRead: RegionReadScope? = null
        var escapedWrite: RegionReplacementScope? = null

        try {
            store.replaceRegion(
                listOf(
                    RegionChunkInput(firstPosition, CompressedChunk(Compression.NONE, first)),
                    RegionChunkInput(externalPosition, CompressedChunk(Compression.ZLIB, external)),
                ),
            )

            assertEquals(1, fileSystem.headerWrites)
            assertEquals(AnvilChunkPlacement.INLINE, store.readChunkInfo(firstPosition)?.placement)
            assertEquals(AnvilChunkPlacement.EXTERNAL, store.readChunkInfo(externalPosition)?.placement)
            assertContentEquals(first, store.readCompressedChunk(firstAbsolute).bytesOrNull())
            assertContentEquals(external, store.readCompressedChunk(externalPosition).bytesOrNull())
            assertFailsWith<IllegalArgumentException> { store.readCompressedChunk(ChunkPosition(32, 0)) }

            store.withReadScope {
                escapedRead = this
                assertEquals(listOf(firstPosition, externalPosition), localChunkPositions.toList())
                assertEquals(listOf(firstAbsolute, externalAbsolute), chunkPositions.toList())
                assertContentEquals(first, readCompressedChunk(firstAbsolute).bytesOrNull())
                withCompressedChunkSource(externalAbsolute) { info, source ->
                    assertEquals(AnvilChunkPlacement.EXTERNAL, info.placement)
                    assertEquals(external.size.toLong(), info.compressedByteCount)
                    assertContentEquals(external, source.readByteArray())
                }
            }
            assertFailsWith<IllegalStateException> { checkNotNull(escapedRead).localChunkPositions }

            fileSystem.headerWrites = 0
            store.replaceRegion {
                escapedWrite = this
                writeCompressedChunk(
                    replacementAbsolute,
                    Compression.GZIP,
                    replacement.size.toLong(),
                ) { sink -> sink.write(replacement) }
            }

            assertEquals(1, fileSystem.headerWrites)
            assertEquals(setOf(replacementPosition), store.readAnvilRegion().chunks.keys)
            assertContentEquals(replacement, store.readCompressedChunk(replacementPosition).bytesOrNull())
            assertFalse(base.exists(path.parent!! / "c.1.0.mcc"))
            assertFailsWith<IllegalStateException> {
                checkNotNull(escapedWrite).writeCompressedChunk(firstPosition, inlineChunk(1))
            }

            store.clear()
            assertTrue(store.readAnvilRegion().chunks.isEmpty())
            assertTrue(base.exists(path))
        } finally {
            store.close()
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun failedStreamingBatchLeavesThePreviousRegionAndCleansStaging() {
        val base = FakeFileSystem()
        val path = "/world/region/r.0.0.mca".toPath()
        val fileSystem = CountingMutableRegionFileSystem(base, path)
        val store = MutableRegionFile.open(path, fileSystem, syncWrites = false)
        val retainedPosition = LocalChunkPosition(0, 0)
        val failedPosition = LocalChunkPosition(1, 0)
        val retained = byteArrayOf(1, 2, 3)
        store.writeCompressedChunk(retainedPosition, CompressedChunk(Compression.NONE, retained))
        val committedHeaderWrites = fileSystem.headerWrites

        try {
            assertFailsWith<WorldIOException> {
                store.replaceRegion {
                    writeCompressedChunk(retainedPosition, inlineChunk(9))
                    writeCompressedChunk(failedPosition, Compression.NONE, firstExternalChunkLength()) {}
                }
            }

            assertEquals(committedHeaderWrites, fileSystem.headerWrites)
            assertContentEquals(retained, store.readCompressedChunk(retainedPosition).bytesOrNull())
            assertNull(store.readCompressedChunk(failedPosition))
            assertTrue(base.list(path.parent!!).none { it.name.startsWith(".mcc-") })
        } finally {
            store.close()
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun worldStoreExposesCompleteAndStreamingRegionPaths() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val position = RegionPosition(-1, 2)
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(31, 31)
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )

        try {
            store.replaceRegion(
                position,
                listOf(
                    RegionChunkInput(first, inlineChunk(1)),
                    RegionChunkInput(second, inlineChunk(2)),
                ),
            )
            assertEquals(setOf(first, second), store.readAnvilRegion(position)?.chunks?.keys)
            assertContentEquals(byteArrayOf(1), store.readCompressedChunk(position, first).bytesOrNull())

            val streamed = store.withReadScope(position) {
                localChunkPositions.associateWith { local ->
                    withCompressedChunkSource(position.chunk(local)) { _, source -> source.readByteArray() }
                }
            }
            assertContentEquals(byteArrayOf(1), streamed[first])
            assertContentEquals(byteArrayOf(2), streamed[second])

            store.replaceRegion(position) {
                writeCompressedChunk(position.chunk(second), Compression.NONE, 1L) { sink ->
                    sink.write(byteArrayOf(3))
                }
            }
            assertNull(store.readCompressedChunk(position.chunk(first)))
            assertContentEquals(byteArrayOf(3), store.readCompressedChunk(position.chunk(second)).bytesOrNull())
        } finally {
            store.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun explicitRegionPinsOneHandleWhileOneShotCallsRemainLightweight() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        val regionPosition = RegionPosition(0, 0)
        val path = directory / "r.0.0.mca"
        val fileSystem = CountingMutableRegionFileSystem(base, path)
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(1, 0)

        store.openRegion(regionPosition).use { regionHandle ->
            assertEquals(regionPosition, regionHandle.position)
            assertEquals(1, store.activeRegionUsers(regionPosition))
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
            assertEquals(1, fileSystem.mutableOpens)
            assertEquals(0, fileSystem.closes)
        }

        assertEquals(1, fileSystem.mutableOpens)
        assertEquals(1, fileSystem.closes)
        assertEquals(0, store.activeRegionCount())

        store.readCompressedChunk(regionPosition, first)
        store.readCompressedChunk(regionPosition.chunk(second))
        assertEquals(3, fileSystem.mutableOpens)
        assertEquals(3, fileSystem.closes)

        val unopened = store.openRegion(RegionPosition(1, 0))
        assertFalse(unopened.hasRegion())
        assertTrue(unopened.readChunkInfos().isEmpty())
        unopened.close()
        unopened.close()
        assertEquals(3, fileSystem.mutableOpens)
        assertFailsWith<IllegalStateException> { unopened.readChunkInfos() }

        store.close()
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
    var headerWrites = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        if (file != target) return handle
        mutableOpens++
        return countingHandle(handle, readWrite = true)
    }

    override fun openReadOnly(file: Path): FileHandle {
        val handle = super.openReadOnly(file)
        if (file != target) return handle
        liveOpens++
        return countingHandle(handle, readWrite = false)
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
        ): Int = delegate.read(fileOffset, array, arrayOffset, byteCount)

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
