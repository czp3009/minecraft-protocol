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
        val fileSystem = FakeFileSystem()
        val regionStorage = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
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
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun syncWritesFlushAtRecordHeaderExplicitFlushAndCloseBoundaries() = runTest {
        val base = FakeFileSystem()
        val fileSystem = FlushRecordingFileSystem(base)
        val directory = "/world/region".toPath()
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = true),
        )

        store.writeCompressedChunk(ChunkPosition(0, 0), lifecycleChunk(1))
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
        val store = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )

        store.writeCompressedChunk(ChunkPosition(0, 0), lifecycleChunk(1))
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
        val first = RegionStorage(
            directory = firstDirectory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        first.readAnvilRegion(RegionPosition(0, 0))
        first.readAnvilRegion(RegionPosition(1, 0))
        first.readAnvilRegion(RegionPosition(0, 0))
        first.readAnvilRegion(RegionPosition(2, 0))

        assertFalse(firstDirectory / "r.0.0.mca" in fileSystem.allPaths)
        assertFalse(firstDirectory / "r.2.0.mca" in fileSystem.allPaths)
        assertTrue(fileSystem.openPaths.isEmpty())

        val secondDirectory = "/world/entities".toPath()
        val second = RegionStorage(
            directory = secondDirectory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        second.readAnvilRegion(RegionPosition(9, 9))
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
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )

        val operationFailure = assertFailsWith<IOException> {
            store.readAnvilRegion(RegionPosition(0, 0))
        }

        assertEquals("synthetic finite close failure", operationFailure.message)
        assertEquals(1, fileSystem.closeAttempts)
        assertEquals(0, store.activeRegionCount())
        base.checkNoOpenFiles()
        assertTrue(base.exists(directory / "r.0.0.mca"))

        store.readAnvilRegion(RegionPosition(0, 0))
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
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        val first = assertFailsWith<IOException> { store.readAnvilRegion(RegionPosition(0, 0)) }
        val second = assertFailsWith<IOException> { store.readAnvilRegion(RegionPosition(1, 0)) }

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
        val store = RegionStorage(
            directory = "/world/region".toPath(),
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        assertFailsWith<IOException> {
            store.writeCompressedChunk(ChunkPosition(0, 0), lifecycleChunk(1))
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
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )

        assertFailsWith<IOException> {
            store.readAnvilRegion(RegionPosition(0, 0))
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
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        assertFailsWith<IOException> { store.readAnvilRegion(RegionPosition(0, 0)) }

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
        val store = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        val first = assertFailsWith<IOException> { store.readAnvilRegion(RegionPosition(0, 0)) }
        val second = assertFailsWith<IOException> { store.readAnvilRegion(RegionPosition(1, 0)) }

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

private fun lifecycleChunk(value: Byte): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = byteArrayOf(value),
)

private fun lifecycleChunk(value: Int): CompressedChunk =
    lifecycleChunk(value.toByte())

private fun FileSystem.createEmptyRegion(
    directory: Path,
    position: RegionPosition,
) {
    createDirectories(directory)
    write(directory / "r.${position.x}.${position.z}.mca") {}
}
