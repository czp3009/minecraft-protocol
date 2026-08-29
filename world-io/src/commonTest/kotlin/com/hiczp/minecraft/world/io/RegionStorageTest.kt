package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.time.Clock

class RegionStorageTest {
    @Test
    fun updatesUseNewSectorsThenReleaseTheOldAllocation() = runTest {
        val recordingFileSystem = RecordingFileSystem(
            FakeFileSystem().apply { allowReadsWhileWriting = true },
        )
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val regionStorage = store(recordingFileSystem, directory)

        regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(1)))
        val path = directory / "r.0.0.mca"
        assertEquals(RegionLocation(2, 1), header(recordingFileSystem, path).location(chunkPosition.localChunkPosition))

        regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(2)))
        assertEquals(RegionLocation(3, 1), header(recordingFileSystem, path).location(chunkPosition.localChunkPosition))
        assertEquals(1, recordingFileSystem.read(path) { readByteArray() }[2 * REGION_SECTOR_BYTES + 5].toInt())

        regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(3)))
        assertEquals(RegionLocation(2, 1), header(recordingFileSystem, path).location(chunkPosition.localChunkPosition))
        assertContentEquals(byteArrayOf(3), regionStorage.readCompressedChunk(chunkPosition).bytesOrNull())
        assertTrue(recordingFileSystem.moves.none { (_, target) -> target.name.endsWith(".mca") })

        regionStorage.close()
        assertEquals(0L, recordingFileSystem.openPathsCount())
        assertEquals(0L, recordingFileSystem.metadata(path).size!! % REGION_SECTOR_BYTES)
    }

    @Test
    fun externalThresholdSidecarsAndClearFollowTheCommittedHeader() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(-1, -1)
        val regionStorage = store(fakeFileSystem, directory)
        val externalBytes = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { (it * 13).toByte() }

        regionStorage.writeCompressedChunk(chunkPosition, chunk(externalBytes))
        val sidecar = directory / "c.-1.-1.mcc"
        assertContentEquals(externalBytes, fakeFileSystem.read(sidecar) { readByteArray() })
        assertEquals(AnvilChunkPlacement.EXTERNAL, regionStorage.readChunkInfo(chunkPosition)?.anvilChunkPlacement)
        assertTrue(regionStorage.hasChunk(chunkPosition))

        regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(9, 8, 7)))
        assertFalse(fakeFileSystem.exists(sidecar))
        assertEquals(AnvilChunkPlacement.INLINE, regionStorage.readChunkInfo(chunkPosition)?.anvilChunkPlacement)

        regionStorage.removeChunk(chunkPosition)
        assertNull(regionStorage.readCompressedChunk(chunkPosition))
        assertFalse(regionStorage.hasChunk(chunkPosition))
        regionStorage.close()
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun openSanitizesOnlyVanillaInvalidLocationsAndAcceptsOverlaps() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        fakeFileSystem.createDirectories(directory)
        val path = directory / "r.0.0.mca"
        val regionHeader = RegionHeader().apply {
            set(
                LocalChunkPosition(0, 0),
                RegionLocation(1, 1),
                1,
            )
            set(
                LocalChunkPosition(1, 0),
                RegionLocation(2, 1),
                2,
            )
            set(
                LocalChunkPosition(2, 0),
                RegionLocation(2, 1),
                3,
            )
        }
        val byteArray = ByteArray(3 * REGION_SECTOR_BYTES)
        regionHeader.encode().copyInto(byteArray)
        writeInt(byteArray, 2 * REGION_SECTOR_BYTES, 2)
        byteArray[2 * REGION_SECTOR_BYTES + 4] = RegionChunkRecordHeader.compressionId(Compression.NONE).toByte()
        byteArray[2 * REGION_SECTOR_BYTES + 5] = 42
        path.parent?.let(fakeFileSystem::createDirectories)
        fakeFileSystem.write(path) { write(byteArray) }

        val regionStorage = store(fakeFileSystem, directory)
        assertNull(regionStorage.readCompressedChunk(ChunkPosition(0, 0)))
        assertContentEquals(
            byteArrayOf(42),
            regionStorage.readCompressedChunk(ChunkPosition(1, 0)).bytesOrNull(),
        )
        assertContentEquals(
            byteArrayOf(42),
            regionStorage.readCompressedChunk(ChunkPosition(2, 0)).bytesOrNull(),
        )
        regionStorage.close()
    }

    @Test
    fun headerFailureLeavesTheOldChunkCommittedAndNewBytesUnreferenced() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(1)))
            it.close()
        }
        val beforeSize = base.metadata(directory / "r.0.0.mca").size!!
        val headerFailingFileSystem = HeaderFailingFileSystem(base)
        val regionStorage = store(headerFailingFileSystem, directory)

        assertFailsWith<IOException> {
            regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(2)))
        }
        regionStorage.close()

        val reopened = store(base, directory)
        assertContentEquals(
            byteArrayOf(1),
            reopened.readCompressedChunk(chunkPosition).bytesOrNull(),
        )
        assertTrue(base.metadata(directory / "r.0.0.mca").size!! > beforeSize)
        reopened.close()
    }

    @Test
    fun writesAndClearsAssignAutomaticTimestamps() = runTest {
        val base = FakeFileSystem().apply {
            allowReadsWhileWriting = true
        }
        val directory = "/world/timestamp/region".toPath()
        val path = directory / "r.0.0.mca"
        val chunkPosition = ChunkPosition(0, 0)
        val regionStorage = store(base, directory)
        val beforeWrite = Clock.System.now().epochSeconds.toInt()

        regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(1)))

        val afterWrite = Clock.System.now().epochSeconds.toInt()
        val writtenHeader = header(base, path)
        assertNotNull(writtenHeader.location(chunkPosition.localChunkPosition))
        assertTrue(
            writtenHeader.timestamp(chunkPosition.localChunkPosition) in
                    minOf(beforeWrite, afterWrite)..maxOf(beforeWrite, afterWrite),
        )

        val beforeClear = Clock.System.now().epochSeconds.toInt()
        regionStorage.removeChunk(chunkPosition)
        val afterClear = Clock.System.now().epochSeconds.toInt()

        val clearedHeader = header(base, path)
        assertNull(clearedHeader.location(chunkPosition.localChunkPosition))
        assertTrue(
            clearedHeader.timestamp(chunkPosition.localChunkPosition) in
                    minOf(beforeClear, afterClear)..maxOf(beforeClear, afterClear),
        )
        regionStorage.close()
    }

    @Test
    fun clearHeaderFailureKeepsTheDiskChunkAndExternalSidecar() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/clear-header/region".toPath()
        val path = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val chunkPosition = ChunkPosition(0, 0)
        val payload = externalPayload(11)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(payload))
            it.close()
        }
        val oldHeader = header(base, path)
        val clearing = store(HeaderFailingFileSystem(base), directory)

        assertFailsWith<IOException> {
            clearing.removeChunk(chunkPosition)
        }
        assertNotNull(clearing.readCompressedChunk(chunkPosition))
        clearing.close()

        assertEquals(oldHeader, header(base, path))
        assertContentEquals(payload, base.read(sidecar) { readByteArray() })
        val reopened = store(base, directory)
        assertContentEquals(
            payload,
            reopened.readCompressedChunk(chunkPosition).bytesOrNull(),
        )
        reopened.close()
    }

    @Test
    fun partialRecordFailureLeavesTheOldHeaderCommitted() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val chunkPosition = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(1)))
            it.close()
        }
        val oldLocation = header(base, path).location(chunkPosition.localChunkPosition)
        val oldSize = checkNotNull(base.metadata(path).size)
        val recordFailingFileSystem = RecordFailingFileSystem(base)
        val regionStorage = store(recordFailingFileSystem, directory)

        assertFailsWith<IOException> {
            regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(2, 3, 4)))
        }
        regionStorage.close()

        assertEquals(oldLocation, header(base, path).location(chunkPosition.localChunkPosition))
        assertTrue(checkNotNull(base.metadata(path).size) > oldSize)
        val reopened = store(base, directory)
        assertContentEquals(
            byteArrayOf(1),
            reopened.readCompressedChunk(chunkPosition).bytesOrNull(),
        )
        reopened.close()
    }

    @Test
    fun syncFailuresExposeRecordHeaderAndClearCommitBoundaries() = runTest {
        for (failureCall in 1..2) {
            val base = FakeFileSystem().apply {
                allowReadsWhileWriting = true
            }
            val directory = "/world-sync-$failureCall/region".toPath()
            val chunkPosition = ChunkPosition(0, 0)
            store(base, directory).also {
                it.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(1)))
                it.close()
            }
            val nthFlushFailingFileSystem = NthFlushFailingFileSystem(base, failureCall)
            val updating = CoordinatedRegionStore(
                directory = directory,
                fileSystem = nthFlushFailingFileSystem,
                regionStorageConfiguration = RegionStorageConfiguration(
                    syncWrites = true,
                ),
            )

            assertFailsWith<IOException> {
                updating.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(2)))
            }
            updating.close()

            val expected = if (failureCall == 1) {
                byteArrayOf(1)
            } else {
                byteArrayOf(2)
            }
            val reopened = store(base, directory)
            assertContentEquals(
                expected,
                reopened.readCompressedChunk(chunkPosition).bytesOrNull(),
            )
            reopened.close()
        }

        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world-sync-clear/region".toPath()
        val clearChunkPosition = ChunkPosition(0, 0)
        val sidecar = directory / "c.0.0.mcc"
        store(base, directory).also {
            it.writeCompressedChunk(clearChunkPosition, chunk(externalPayload(13)))
            it.close()
        }
        val clearing = CoordinatedRegionStore(
            directory = directory,
            fileSystem = NthFlushFailingFileSystem(base, failureCall = 1),
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = true),
        )

        assertFailsWith<IOException> { clearing.removeChunk(clearChunkPosition) }
        clearing.close()

        assertTrue(base.exists(sidecar))
        val reopened = store(base, directory)
        assertNull(reopened.readCompressedChunk(clearChunkPosition))
        reopened.close()
    }

    @Test
    fun failedExternalMoveKeepsTheCommittedStubAndOldSidecar() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val chunkPosition = ChunkPosition(0, 0)
        val first = externalPayload(1)
        val second = externalPayload(2)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(first))
            it.close()
        }
        val oldLocation = header(base, path).location(chunkPosition.localChunkPosition)
        val sidecarMoveFailingFileSystem = SidecarMoveFailingFileSystem(base)
        val regionStorage = store(sidecarMoveFailingFileSystem, directory)
        val beforeWrite = Clock.System.now().epochSeconds.toInt()

        assertFailsWith<IOException> {
            regionStorage.writeCompressedChunk(chunkPosition, chunk(second))
        }
        val afterWrite = Clock.System.now().epochSeconds.toInt()
        regionStorage.close()

        val committedHeader = header(base, path)
        assertNotEquals(oldLocation, committedHeader.location(chunkPosition.localChunkPosition))
        assertTrue(
            committedHeader.timestamp(chunkPosition.localChunkPosition) in
                    minOf(beforeWrite, afterWrite)..maxOf(beforeWrite, afterWrite),
        )
        assertContentEquals(first, base.read(directory / "c.0.0.mcc") { readByteArray() })
        assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })
        val reopened = store(base, directory)
        assertContentEquals(
            first,
            reopened.readCompressedChunk(chunkPosition).bytesOrNull(),
        )
        reopened.close()
    }

    @Test
    fun failedFirstExternalMoveLeavesACommittedStubWithoutASidecar() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/first-external/region".toPath()
        val path = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val chunkPosition = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(1)))
            it.close()
        }
        val oldLocation = header(base, path).location(chunkPosition.localChunkPosition)
        val failingStore = store(
            SidecarMoveFailingFileSystem(base),
            directory,
        )

        assertFailsWith<IOException> {
            failingStore.writeCompressedChunk(chunkPosition, chunk(externalPayload(12)))
        }
        failingStore.close()

        assertNotEquals(
            oldLocation,
            header(base, path).location(chunkPosition.localChunkPosition),
        )
        assertFalse(base.exists(sidecar))
        assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })
        val reopened = store(base, directory)
        assertNull(reopened.readCompressedChunk(chunkPosition))
        assertTrue(reopened.hasChunk(chunkPosition))
        assertEquals(1, reopened.readChunkCount(chunkPosition.regionPosition))
        assertEquals(
            listOf(chunkPosition.localChunkPosition),
            reopened.readLocalChunkPositions(chunkPosition.regionPosition)
        )
        reopened.close()
    }

    @Test
    fun externalTemporaryFailuresKeepTheOldCommitAndReservedSector() = runTest {
        SidecarSinkFailure.entries.forEach { failurePoint ->
            val base = FakeFileSystem().apply {
                allowReadsWhileWriting = true
            }
            val directory = "/world-${failurePoint.name}/region".toPath()
            val path = directory / "r.0.0.mca"
            val chunkPosition = ChunkPosition(0, 0)
            store(base, directory).also {
                it.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(1)))
                it.close()
            }
            val oldHeader = header(base, path)
            val sidecarSinkFailingFileSystem = SidecarSinkFailingFileSystem(base, failurePoint)
            val regionStorage = store(sidecarSinkFailingFileSystem, directory)

            assertFailsWith<IOException> {
                regionStorage.writeCompressedChunk(chunkPosition, chunk(externalPayload(6)))
            }
            assertEquals(oldHeader, header(base, path))
            assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })

            regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(2)))
            val oldLocation = checkNotNull(
                oldHeader.location(chunkPosition.localChunkPosition),
            )
            assertEquals(
                oldLocation.sectorOffset + 1,
                header(base, path).location(chunkPosition.localChunkPosition)?.sectorOffset,
                "Failure point $failurePoint did not reuse the disk allocation after last-release close",
            )
            regionStorage.close()

            val reopened = store(base, directory)
            assertContentEquals(
                byteArrayOf(2),
                reopened.readCompressedChunk(chunkPosition).bytesOrNull(),
            )
            reopened.close()
        }
    }

    @Test
    fun externalHeaderFailureKeepsOldHeaderAndSidecar() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val chunkPosition = ChunkPosition(0, 0)
        val first = externalPayload(7)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(first))
            it.close()
        }
        val oldHeader = header(base, path)
        val regionStorage = store(HeaderFailingFileSystem(base), directory)

        assertFailsWith<IOException> {
            regionStorage.writeCompressedChunk(chunkPosition, chunk(externalPayload(8)))
        }
        regionStorage.close()

        assertEquals(oldHeader, header(base, path))
        assertContentEquals(first, base.read(sidecar) { readByteArray() })
        assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })
    }

    @Test
    fun sidecarDeleteFailuresDoNotRollBackInternalWriteOrClear() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val chunkPosition = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(externalPayload(3)))
            it.close()
        }

        val internalFailure = SidecarDeleteFailingFileSystem(base, sidecar)
        val internalStore = store(internalFailure, directory)
        assertFailsWith<IOException> {
            internalStore.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(9)))
        }
        internalStore.close()
        assertTrue(base.exists(sidecar))
        val committedInternal = store(base, directory)
        assertContentEquals(
            byteArrayOf(9),
            committedInternal.readCompressedChunk(chunkPosition).bytesOrNull(),
        )
        committedInternal.writeCompressedChunk(chunkPosition, chunk(externalPayload(4)))
        committedInternal.close()

        val clearFailure = SidecarDeleteFailingFileSystem(base, sidecar)
        val clearingStore = store(clearFailure, directory)
        assertFailsWith<IOException> {
            clearingStore.removeChunk(chunkPosition)
        }
        clearingStore.close()
        assertNull(header(base, path).location(chunkPosition.localChunkPosition))
        assertTrue(base.exists(sidecar))
        val reopened = store(base, directory)
        assertNull(reopened.readCompressedChunk(chunkPosition))
        reopened.close()
    }

    @Test
    fun chunkReadAndWriteUseOnlyHeaderAndPositionalHandleOperations() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val chunkPosition = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(ByteArray(8_000) { 1 }))
            it.close()
        }
        val fileSize = checkNotNull(base.metadata(path).size)
        val handleRecordingFileSystem = HandleRecordingFileSystem(base)
        val regionStorage = store(handleRecordingFileSystem, directory)

        assertContentEquals(
            ByteArray(8_000) { 1 },
            regionStorage.readCompressedChunk(chunkPosition).bytesOrNull(),
        )
        regionStorage.writeCompressedChunk(chunkPosition, chunk(byteArrayOf(7)))

        assertTrue(
            handleRecordingFileSystem.reads.any {
                it.offset == 0L && it.byteCount == REGION_HEADER_BYTES
            },
        )
        assertFalse(
            handleRecordingFileSystem.reads.any {
                it.offset == 0L && it.byteCount.toLong() >= fileSize
            },
        )
        assertTrue(
            handleRecordingFileSystem.writes.any { it.offset >= REGION_HEADER_BYTES },
        )
        assertTrue(
            handleRecordingFileSystem.writes.any {
                it.offset == 0L && it.byteCount == REGION_HEADER_BYTES
            },
        )
        assertFalse(
            handleRecordingFileSystem.writes.any {
                it.offset == 0L && it.byteCount.toLong() >= fileSize
            },
        )
        regionStorage.close()
    }

    @Test
    fun unresolvedExternalRegionReplacementIsRejectedBeforeCreatingARegionFile() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val regionStorage = store(fakeFileSystem, directory)
        assertFailsWith<AnvilFormatException> {
            regionStorage.replaceRegion(
                chunkPosition.regionPosition,
                AnvilRegion(
                    mapOf(
                        chunkPosition.localChunkPosition to AnvilChunkRecord(
                            compression = Compression.NONE,
                            content = null,
                            anvilChunkPlacement = AnvilChunkPlacement.EXTERNAL,
                        ),
                    ),
                ),
            )
        }

        assertFalse(fakeFileSystem.exists(directory / "r.0.0.mca"))
        regionStorage.close()
    }

    @Test
    fun regionIndexRetainsChunksWhoseExternalPayloadIsMissing() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val sidecar = directory / "c.0.0.mcc"
        val payload = externalPayload(5)
        store(fakeFileSystem, directory).also {
            it.writeCompressedChunk(chunkPosition, chunk(payload))
            it.close()
        }
        fakeFileSystem.delete(sidecar)

        val missing = store(fakeFileSystem, directory)
        assertNull(missing.readCompressedChunk(chunkPosition))
        assertTrue(missing.hasChunk(chunkPosition))
        assertEquals(1, missing.readChunkCount(chunkPosition.regionPosition))
        assertEquals(
            listOf(chunkPosition.localChunkPosition),
            missing.readLocalChunkPositions(chunkPosition.regionPosition)
        )
        assertTrue(missing.readChunkInfos(chunkPosition.regionPosition).isEmpty())
        missing.close()

        sidecar.parent?.let(fakeFileSystem::createDirectories)
        fakeFileSystem.write(sidecar) { write(payload) }
        val reopened = store(fakeFileSystem, directory)
        assertTrue(reopened.hasChunk(chunkPosition))
        assertContentEquals(payload, checkNotNull(reopened.readCompressedChunk(chunkPosition)).toByteArray())
        reopened.close()
    }

    @Test
    fun storesDoNotRetainIdleRegionHandlesAndCloseRejectsNewOperations() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )

        regionStorage.readAnvilRegion(RegionPosition(0, 0))
        assertEquals(0, fakeFileSystem.openPaths.size)
        regionStorage.readAnvilRegion(RegionPosition(1, 0))
        assertFalse(fakeFileSystem.exists(directory / "r.0.0.mca"))
        assertFalse(fakeFileSystem.exists(directory / "r.1.0.mca"))
        assertEquals(0, fakeFileSystem.openPaths.size)
        regionStorage.close()
        fakeFileSystem.checkNoOpenFiles()
        assertFailsWith<IllegalStateException> {
            regionStorage.readCompressedChunk(ChunkPosition(0, 0))
        }
    }

    private fun store(
        fileSystem: FileSystem,
        directory: Path,
    ): CoordinatedRegionStore = CoordinatedRegionStore(
        directory = directory,
        fileSystem = fileSystem,
        regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
    )

    private fun chunk(bytes: ByteArray): CompressedChunk = CompressedChunk(
        compression = Compression.NONE,
        compressedBytes = bytes,
    )
}

private class RecordingFileSystem(
    private val fakeFileSystem: FakeFileSystem,
) : ForwardingFileSystem(fakeFileSystem) {
    val moves = mutableListOf<Pair<Path, Path>>()

    override fun atomicMove(source: Path, target: Path) {
        moves += source to target
        super.atomicMove(source, target)
    }

    fun openPathsCount(): Long = fakeFileSystem.openPaths.size.toLong()
}

private class HeaderFailingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    private var failHeader = true

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.endsWith(".mca")) return fileHandle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = fileHandle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                if (fileOffset == 0L && failHeader) {
                    failHeader = false
                    throw IOException("synthetic header failure")
                }
                fileHandle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() = fileHandle.flush()

            override fun protectedResize(size: Long) = fileHandle.resize(size)

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() = fileHandle.close()
        }
    }
}

private class NthFlushFailingFileSystem(
    delegate: FileSystem,
    private val failureCall: Int,
) : ForwardingFileSystem(delegate) {
    private var flushCalls = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.endsWith(".mca")) return fileHandle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = fileHandle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) = fileHandle.write(fileOffset, array, arrayOffset, byteCount)

            override fun protectedFlush() {
                flushCalls++
                fileHandle.flush()
                if (flushCalls == failureCall) {
                    throw IOException("synthetic sync failure")
                }
            }

            override fun protectedResize(size: Long) = fileHandle.resize(size)

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() = fileHandle.close()
        }
    }
}

private class RecordFailingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    private var failRecord = true

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = interceptRegionHandle(
        super.openReadWrite(file, mustCreate, mustExist),
        file,
    ) { fileHandle, fileOffset, array, arrayOffset, byteCount ->
        if (fileOffset >= REGION_HEADER_BYTES && failRecord) {
            failRecord = false
            fileHandle.write(fileOffset, array, arrayOffset, 1)
            throw IOException("synthetic record failure")
        }
        fileHandle.write(fileOffset, array, arrayOffset, byteCount)
    }
}

private class SidecarMoveFailingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    override fun atomicMove(source: Path, target: Path) {
        if (
            source.name.startsWith(".mcc-") &&
            target.name.endsWith(".mcc")
        ) {
            throw IOException("synthetic sidecar move failure")
        }
        super.atomicMove(source, target)
    }
}

private class SidecarDeleteFailingFileSystem(
    delegate: FileSystem,
    private val sidecar: Path,
) : ForwardingFileSystem(delegate) {
    private var failDelete = true

    override fun delete(path: Path, mustExist: Boolean) {
        if (path == sidecar && failDelete) {
            failDelete = false
            throw IOException("synthetic sidecar delete failure")
        }
        super.delete(path, mustExist)
    }
}

private class SidecarSinkFailingFileSystem(
    delegate: FileSystem,
    private val failurePoint: SidecarSinkFailure,
) : ForwardingFileSystem(delegate) {
    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (
            file.name.startsWith(".mcc-") &&
            failurePoint == SidecarSinkFailure.OPEN
        ) {
            throw IOException("synthetic sidecar open failure")
        }
        val sink = super.sink(file, mustCreate)
        if (!file.name.startsWith(".mcc-")) return sink
        return object : Sink by sink {
            override fun write(source: Buffer, byteCount: Long) {
                if (failurePoint == SidecarSinkFailure.WRITE) {
                    val partial = minOf(byteCount, 1)
                    if (partial > 0) sink.write(source, partial)
                    throw IOException("synthetic sidecar write failure")
                }
                sink.write(source, byteCount)
            }

            override fun close() {
                sink.close()
                if (failurePoint == SidecarSinkFailure.CLOSE) {
                    throw IOException("synthetic sidecar close failure")
                }
            }
        }
    }
}

private enum class SidecarSinkFailure {
    OPEN,
    WRITE,
    CLOSE,
}

private class HandleRecordingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    val reads = mutableListOf<HandleOperation>()
    val writes = mutableListOf<HandleOperation>()

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.endsWith(".mca")) return fileHandle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int {
                reads += HandleOperation(fileOffset, byteCount)
                return fileHandle.read(
                    fileOffset,
                    array,
                    arrayOffset,
                    byteCount,
                )
            }

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                writes += HandleOperation(fileOffset, byteCount)
                fileHandle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() = fileHandle.flush()

            override fun protectedResize(size: Long) = fileHandle.resize(size)

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() = fileHandle.close()
        }
    }
}

private data class HandleOperation(
    val offset: Long,
    val byteCount: Int,
)

private fun interceptRegionHandle(
    fileHandle: FileHandle,
    file: Path,
    write: (
        fileHandle: FileHandle,
        fileOffset: Long,
        byteArray: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ) -> Unit,
): FileHandle {
    if (!file.name.endsWith(".mca")) return fileHandle
    return object : FileHandle(readWrite = true) {
        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int = fileHandle.read(fileOffset, array, arrayOffset, byteCount)

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) = write(fileHandle, fileOffset, array, arrayOffset, byteCount)

        override fun protectedFlush() = fileHandle.flush()

        override fun protectedResize(size: Long) = fileHandle.resize(size)

        override fun protectedSize(): Long = fileHandle.size()

        override fun protectedClose() = fileHandle.close()
    }
}

private fun header(fileSystem: FileSystem, path: Path): RegionHeader =
    RegionHeader.decode(
        fileSystem.read(path) { readByteArray() }.copyOfRange(0, REGION_HEADER_BYTES),
    )

private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private fun externalPayload(seed: Int): ByteArray = ByteArray(
    REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
            REGION_CHUNK_RECORD_HEADER_BYTES,
) { index -> (index * 13 + seed).toByte() }
