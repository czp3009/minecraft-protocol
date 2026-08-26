package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.CompressedChunk
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/** Portable lifecycle and handle-failure coverage for region stores. */
class RegionHandleLifecycleTest {
    @Test
    fun useClosesARegionHandle() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val regionStorage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val regionHandle = regionStorage.openRegion(RegionPosition(0, 0))

        val result = regionHandle.use { suppliedRegionHandle ->
            assertSame(regionHandle, suppliedRegionHandle)
            assertFalse(suppliedRegionHandle.hasRegion())
            "result"
        }

        assertEquals("result", result)
        assertFailsWith<IllegalStateException> { regionHandle.hasRegion() }

        val failingRegionHandle = regionStorage.openRegion(RegionPosition(1, 0))
        val failure = assertFailsWith<IllegalArgumentException> {
            failingRegionHandle.use {
                throw IllegalArgumentException("block failure")
            }
        }
        assertEquals("block failure", failure.message)
        assertFailsWith<IllegalStateException> { failingRegionHandle.hasRegion() }

        regionStorage.close()
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun syncWritesFlushAtRecordHeaderExplicitFlushAndCloseBoundaries() = runTest {
        val base = FakeFileSystem()
        val flushRecordingFileSystem = FlushRecordingFileSystem(base)
        val directory = "/world/region".toPath()
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = flushRecordingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = true),
        )

        regionStorage.writeCompressedChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        assertEquals(3, flushRecordingFileSystem.flushes)
        regionStorage.flush()
        assertEquals(3, flushRecordingFileSystem.flushes)
        regionStorage.close()
        assertEquals(3, flushRecordingFileSystem.flushes)
        base.checkNoOpenFiles()
    }

    @Test
    fun nonSyncWriteFlushesOnceWhenItsLastEntryUserReleases() = runTest {
        val base = FakeFileSystem()
        val flushRecordingFileSystem = FlushRecordingFileSystem(base)
        val regionStorage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = flushRecordingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )

        regionStorage.writeCompressedChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        assertEquals(1, flushRecordingFileSystem.flushes)
        regionStorage.flush()
        assertEquals(1, flushRecordingFileSystem.flushes)
        regionStorage.close()
        assertEquals(1, flushRecordingFileSystem.flushes)
        base.checkNoOpenFiles()
    }

    @Test
    fun missingReadsCreateNeitherFilesNorIdleEntries() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val firstDirectory = "/world/region".toPath()
        val first = RegionStorage(
            directory = firstDirectory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        first.readAnvilRegion(RegionPosition(0, 0))
        first.readAnvilRegion(RegionPosition(1, 0))
        first.readAnvilRegion(RegionPosition(0, 0))
        first.readAnvilRegion(RegionPosition(2, 0))

        assertFalse(firstDirectory / "r.0.0.mca" in fakeFileSystem.allPaths)
        assertFalse(firstDirectory / "r.2.0.mca" in fakeFileSystem.allPaths)
        assertTrue(fakeFileSystem.openPaths.isEmpty())

        val secondDirectory = "/world/entities".toPath()
        val second = RegionStorage(
            directory = secondDirectory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        second.readAnvilRegion(RegionPosition(9, 9))
        assertFalse(secondDirectory / "r.9.9.mca" in fakeFileSystem.allPaths)
        assertTrue(fakeFileSystem.openPaths.isEmpty())

        first.close()
        assertEquals(0, fakeFileSystem.openPaths.size)
        second.close()
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun lastReleaseCloseFailureAllowsSameRegionReopenWithoutPoisoningLaterClose() = runTest {
        val base = FakeFileSystem()
        val finiteCloseFailingFileSystem = FiniteCloseFailingFileSystem(base, failures = 1)
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = finiteCloseFailingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )

        val operationFailure = assertFailsWith<IOException> {
            regionStorage.readAnvilRegion(RegionPosition(0, 0))
        }

        assertEquals("synthetic finite close failure", operationFailure.message)
        assertEquals(1, finiteCloseFailingFileSystem.closeAttempts)
        assertEquals(0, regionStorage.activeRegionCount())
        base.checkNoOpenFiles()
        assertTrue(base.exists(directory / "r.0.0.mca"))

        regionStorage.readAnvilRegion(RegionPosition(0, 0))
        assertEquals(2, finiteCloseFailingFileSystem.closeAttempts)
        assertEquals(0, regionStorage.activeRegionCount())
        assertTrue(base.openPaths.isEmpty())
        regionStorage.close()
        base.checkNoOpenFiles()
    }

    @Test
    fun eachLastReleaseReportsItsFlushAndCloseFailuresWithoutPoisoningStoreClose() = runTest {
        val base = FakeFileSystem()
        val closingFailingFileSystem = ClosingFailingFileSystem(base)
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        base.createEmptyRegion(directory, RegionPosition(1, 0))
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = closingFailingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val first = assertFailsWith<IOException> { regionStorage.readAnvilRegion(RegionPosition(0, 0)) }
        val second = assertFailsWith<IOException> { regionStorage.readAnvilRegion(RegionPosition(1, 0)) }

        regionStorage.close()

        assertEquals(2, closingFailingFileSystem.flushAttempts)
        assertEquals(2, closingFailingFileSystem.closeAttempts)
        assertEquals("synthetic flush failure", first.message)
        assertEquals("synthetic flush failure", second.message)
        assertEquals("synthetic close failure", first.suppressedExceptions.single().message)
        assertEquals("synthetic close failure", second.suppressedExceptions.single().message)
        base.checkNoOpenFiles()
        regionStorage.close()
        assertFailsWith<IllegalStateException> { regionStorage.flush() }
    }

    @Test
    fun lastReleaseStillFlushesAndClosesAfterPaddingFailure() = runTest {
        val base = FakeFileSystem()
        val resizeFailingFileSystem = ResizeFailingFileSystem(base)
        val regionStorage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = resizeFailingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        assertFailsWith<IOException> {
            regionStorage.writeCompressedChunk(ChunkPosition(0, 0), lifecycleChunk(1))
        }

        regionStorage.close()

        assertTrue(resizeFailingFileSystem.resizeAttempted)
        assertTrue(resizeFailingFileSystem.flushAttempted)
        assertTrue(resizeFailingFileSystem.closeAttempted)
        base.checkNoOpenFiles()
    }

    @Test
    fun openSizeFailureClosesTheHandleAndDoesNotCacheIt() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        val sizeFailingFileSystem = SizeFailingFileSystem(base, failureCall = 1)
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = sizeFailingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )

        assertFailsWith<IOException> {
            regionStorage.readAnvilRegion(RegionPosition(0, 0))
        }

        assertTrue(sizeFailingFileSystem.closeAttempted)
        base.checkNoOpenFiles()
        regionStorage.close()
    }

    @Test
    fun lastReleaseSizeFailureStillFlushesAndClosesWithoutPoisoningStoreClose() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        val sizeFailingFileSystem = SizeFailingFileSystem(base, failureCall = 2)
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = sizeFailingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        assertFailsWith<IOException> { regionStorage.readAnvilRegion(RegionPosition(0, 0)) }

        regionStorage.close()

        assertTrue(sizeFailingFileSystem.flushAttempted)
        assertTrue(sizeFailingFileSystem.closeAttempted)
        base.checkNoOpenFiles()
    }

    @Test
    fun eachLastReleaseReportsItsFlushFailureWithoutPoisoningStoreClose() = runTest {
        val base = FakeFileSystem()
        val directory = "/world/region".toPath()
        base.createEmptyRegion(directory, RegionPosition(0, 0))
        base.createEmptyRegion(directory, RegionPosition(1, 0))
        val finiteFlushFailingFileSystem = FiniteFlushFailingFileSystem(base, failures = 2)
        val regionStorage = RegionStorage(
            directory = directory,
            fileSystem = finiteFlushFailingFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val first = assertFailsWith<IOException> { regionStorage.readAnvilRegion(RegionPosition(0, 0)) }
        val second = assertFailsWith<IOException> { regionStorage.readAnvilRegion(RegionPosition(1, 0)) }

        regionStorage.close()

        assertEquals(2, finiteFlushFailingFileSystem.flushAttempts)
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
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        return wrapHandle(
            fileHandle,
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

private fun lifecycleChunk(value: Byte): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = byteArrayOf(value),
)

private fun lifecycleChunk(value: Int): CompressedChunk =
    lifecycleChunk(value.toByte())

private fun FileSystem.createEmptyRegion(
    directory: Path,
    regionPosition: RegionPosition,
) {
    createDirectories(directory)
    write(directory / "r.${regionPosition.x}.${regionPosition.z}.mca") {}
}
