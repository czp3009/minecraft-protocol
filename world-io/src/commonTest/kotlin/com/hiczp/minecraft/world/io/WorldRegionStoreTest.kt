package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.time.Clock

class WorldRegionStoreTest {
    @Test
    fun updatesUseNewSectorsThenReleaseTheOldAllocation() = runTest {
        val fileSystem = RecordingFileSystem(
            FakeFileSystem().apply { allowReadsWhileWriting = true },
        )
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val store = store(fileSystem, directory)

        store.writeChunk(position, chunk(byteArrayOf(1)))
        val path = directory / "r.0.0.mca"
        assertEquals(RegionLocation(2, 1), header(fileSystem, path).location(position.local))

        store.writeChunk(position, chunk(byteArrayOf(2)))
        assertEquals(RegionLocation(3, 1), header(fileSystem, path).location(position.local))
        assertEquals(1, fileSystem.readBytes(path)[2 * REGION_SECTOR_BYTES + 5].toInt())

        store.writeChunk(position, chunk(byteArrayOf(3)))
        assertEquals(RegionLocation(2, 1), header(fileSystem, path).location(position.local))
        assertContentEquals(byteArrayOf(3), store.readChunk(position)?.payload?.compressedBytes)
        assertTrue(fileSystem.moves.none { (_, target) -> target.name.endsWith(".mca") })

        store.close()
        assertEquals(0L, fileSystem.openPathsCount())
        assertEquals(0L, fileSystem.metadata(path).size!! % REGION_SECTOR_BYTES)
    }

    @Test
    fun externalThresholdSidecarsAndClearFollowTheCommittedHeader() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val position = ChunkPosition(-1, -1)
        val store = store(fileSystem, directory)
        val externalBytes = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { (it * 13).toByte() }

        store.writeChunk(position, chunk(externalBytes))
        val sidecar = directory / "c.-1.-1.mcc"
        assertContentEquals(externalBytes, fileSystem.readBytes(sidecar))
        assertTrue(checkNotNull(store.readChunk(position)).payload.isExternal)
        assertTrue(store.doesChunkExist(position))

        store.writeChunk(position, chunk(byteArrayOf(9, 8, 7)))
        assertFalse(fileSystem.exists(sidecar))
        assertFalse(checkNotNull(store.readChunk(position)).payload.isExternal)

        store.clearChunk(position)
        assertNull(store.readChunk(position))
        assertFalse(store.doesChunkExist(position))
        store.close()
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun openSanitizesOnlyVanillaInvalidLocationsAndAcceptsOverlaps() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        fileSystem.createDirectories(directory)
        val path = directory / "r.0.0.mca"
        val header = RegionHeader().apply {
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
        val bytes = ByteArray(3 * REGION_SECTOR_BYTES)
        header.encode().copyInto(bytes)
        writeInt(bytes, 2 * REGION_SECTOR_BYTES, 2)
        bytes[2 * REGION_SECTOR_BYTES + 4] = RegionChunkRecordHeader.compressionId(Compression.NONE).toByte()
        bytes[2 * REGION_SECTOR_BYTES + 5] = 42
        fileSystem.writeBytes(path, bytes)

        val store = store(fileSystem, directory)
        assertNull(store.readChunk(ChunkPosition(0, 0)))
        assertContentEquals(
            byteArrayOf(42),
            store.readChunk(ChunkPosition(1, 0))?.payload?.compressedBytes,
        )
        assertContentEquals(
            byteArrayOf(42),
            store.readChunk(ChunkPosition(2, 0))?.payload?.compressedBytes,
        )
        store.close()
    }

    @Test
    fun headerFailureLeavesTheOldChunkCommittedAndNewBytesUnreferenced() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeChunk(position, chunk(byteArrayOf(1)))
            it.close()
        }
        val beforeSize = base.metadata(directory / "r.0.0.mca").size!!
        val failing = HeaderFailingFileSystem(base)
        val store = store(failing, directory)

        assertFailsWith<IOException> {
            store.writeChunk(position, chunk(byteArrayOf(2)))
        }
        store.close()

        val reopened = store(base, directory)
        assertContentEquals(
            byteArrayOf(1),
            reopened.readChunk(position)?.payload?.compressedBytes,
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
        val position = ChunkPosition(0, 0)
        val store = store(base, directory)
        val beforeWrite = Clock.System.now().epochSeconds.toInt()

        store.writeChunk(position, chunk(byteArrayOf(1)))

        val afterWrite = Clock.System.now().epochSeconds.toInt()
        val writtenHeader = header(base, path)
        assertNotNull(writtenHeader.location(position.local))
        assertTrue(
            writtenHeader.timestamp(position.local) in
                    minOf(beforeWrite, afterWrite)..maxOf(beforeWrite, afterWrite),
        )

        val beforeClear = Clock.System.now().epochSeconds.toInt()
        store.clearChunk(position)
        val afterClear = Clock.System.now().epochSeconds.toInt()

        val clearedHeader = header(base, path)
        assertNull(clearedHeader.location(position.local))
        assertTrue(
            clearedHeader.timestamp(position.local) in
                    minOf(beforeClear, afterClear)..maxOf(beforeClear, afterClear),
        )
        store.close()
    }

    @Test
    fun clearHeaderFailureKeepsTheDiskChunkAndExternalSidecar() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/clear-header/region".toPath()
        val path = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val position = ChunkPosition(0, 0)
        val payload = externalPayload(11)
        store(base, directory).also {
            it.writeChunk(position, chunk(payload))
            it.close()
        }
        val oldHeader = header(base, path)
        val clearing = store(HeaderFailingFileSystem(base), directory)

        assertFailsWith<IOException> {
            clearing.clearChunk(position)
        }
        assertNotNull(clearing.readChunk(position))
        clearing.close()

        assertEquals(oldHeader, header(base, path))
        assertContentEquals(payload, base.readBytes(sidecar))
        val reopened = store(base, directory)
        assertContentEquals(
            payload,
            reopened.readChunk(position)?.payload?.compressedBytes,
        )
        reopened.close()
    }

    @Test
    fun partialRecordFailureLeavesTheOldHeaderCommitted() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val position = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeChunk(position, chunk(byteArrayOf(1)))
            it.close()
        }
        val oldLocation = header(base, path).location(position.local)
        val oldSize = checkNotNull(base.metadata(path).size)
        val failing = RecordFailingFileSystem(base)
        val store = store(failing, directory)

        assertFailsWith<IOException> {
            store.writeChunk(position, chunk(byteArrayOf(2, 3, 4)))
        }
        store.close()

        assertEquals(oldLocation, header(base, path).location(position.local))
        assertTrue(checkNotNull(base.metadata(path).size) > oldSize)
        val reopened = store(base, directory)
        assertContentEquals(
            byteArrayOf(1),
            reopened.readChunk(position)?.payload?.compressedBytes,
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
            val position = ChunkPosition(0, 0)
            store(base, directory).also {
                it.writeChunk(position, chunk(byteArrayOf(1)))
                it.close()
            }
            val failing = NthFlushFailingFileSystem(base, failureCall)
            val updating = WorldRegionStore(
                directory = directory,
                fileSystem = failing,
                configuration = WorldRegionStoreConfiguration(
                    syncWrites = true,
                ),
            )

            assertFailsWith<IOException> {
                updating.writeChunk(position, chunk(byteArrayOf(2)))
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
                reopened.readChunk(position)?.payload?.compressedBytes,
            )
            reopened.close()
        }

        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world-sync-clear/region".toPath()
        val position = ChunkPosition(0, 0)
        val sidecar = directory / "c.0.0.mcc"
        store(base, directory).also {
            it.writeChunk(position, chunk(externalPayload(13)))
            it.close()
        }
        val clearing = WorldRegionStore(
            directory = directory,
            fileSystem = NthFlushFailingFileSystem(base, failureCall = 1),
            configuration = WorldRegionStoreConfiguration(syncWrites = true),
        )

        assertFailsWith<IOException> { clearing.clearChunk(position) }
        clearing.close()

        assertTrue(base.exists(sidecar))
        val reopened = store(base, directory)
        assertNull(reopened.readChunk(position))
        reopened.close()
    }

    @Test
    fun failedExternalMoveKeepsTheCommittedStubAndOldSidecar() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val position = ChunkPosition(0, 0)
        val first = externalPayload(1)
        val second = externalPayload(2)
        store(base, directory).also {
            it.writeChunk(position, chunk(first))
            it.close()
        }
        val oldLocation = header(base, path).location(position.local)
        val failing = SidecarMoveFailingFileSystem(base)
        val store = store(failing, directory)
        val beforeWrite = Clock.System.now().epochSeconds.toInt()

        assertFailsWith<IOException> {
            store.writeChunk(position, chunk(second))
        }
        val afterWrite = Clock.System.now().epochSeconds.toInt()
        store.close()

        val committedHeader = header(base, path)
        assertNotEquals(oldLocation, committedHeader.location(position.local))
        assertTrue(
            committedHeader.timestamp(position.local) in
                    minOf(beforeWrite, afterWrite)..maxOf(beforeWrite, afterWrite),
        )
        assertContentEquals(first, base.readBytes(directory / "c.0.0.mcc"))
        assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })
        val reopened = store(base, directory)
        assertContentEquals(
            first,
            reopened.readChunk(position)?.payload?.compressedBytes,
        )
        reopened.close()
    }

    @Test
    fun failedFirstExternalMoveLeavesACommittedStubWithoutASidecar() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/first-external/region".toPath()
        val path = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val position = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeChunk(position, chunk(byteArrayOf(1)))
            it.close()
        }
        val oldLocation = header(base, path).location(position.local)
        val failingStore = store(
            SidecarMoveFailingFileSystem(base),
            directory,
        )

        assertFailsWith<IOException> {
            failingStore.writeChunk(position, chunk(externalPayload(12)))
        }
        failingStore.close()

        assertNotEquals(
            oldLocation,
            header(base, path).location(position.local),
        )
        assertFalse(base.exists(sidecar))
        assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })
        val reopened = store(base, directory)
        assertFailsWith<WorldIOException> {
            reopened.readChunk(position)
        }
        assertFalse(reopened.doesChunkExist(position))
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
            val position = ChunkPosition(0, 0)
            store(base, directory).also {
                it.writeChunk(position, chunk(byteArrayOf(1)))
                it.close()
            }
            val oldHeader = header(base, path)
            val failing = SidecarSinkFailingFileSystem(base, failurePoint)
            val store = store(failing, directory)

            assertFailsWith<IOException> {
                store.writeChunk(position, chunk(externalPayload(6)))
            }
            assertEquals(oldHeader, header(base, path))
            assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })

            store.writeChunk(position, chunk(byteArrayOf(2)))
            val oldLocation = checkNotNull(
                oldHeader.location(position.local),
            )
            assertEquals(
                oldLocation.sectorOffset + 1,
                header(base, path).location(position.local)?.sectorOffset,
                "Failure point $failurePoint did not reuse the disk allocation after last-release close",
            )
            store.close()

            val reopened = store(base, directory)
            assertContentEquals(
                byteArrayOf(2),
                reopened.readChunk(position)?.payload?.compressedBytes,
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
        val position = ChunkPosition(0, 0)
        val first = externalPayload(7)
        store(base, directory).also {
            it.writeChunk(position, chunk(first))
            it.close()
        }
        val oldHeader = header(base, path)
        val store = store(HeaderFailingFileSystem(base), directory)

        assertFailsWith<IOException> {
            store.writeChunk(position, chunk(externalPayload(8)))
        }
        store.close()

        assertEquals(oldHeader, header(base, path))
        assertContentEquals(first, base.readBytes(sidecar))
        assertTrue(base.allPaths.none { it.name.startsWith(".mcc-") })
    }

    @Test
    fun sidecarDeleteFailuresDoNotRollBackInternalWriteOrClear() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val position = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeChunk(position, chunk(externalPayload(3)))
            it.close()
        }

        val internalFailure = SidecarDeleteFailingFileSystem(base, sidecar)
        val internalStore = store(internalFailure, directory)
        assertFailsWith<IOException> {
            internalStore.writeChunk(position, chunk(byteArrayOf(9)))
        }
        internalStore.close()
        assertTrue(base.exists(sidecar))
        val committedInternal = store(base, directory)
        assertContentEquals(
            byteArrayOf(9),
            committedInternal.readChunk(position)?.payload?.compressedBytes,
        )
        committedInternal.writeChunk(position, chunk(externalPayload(4)))
        committedInternal.close()

        val clearFailure = SidecarDeleteFailingFileSystem(base, sidecar)
        val clearingStore = store(clearFailure, directory)
        assertFailsWith<IOException> {
            clearingStore.clearChunk(position)
        }
        clearingStore.close()
        assertNull(header(base, path).location(position.local))
        assertTrue(base.exists(sidecar))
        val reopened = store(base, directory)
        assertNull(reopened.readChunk(position))
        reopened.close()
    }

    @Test
    fun chunkReadAndWriteUseOnlyHeaderAndPositionalHandleOperations() = runTest {
        val base = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val directory = "/world/region".toPath()
        val path = directory / "r.0.0.mca"
        val position = ChunkPosition(0, 0)
        store(base, directory).also {
            it.writeChunk(position, chunk(ByteArray(8_000) { 1 }))
            it.close()
        }
        val fileSize = checkNotNull(base.metadata(path).size)
        val recording = HandleRecordingFileSystem(base)
        val store = store(recording, directory)

        assertContentEquals(
            ByteArray(8_000) { 1 },
            store.readChunk(position)?.payload?.compressedBytes,
        )
        store.writeChunk(position, chunk(byteArrayOf(7)))

        assertTrue(
            recording.reads.any {
                it.offset == 0L && it.byteCount == REGION_HEADER_BYTES
            },
        )
        assertFalse(
            recording.reads.any {
                it.offset == 0L && it.byteCount.toLong() >= fileSize
            },
        )
        assertTrue(
            recording.writes.any { it.offset >= REGION_HEADER_BYTES },
        )
        assertTrue(
            recording.writes.any {
                it.offset == 0L && it.byteCount == REGION_HEADER_BYTES
            },
        )
        store.close()
    }

    @Test
    fun unresolvedExternalWritesAreRejectedBeforeCreatingARegionFile() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val store = store(fileSystem, directory)
        assertFailsWith<RegionFormatException> {
            store.writeChunk(
                position,
                RegionChunk(
                    compression = Compression.NONE,
                    payload = RegionChunkPayload.External(),
                ),
            )
        }
        assertFailsWith<RegionFormatException> {
            store.writeRegion(
                position.region,
                RegionFile(
                    mapOf(
                        position.local to RegionChunk(
                            compression = Compression.NONE,
                            payload = RegionChunkPayload.External(),
                        ),
                    ),
                ),
            )
        }

        assertFalse(fileSystem.exists(directory / "r.0.0.mca"))
        store.close()
    }

    @Test
    fun externalReadsRejectMissingSidecarsWithoutImposingASizePolicy() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val sidecar = directory / "c.0.0.mcc"
        val payload = externalPayload(5)
        store(fileSystem, directory).also {
            it.writeChunk(position, chunk(payload))
            it.close()
        }
        fileSystem.delete(sidecar)

        val missing = store(fileSystem, directory)
        assertFailsWith<WorldIOException> {
            missing.readChunk(position)
        }
        missing.close()

        fileSystem.writeBytes(sidecar, payload)
        val reopened = store(fileSystem, directory)
        assertTrue(reopened.doesChunkExist(position))
        assertContentEquals(payload, checkNotNull(reopened.readChunk(position)).payload.compressedBytes)
        reopened.close()
    }

    @Test
    fun storesDoNotRetainIdleRegionHandlesAndCloseRejectsNewOperations() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )

        store.readRegion(RegionPosition(0, 0))
        assertEquals(0, fileSystem.openPaths.size)
        store.readRegion(RegionPosition(1, 0))
        assertFalse(fileSystem.exists(directory / "r.0.0.mca"))
        assertFalse(fileSystem.exists(directory / "r.1.0.mca"))
        assertEquals(0, fileSystem.openPaths.size)
        store.close()
        fileSystem.checkNoOpenFiles()
        assertFailsWith<IllegalStateException> {
            store.readChunk(ChunkPosition(0, 0))
        }
    }

    private fun store(
        fileSystem: FileSystem,
        directory: Path,
    ): WorldRegionStore = WorldRegionStore(
        directory = directory,
        fileSystem = fileSystem,
        configuration = WorldRegionStoreConfiguration(syncWrites = false),
    )

    private fun chunk(bytes: ByteArray): RegionChunk = RegionChunk(
        compression = Compression.NONE,
        payload = RegionChunkPayload.Inline(bytes),
    )
}

private class RecordingFileSystem(
    private val fake: FakeFileSystem,
) : ForwardingFileSystem(fake) {
    val moves = mutableListOf<Pair<Path, Path>>()

    override fun atomicMove(source: Path, target: Path) {
        moves += source to target
        super.atomicMove(source, target)
    }

    fun openPathsCount(): Long = fake.openPaths.size.toLong()
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
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.endsWith(".mca")) return handle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = handle.read(fileOffset, array, arrayOffset, byteCount)

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
                handle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() = handle.flush()

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() = handle.close()
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
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.endsWith(".mca")) return handle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = handle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) = handle.write(fileOffset, array, arrayOffset, byteCount)

            override fun protectedFlush() {
                flushCalls++
                handle.flush()
                if (flushCalls == failureCall) {
                    throw IOException("synthetic sync failure")
                }
            }

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() = handle.close()
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
    ) { handle, fileOffset, array, arrayOffset, byteCount ->
        if (fileOffset >= REGION_HEADER_BYTES && failRecord) {
            failRecord = false
            handle.write(fileOffset, array, arrayOffset, 1)
            throw IOException("synthetic record failure")
        }
        handle.write(fileOffset, array, arrayOffset, byteCount)
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
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        if (!file.name.endsWith(".mca")) return handle
        return object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int {
                reads += HandleOperation(fileOffset, byteCount)
                return handle.read(
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
                handle.write(fileOffset, array, arrayOffset, byteCount)
            }

            override fun protectedFlush() = handle.flush()

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() = handle.close()
        }
    }
}

private data class HandleOperation(
    val offset: Long,
    val byteCount: Int,
)

private fun interceptRegionHandle(
    handle: FileHandle,
    file: Path,
    write: (
        handle: FileHandle,
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ) -> Unit,
): FileHandle {
    if (!file.name.endsWith(".mca")) return handle
    return object : FileHandle(readWrite = true) {
        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int = handle.read(fileOffset, array, arrayOffset, byteCount)

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) = write(handle, fileOffset, array, arrayOffset, byteCount)

        override fun protectedFlush() = handle.flush()

        override fun protectedResize(size: Long) = handle.resize(size)

        override fun protectedSize(): Long = handle.size()

        override fun protectedClose() = handle.close()
    }
}

private fun header(fileSystem: FileSystem, path: Path): RegionHeader =
    RegionHeader.decode(
        fileSystem.readBytes(path).copyOfRange(0, REGION_HEADER_BYTES),
    )

private fun FileSystem.readBytes(path: Path): ByteArray =
    readFileBytes(path)

private fun FileSystem.writeBytes(path: Path, bytes: ByteArray) {
    path.parent?.let(::createDirectories)
    val handle = openReadWrite(path)
    try {
        handle.resize(0L)
        handle.write(0L, bytes, 0, bytes.size)
    } finally {
        handle.close()
    }
}

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
