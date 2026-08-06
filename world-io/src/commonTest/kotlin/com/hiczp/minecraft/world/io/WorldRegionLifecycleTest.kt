package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/** Portable lifecycle and handle-failure coverage for region stores. */
class WorldRegionLifecycleTest {
    @Test
    fun syncWritesFlushAtRecordHeaderExplicitFlushAndCloseBoundaries() = runTest {
        val base = FakeFileSystem()
        val fileSystem = FlushRecordingFileSystem(base)
        val directory = "/world/region".toPath()
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = true),
            currentEpochSeconds = { 1 },
        )

        store.writeChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        assertEquals(2, fileSystem.flushes)
        store.flush()
        assertEquals(3, fileSystem.flushes)
        store.close()
        assertEquals(4, fileSystem.flushes)
        base.checkNoOpenFiles()
    }

    @Test
    fun nonSyncWritesWaitForExplicitFlushAndClose() = runTest {
        val base = FakeFileSystem()
        val fileSystem = FlushRecordingFileSystem(base)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
            currentEpochSeconds = { 1 },
        )

        store.writeChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        assertEquals(0, fileSystem.flushes)
        store.flush()
        assertEquals(1, fileSystem.flushes)
        store.close()
        assertEquals(2, fileSystem.flushes)
    }

    @Test
    fun lruHitsPromoteToMruAndStoresOwnIndependentCaches() = runTest {
        val fileSystem = FakeFileSystem()
        val firstDirectory = "/world/region".toPath()
        val first = WorldRegionStore(
            directory = firstDirectory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(
                maximumOpenRegions = 2,
                syncWrites = false,
            ),
        )
        first.readRegion(RegionPosition(0, 0))
        first.readRegion(RegionPosition(1, 0))
        first.readRegion(RegionPosition(0, 0))
        first.readRegion(RegionPosition(2, 0))

        assertTrue(firstDirectory / "r.0.0.mca" in fileSystem.openPaths)
        assertTrue(firstDirectory / "r.2.0.mca" in fileSystem.openPaths)
        assertFalse(firstDirectory / "r.1.0.mca" in fileSystem.openPaths)

        val secondDirectory = "/world/entities".toPath()
        val second = WorldRegionStore(
            directory = secondDirectory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(
                maximumOpenRegions = 1,
                syncWrites = false,
            ),
        )
        second.readRegion(RegionPosition(9, 9))
        assertTrue(secondDirectory / "r.9.9.mca" in fileSystem.openPaths)
        assertEquals(3, fileSystem.openPaths.size)

        first.close()
        assertEquals(1, fileSystem.openPaths.size)
        second.close()
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun lruEvictionFailureDoesNotCacheTheRequestedRegion() = runTest {
        val base = FakeFileSystem()
        val fileSystem = FiniteCloseFailingFileSystem(base, failures = 1)
        val directory = "/world/region".toPath()
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(
                maximumOpenRegions = 1,
                syncWrites = false,
            ),
        )
        store.readRegion(RegionPosition(0, 0))

        assertFailsWith<IOException> {
            store.readRegion(RegionPosition(1, 0))
        }

        assertEquals(1, fileSystem.closeAttempts)
        base.checkNoOpenFiles()
        assertFalse(base.exists(directory / "r.1.0.mca"))

        store.readRegion(RegionPosition(1, 0))
        assertEquals(listOf(directory / "r.1.0.mca"), base.openPaths)
        store.close()
        base.checkNoOpenFiles()
    }

    @Test
    fun closeAggregatesEveryRegionFlushAndCloseFailure() = runTest {
        val base = FakeFileSystem()
        val fileSystem = ClosingFailingFileSystem(base)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        store.readRegion(RegionPosition(0, 0))
        store.readRegion(RegionPosition(1, 0))

        val failure = assertFailsWith<IOException> { store.close() }

        assertEquals(2, fileSystem.flushAttempts)
        assertEquals(2, fileSystem.closeAttempts)
        assertTrue(failure.suppressedExceptions.isNotEmpty())
        base.checkNoOpenFiles()
        store.close()
        assertFailsWith<IllegalStateException> { store.flush() }
    }

    @Test
    fun closeStillFlushesAndClosesAfterPaddingFailure() = runTest {
        val base = FakeFileSystem()
        val fileSystem = ResizeFailingFileSystem(base)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
            currentEpochSeconds = { 1 },
        )
        store.writeChunk(ChunkPosition(0, 0), lifecycleChunk(1))

        assertFailsWith<IOException> { store.close() }

        assertTrue(fileSystem.resizeAttempted)
        assertTrue(fileSystem.flushAttempted)
        assertTrue(fileSystem.closeAttempted)
        base.checkNoOpenFiles()
    }

    @Test
    fun openSizeFailureClosesTheHandleAndDoesNotCacheIt() = runTest {
        val base = FakeFileSystem()
        val fileSystem = SizeFailingFileSystem(base, failureCall = 1)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )

        assertFailsWith<IOException> {
            store.readRegion(RegionPosition(0, 0))
        }

        assertTrue(fileSystem.closeAttempted)
        base.checkNoOpenFiles()
        store.close()
    }

    @Test
    fun openRejectsANonProgressingHandleAndClosesIt() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        base.createDirectories(directory)
        base.write(directory / "r.0.0.mca") { writeByte(1) }
        val fileSystem = NonProgressingReadFileSystem(base)
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )

        assertFailsWith<WorldIOException> {
            store.readRegion(RegionPosition(0, 0))
        }

        assertTrue(fileSystem.closeAttempted)
        base.checkNoOpenFiles()
        store.close()
    }

    @Test
    fun closeSizeFailureStillFlushesAndCloses() = runTest {
        val base = FakeFileSystem()
        val fileSystem = SizeFailingFileSystem(base, failureCall = 2)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        store.readRegion(RegionPosition(0, 0))

        assertFailsWith<IOException> { store.close() }

        assertTrue(fileSystem.flushAttempted)
        assertTrue(fileSystem.closeAttempted)
        base.checkNoOpenFiles()
    }

    @Test
    fun flushAggregatesFailuresButAttemptsEveryOpenRegion() = runTest {
        val base = FakeFileSystem()
        val fileSystem = FiniteFlushFailingFileSystem(base, failures = 2)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        store.readRegion(RegionPosition(0, 0))
        store.readRegion(RegionPosition(1, 0))

        val failure = assertFailsWith<IOException> { store.flush() }

        assertEquals(2, fileSystem.flushAttempts)
        assertEquals(1, failure.suppressedExceptions.size)
        store.close()
        base.checkNoOpenFiles()
    }
}

private class FlushRecordingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    var flushes = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = wrapHandle(
        super.openReadWrite(file, mustCreate, mustExist),
        flush = {
            flushes++
            it.flush()
        },
    )
}

private class ClosingFailingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    var flushAttempts = 0
    var closeAttempts = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = wrapHandle(
        super.openReadWrite(file, mustCreate, mustExist),
        flush = {
            flushAttempts++
            it.flush()
            throw IOException("synthetic flush failure")
        },
        close = {
            closeAttempts++
            it.close()
            throw IOException("synthetic close failure")
        },
    )
}

private class ResizeFailingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    var resizeAttempted = false
    var flushAttempted = false
    var closeAttempted = false

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = wrapHandle(
        super.openReadWrite(file, mustCreate, mustExist),
        resize = { _, _ ->
            resizeAttempted = true
            throw IOException("synthetic resize failure")
        },
        flush = {
            flushAttempted = true
            it.flush()
        },
        close = {
            closeAttempted = true
            it.close()
        },
    )
}

private class FiniteFlushFailingFileSystem(
    delegate: FileSystem,
    private var failures: Int,
) : ForwardingFileSystem(delegate) {
    var flushAttempts = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = wrapHandle(
        super.openReadWrite(file, mustCreate, mustExist),
        flush = {
            flushAttempts++
            it.flush()
            if (failures > 0) {
                failures--
                throw IOException("synthetic finite flush failure")
            }
        },
    )
}

private class FiniteCloseFailingFileSystem(
    delegate: FileSystem,
    private var failures: Int,
) : ForwardingFileSystem(delegate) {
    var closeAttempts = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = wrapHandle(
        super.openReadWrite(file, mustCreate, mustExist),
        close = {
            closeAttempts++
            it.close()
            if (failures > 0) {
                failures--
                throw IOException("synthetic finite close failure")
            }
        },
    )
}

private class SizeFailingFileSystem(
    delegate: FileSystem,
    private val failureCall: Int,
) : ForwardingFileSystem(delegate) {
    private var sizeCalls = 0
    var flushAttempted = false
    var closeAttempted = false

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        return wrapHandle(
            handle,
            flush = {
                flushAttempted = true
                it.flush()
            },
            close = {
                closeAttempted = true
                it.close()
            },
            size = {
                sizeCalls++
                if (sizeCalls == failureCall) {
                    throw IOException("synthetic size failure")
                }
                it.size()
            },
        )
    }
}

private class NonProgressingReadFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    var closeAttempted = false

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = wrapHandle(
        super.openReadWrite(file, mustCreate, mustExist),
        close = {
            closeAttempted = true
            it.close()
        },
    ).let { handle ->
        object : FileHandle(readWrite = true) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = 0

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) = handle.write(fileOffset, array, arrayOffset, byteCount)

            override fun protectedFlush() = handle.flush()

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() = handle.close()
        }
    }
}

private fun wrapHandle(
    delegate: FileHandle,
    flush: (FileHandle) -> Unit = FileHandle::flush,
    resize: (FileHandle, Long) -> Unit = FileHandle::resize,
    close: (FileHandle) -> Unit = FileHandle::close,
    size: (FileHandle) -> Long = FileHandle::size,
): FileHandle = object : FileHandle(readWrite = true) {
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
    ) = delegate.write(fileOffset, array, arrayOffset, byteCount)

    override fun protectedFlush() = flush(delegate)

    override fun protectedResize(size: Long) = resize(delegate, size)

    override fun protectedSize(): Long = size(delegate)

    override fun protectedClose() = close(delegate)
}

private fun lifecycleChunk(value: Byte): RegionChunk = RegionChunk(
    compression = RegionCompression.NONE,
    payload = RegionChunkPayload.Inline(byteArrayOf(value)),
)

private fun lifecycleChunk(value: Int): RegionChunk =
    lifecycleChunk(value.toByte())
