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
        val fileSystem = CountingRegionFileSystem(base, path)
        val store = RegionFileStore.open(path, fileSystem, syncWrites = false)
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
        var escapedWrite: RegionWriteScope? = null

        try {
            store.writeRegion(
                RegionFile(
                    linkedMapOf(
                        firstPosition to RegionChunk(
                            Compression.NONE,
                            RegionChunkPayload.External(first),
                            timestamp = 123,
                        ),
                        externalPosition to RegionChunk(
                            Compression.ZLIB,
                            RegionChunkPayload.Inline(external),
                            timestamp = 456,
                        ),
                    ),
                ),
            )

            assertEquals(1, fileSystem.headerWrites)
            assertFalse(checkNotNull(store.readChunk(firstPosition)).payload.isExternal)
            assertTrue(checkNotNull(store.readChunk(externalPosition)).payload.isExternal)
            assertContentEquals(first, store.readChunk(firstAbsolute)?.payload?.compressedBytes)
            assertContentEquals(external, store.readChunk(externalPosition)?.payload?.compressedBytes)
            assertFailsWith<IllegalArgumentException> { store.readChunk(ChunkPosition(32, 0)) }

            store.readRegion {
                escapedRead = this
                assertEquals(listOf(firstPosition, externalPosition), chunkPositions)
                assertContentEquals(first, readChunk(firstAbsolute)?.payload?.compressedBytes)
                readChunk(externalAbsolute) { info, source ->
                    assertTrue(info.external)
                    assertEquals(external.size.toLong(), info.compressedLength)
                    assertContentEquals(external, source.readByteArray())
                }
            }
            assertFailsWith<IllegalStateException> { checkNotNull(escapedRead).chunkPositions }

            fileSystem.headerWrites = 0
            store.writeRegion {
                escapedWrite = this
                writeChunk(
                    replacementAbsolute,
                    Compression.GZIP,
                    replacement.size.toLong(),
                ) { sink -> sink.write(replacement) }
            }

            assertEquals(1, fileSystem.headerWrites)
            assertEquals(setOf(replacementPosition), store.readRegion().chunks.keys)
            assertContentEquals(replacement, store.readChunk(replacementPosition)?.payload?.compressedBytes)
            assertFalse(base.exists(path.parent!! / "c.1.0.mcc"))
            assertFailsWith<IllegalStateException> {
                checkNotNull(escapedWrite).writeChunk(firstPosition, inlineChunk(1))
            }

            store.clearRegion()
            assertTrue(store.readRegion().chunks.isEmpty())
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
        val fileSystem = CountingRegionFileSystem(base, path)
        val store = RegionFileStore.open(path, fileSystem, syncWrites = false)
        val retainedPosition = LocalChunkPosition(0, 0)
        val failedPosition = LocalChunkPosition(1, 0)
        val retained = byteArrayOf(1, 2, 3)
        store.writeChunk(retainedPosition, RegionChunk(Compression.NONE, RegionChunkPayload.Inline(retained)))
        val committedHeaderWrites = fileSystem.headerWrites

        try {
            assertFailsWith<WorldIOException> {
                store.writeRegion {
                    writeChunk(retainedPosition, inlineChunk(9))
                    writeChunk(failedPosition, Compression.NONE, firstExternalChunkLength()) {}
                }
            }

            assertEquals(committedHeaderWrites, fileSystem.headerWrites)
            assertContentEquals(retained, store.readChunk(retainedPosition)?.payload?.compressedBytes)
            assertNull(store.readChunk(failedPosition))
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
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )

        try {
            store.writeRegion(
                position,
                RegionFile(
                    linkedMapOf(
                        first to inlineChunk(1),
                        second to inlineChunk(2),
                    ),
                ),
            )
            assertEquals(setOf(first, second), store.readRegion(position)?.chunks?.keys)
            assertContentEquals(byteArrayOf(1), store.readChunk(position, first)?.payload?.compressedBytes)

            val streamed = store.readRegion(position) {
                chunkPositions.associateWith { local ->
                    readChunk(position.chunk(local)) { _, source -> source.readByteArray() }
                }
            }
            assertContentEquals(byteArrayOf(1), streamed?.get(first))
            assertContentEquals(byteArrayOf(2), streamed?.get(second))

            store.writeRegion(position) {
                writeChunk(position.chunk(second), Compression.NONE, 1L) { sink -> sink.write(byteArrayOf(3)) }
            }
            assertNull(store.readChunk(position.chunk(first)))
            assertContentEquals(byteArrayOf(3), store.readChunk(position.chunk(second))?.payload?.compressedBytes)
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
        val fileSystem = CountingRegionFileSystem(base, path)
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(1, 0)

        store.withRegion(regionPosition) {
            assertEquals(regionPosition, position)
            assertEquals(1, store.activeRegionUsers(regionPosition))
            writeChunk(first, inlineChunk(1))
            writeChunk(regionPosition.chunk(second), inlineChunk(2))
            assertContentEquals(byteArrayOf(1), readChunk(regionPosition.chunk(first))?.payload?.compressedBytes)
            assertContentEquals(byteArrayOf(2), readChunk(second)?.payload?.compressedBytes)
            assertFailsWith<IllegalArgumentException> { readChunk(ChunkPosition(32, 0)) }
            assertEquals(1, fileSystem.mutableOpens)
            assertEquals(0, fileSystem.closes)
        }

        assertEquals(1, fileSystem.mutableOpens)
        assertEquals(1, fileSystem.closes)
        assertEquals(0, store.activeRegionCount())

        store.readChunk(regionPosition, first)
        store.readChunk(regionPosition.chunk(second))
        assertEquals(3, fileSystem.mutableOpens)
        assertEquals(3, fileSystem.closes)

        val unopened = store.openRegion(RegionPosition(1, 0))
        assertFalse(unopened.doesRegionExist())
        assertNull(unopened.readRegion())
        unopened.close()
        unopened.close()
        assertEquals(3, fileSystem.mutableOpens)
        assertFailsWith<IllegalStateException> { unopened.readRegion() }

        store.close()
        base.checkNoOpenFiles()
    }
}

internal class CountingRegionFileSystem(
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

private fun inlineChunk(value: Int): RegionChunk = RegionChunk(
    compression = Compression.NONE,
    payload = RegionChunkPayload.Inline(byteArrayOf(value.toByte())),
)
