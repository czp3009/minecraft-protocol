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
        )

        store.writeChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        assertEquals(3, fileSystem.flushes)
        store.flush()
        assertEquals(3, fileSystem.flushes)
        store.close()
        assertEquals(3, fileSystem.flushes)
        base.checkNoOpenFiles()
    }

    @Test
    fun nonSyncWriteFlushesOnceWhenItsLastEntryUserReleases() = runTest {
        val base = FakeFileSystem()
        val fileSystem = FlushRecordingFileSystem(base)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )

        store.writeChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        assertEquals(1, fileSystem.flushes)
        store.flush()
        assertEquals(1, fileSystem.flushes)
        store.close()
        assertEquals(1, fileSystem.flushes)
        base.checkNoOpenFiles()
    }

    @Test
    fun missingReadsCreateNeitherFilesNorIdleEntries() = runTest {
        val fileSystem = FakeFileSystem()
        val firstDirectory = "/world/region".toPath()
        val first = WorldRegionStore(
            directory = firstDirectory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        first.readRegion(RegionPosition(0, 0))
        first.readRegion(RegionPosition(1, 0))
        first.readRegion(RegionPosition(0, 0))
        first.readRegion(RegionPosition(2, 0))

        assertFalse(firstDirectory / "r.0.0.mca" in fileSystem.allPaths)
        assertFalse(firstDirectory / "r.2.0.mca" in fileSystem.allPaths)
        assertTrue(fileSystem.openPaths.isEmpty())

        val secondDirectory = "/world/entities".toPath()
        val second = WorldRegionStore(
            directory = secondDirectory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        second.readRegion(RegionPosition(9, 9))
        assertFalse(secondDirectory / "r.9.9.mca" in fileSystem.allPaths)
        assertTrue(fileSystem.openPaths.isEmpty())

        first.close()
        assertEquals(0, fileSystem.openPaths.size)
        second.close()
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun lastReleaseCloseFailureAllowsSameRegionReopenWithoutPoisoningLaterClose() = runTest {
        val base = FakeFileSystem()
        val fileSystem = FiniteCloseFailingFileSystem(base, failures = 1)
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )

        val operationFailure = assertFailsWith<IOException> {
            store.readRegion(RegionPosition(0, 0))
        }

        assertEquals("synthetic finite close failure", operationFailure.message)
        assertEquals(1, fileSystem.closeAttempts)
        assertEquals(0, store.activeRegionCount())
        base.checkNoOpenFiles()
        assertTrue(base.exists(directory / "r.0.0.mca"))

        store.readRegion(RegionPosition(0, 0))
        assertEquals(2, fileSystem.closeAttempts)
        assertEquals(0, store.activeRegionCount())
        assertTrue(base.openPaths.isEmpty())
        store.close()
        base.checkNoOpenFiles()
    }

    @Test
    fun eachLastReleaseReportsItsFlushAndCloseFailuresWithoutPoisoningStoreClose() = runTest {
        val base = FakeFileSystem()
        val fileSystem = ClosingFailingFileSystem(base)
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        base.createEmptyRegion(directory, RegionPosition(1, 0))
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        val first = assertFailsWith<IOException> { store.readRegion(RegionPosition(0, 0)) }
        val second = assertFailsWith<IOException> { store.readRegion(RegionPosition(1, 0)) }

        store.close()

        assertEquals(2, fileSystem.flushAttempts)
        assertEquals(2, fileSystem.closeAttempts)
        assertEquals("synthetic flush failure", first.message)
        assertEquals("synthetic flush failure", second.message)
        assertEquals("synthetic close failure", first.suppressedExceptions.single().message)
        assertEquals("synthetic close failure", second.suppressedExceptions.single().message)
        base.checkNoOpenFiles()
        store.close()
        assertFailsWith<IllegalStateException> { store.flush() }
    }

    @Test
    fun lastReleaseStillFlushesAndClosesAfterPaddingFailure() = runTest {
        val base = FakeFileSystem()
        val fileSystem = ResizeFailingFileSystem(base)
        val store = WorldRegionStore(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        assertFailsWith<IOException> {
            store.writeChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        }

        store.close()

        assertTrue(fileSystem.resizeAttempted)
        assertTrue(fileSystem.flushAttempted)
        assertTrue(fileSystem.closeAttempted)
        base.checkNoOpenFiles()
    }

    @Test
    fun openSizeFailureClosesTheHandleAndDoesNotCacheIt() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        val fileSystem = SizeFailingFileSystem(base, failureCall = 1)
        val store = WorldRegionStore(
            directory = directory,
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
    fun lastReleaseSizeFailureStillFlushesAndClosesWithoutPoisoningStoreClose() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        val fileSystem = SizeFailingFileSystem(base, failureCall = 2)
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        assertFailsWith<IOException> { store.readRegion(RegionPosition(0, 0)) }

        store.close()

        assertTrue(fileSystem.flushAttempted)
        assertTrue(fileSystem.closeAttempted)
        base.checkNoOpenFiles()
    }

    @Test
    fun eachLastReleaseReportsItsFlushFailureWithoutPoisoningStoreClose() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        base.createEmptyRegion(directory, RegionPosition(1, 0))
        val fileSystem = FiniteFlushFailingFileSystem(base, failures = 2)
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = false),
        )
        val first = assertFailsWith<IOException> { store.readRegion(RegionPosition(0, 0)) }
        val second = assertFailsWith<IOException> { store.readRegion(RegionPosition(1, 0)) }

        store.close()

        assertEquals(2, fileSystem.flushAttempts)
        assertEquals("synthetic finite flush failure", first.message)
        assertEquals("synthetic finite flush failure", second.message)
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
    compression = Compression.NONE,
    payload = RegionChunkPayload.Inline(byteArrayOf(value)),
)

private fun lifecycleChunk(value: Int): RegionChunk =
    lifecycleChunk(value.toByte())

private fun FileSystem.createEmptyRegion(
    directory: Path,
    position: RegionPosition,
) {
    createDirectories(directory)
    write(directory / "r.${position.x}.${position.z}.mca") {}
}
