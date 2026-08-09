package com.hiczp.minecraft.world.io

import kotlinx.coroutines.CancellationException
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class FileIOTest {
    @Test
    fun kotlinxIoBoundaryFailuresUseOkioWithoutInterceptingCancellation() {
        val kotlinxFailure = kotlinx.io.IOException("synthetic kotlinx I/O")

        val exposed = assertFailsWith<IOException> {
            withOkioIoExceptions("world-io boundary") {
                throw kotlinxFailure
            }
        }
        val exposedFailure: Throwable = exposed

        assertTrue(
            exposedFailure === kotlinxFailure ||
                    exposed.cause === kotlinxFailure,
        )

        val cancellation = CancellationException("boundary cancelled")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                withOkioIoExceptions("world-io boundary") {
                    throw cancellation
                }
            },
        )
    }

    @Test
    fun temporaryNamesUseFixedWidthBase36AndOptionalAffixes() {
        assertEquals(".tmp-0000000000000", temporaryFileName(0uL))
        assertEquals(
            ".tmp-3w5e11264sgsf",
            temporaryFileName(ULong.MAX_VALUE),
        )
        assertEquals(
            "prefix-0000000000001.suffix",
            temporaryFileName(1uL, "prefix-", ".suffix"),
        )
    }

    @Test
    fun boundedReadsRejectMissingDirectoriesOversizeAndUnderConsumption() {
        val fileSystem = FakeFileSystem()
        val path = "/world/value.dat".toPath()

        assertFailsWith<IllegalArgumentException> {
            fileSystem.readFileWithinLimit(path, -1)
        }
        assertFailsWith<WorldIOException> {
            fileSystem.readFileWithinLimit(path, 10)
        }
        fileSystem.createDirectories(path)
        assertFailsWith<WorldIOException> {
            fileSystem.readFileWithinLimit(path, 10)
        }
        fileSystem.delete(path)
        fileSystem.writeRaw(path, byteArrayOf(1, 2, 3))
        assertFailsWith<WorldIOException> {
            fileSystem.readFileWithinLimit(path, 2)
        }
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fileSystem.readFileWithinLimit(path, 3),
        )
        assertFailsWith<WorldIOException> {
            fileSystem.readFile(path, 3) { _, _ -> }
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun boundedReadPreservesBlockFailureAndSuppressesCloseFailure() {
        val base = FakeFileSystem()
        val path = "/world/value.dat".toPath()
        base.writeRaw(path, byteArrayOf(1))
        val closeFailure = IllegalArgumentException("close")
        val fileSystem = CloseFailingSourceFileSystem(
            base,
            closeFailure,
        )
        val original = IllegalStateException("block")

        val thrown = assertFailsWith<IllegalStateException> {
            fileSystem.readFile(path, 1) { _, _ -> throw original }
        }

        assertSame(original, thrown)
        assertSame(closeFailure, thrown.suppressedExceptions.single())

        val closeOnly = assertFailsWith<IllegalArgumentException> {
            fileSystem.readFile(path, 1) { source, _ ->
                source.readByteArray(1)
            }
        }
        assertSame(closeFailure, closeOnly)
    }

    @Test
    fun closeAggregationPreservesOriginalFailure() {
        val original = IllegalStateException("write")
        val closeFailure = IllegalArgumentException("close")

        val thrown = assertFailsWith<IllegalStateException> {
            try {
                throw original
            } finally {
                closeAllPreserving(original, { throw closeFailure })
            }
        }

        assertSame(original, thrown)
        assertSame(closeFailure, thrown.suppressedExceptions.single())
    }

    @Test
    fun closeAggregationUsesFirstCloseFailureAsPrimary() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        val thrown = assertFailsWith<IllegalStateException> {
            closeAllPreserving(
                failure = null,
                { throw first },
                { throw second },
            )
        }

        assertSame(first, thrown)
        assertSame(second, thrown.suppressedExceptions.single())
    }

    @Test
    fun boundedReadsRejectUnknownSizeGrowthAndShrinkage() {
        val base = FakeFileSystem()
        val path = "/world/value.dat".toPath()
        base.writeRaw(path, byteArrayOf(1, 2, 3))

        assertFailsWith<WorldIOException> {
            MetadataSizeFileSystem(base, path, reportedSize = null)
                .readFileWithinLimit(path, 3)
        }
        assertFailsWith<IOException> {
            MetadataSizeFileSystem(base, path, reportedSize = 2)
                .readFileWithinLimit(path, 2)
        }
        assertFails {
            MetadataSizeFileSystem(base, path, reportedSize = 4)
                .readFileWithinLimit(path, 4)
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun limitedSinkEnforcesCountsFlushAndCloseOwnership() {
        val delegate = RecordingSink()
        val sink = LimitedSink(
            delegate = delegate,
            maximumBytes = 2,
            closeDelegate = true,
        )
        val source = Buffer().apply { write(byteArrayOf(1, 2, 3)) }

        assertFailsWith<IllegalArgumentException> {
            LimitedSink(delegate, -1)
        }
        assertFailsWith<WorldIOException> {
            sink.write(source, -1)
        }
        sink.write(source, 2)
        assertFailsWith<WorldIOException> {
            sink.write(source, 1)
        }
        sink.flush()
        sink.close()

        assertContentEquals(
            byteArrayOf(1, 2),
            delegate.buffer.readByteArray(),
        )
        assertEquals(1, delegate.flushes)
        assertTrue(delegate.closed)

        val unowned = RecordingSink()
        LimitedSink(unowned, maximumBytes = 0).close()
        assertFalse(unowned.closed)
    }

    @Test
    fun uniqueTemporaryFilesUseTheirDirectoryAndIndependentHandles() {
        val fileSystem = FakeFileSystem()
        val directory = "/world/data".toPath()
        val first = fileSystem.openUniqueTemporarySink(directory)
        val second = fileSystem.openUniqueTemporaryHandle(directory)

        assertNotEquals(first.path, second.path)
        assertEquals(directory, first.path.parent)
        assertEquals(directory, second.path.parent)
        first.sink.close()
        second.handle.close()
        assertTrue(fileSystem.exists(first.path))
        assertTrue(fileSystem.exists(second.path))
        fileSystem.delete(first.path)
        fileSystem.delete(second.path)
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun temporaryCreationRetriesCollisionsAndPropagatesUnrelatedIoFailures() {
        val base = FakeFileSystem()
        val sinkFileSystem = TemporarySinkFileSystem(base, collisions = 2)
        val sink = sinkFileSystem.openUniqueTemporarySink(
            "/sink".toPath(),
        )
        assertEquals(3, sinkFileSystem.attempts)
        sink.sink.close()

        val handleFileSystem = TemporaryHandleFileSystem(
            base,
            collisions = 2,
        )
        val handle = handleFileSystem.openUniqueTemporaryHandle(
            "/handle".toPath(),
        )
        assertEquals(3, handleFileSystem.attempts)
        handle.handle.close()

        val sinkFailure = IOException("synthetic sink open failure")
        val failingSink = TemporarySinkFileSystem(
            base,
            failureWithoutFile = sinkFailure,
        )
        assertSame(
            sinkFailure,
            assertFailsWith<IOException> {
                failingSink.openUniqueTemporarySink("/failed-sink".toPath())
            },
        )
        assertEquals(1, failingSink.attempts)

        val handleFailure = IOException(
            "synthetic handle open failure",
        )
        val failingHandle = TemporaryHandleFileSystem(
            base,
            failureWithoutFile = handleFailure,
        )
        assertSame(
            handleFailure,
            assertFailsWith<IOException> {
                failingHandle.openUniqueTemporaryHandle(
                    "/failed-handle".toPath(),
                )
            },
        )
        assertEquals(1, failingHandle.attempts)
        base.checkNoOpenFiles()
    }

    @Test
    fun temporaryCreationReportsExhaustedCollisionBudgets() {
        val sinkBase = FakeFileSystem()
        val sinkFileSystem = TemporarySinkFileSystem(
            sinkBase,
            collisions = 256,
        )
        assertFailsWith<WorldIOException> {
            sinkFileSystem.openUniqueTemporarySink("/sink".toPath())
        }
        assertEquals(256, sinkFileSystem.attempts)
        sinkBase.checkNoOpenFiles()

        val handleBase = FakeFileSystem()
        val handleFileSystem = TemporaryHandleFileSystem(
            handleBase,
            collisions = 256,
        )
        assertFailsWith<WorldIOException> {
            handleFileSystem.openUniqueTemporaryHandle("/handle".toPath())
        }
        assertEquals(256, handleFileSystem.attempts)
        handleBase.checkNoOpenFiles()
    }

    @Test
    fun replacementWithoutAnExistingTargetMovesDirectlyAndCleanupSuppresses() {
        val fileSystem = FakeFileSystem()
        val temporary = "/world/.tmp-value".toPath()
        val target = "/world/value.dat".toPath()
        val backup = "/world/value.dat_old".toPath()
        fileSystem.writeRaw(temporary, byteArrayOf(4, 5))

        fileSystem.replaceWithBackup(temporary, target, backup)

        assertFalse(fileSystem.exists(temporary))
        assertFalse(fileSystem.exists(backup))
        assertContentEquals(
            byteArrayOf(4, 5),
            fileSystem.readFileWithinLimit(target, 2),
        )
        fileSystem.deleteIfExists("/world/missing".toPath())

        val original = IllegalStateException("original")
        val deleteFailure = IllegalArgumentException("delete")
        DeleteFailingFileSystem(fileSystem, target, deleteFailure)
            .deleteIfExistsPreserving(target, original)
        assertSame(deleteFailure, original.suppressedExceptions.single())
        assertTrue(fileSystem.exists(target))
    }

    @Test
    fun replacementRetriesDoNotConsumeCoroutineCancellation() {
        val base = FakeFileSystem()
        val temporary = "/world/.tmp-value".toPath()
        val target = "/world/value.dat".toPath()
        val backup = "/world/value.dat_old".toPath()
        base.writeRaw(temporary, byteArrayOf(2))
        base.writeRaw(target, byteArrayOf(1))
        val cancellation = CancellationException("cancelled")
        val fileSystem = TargetMoveFailingFileSystem(
            base,
            target,
            cancellation,
        )

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                fileSystem.replaceWithBackup(temporary, target, backup)
            },
        )

        assertEquals(1, fileSystem.attempts)
        assertContentEquals(byteArrayOf(1), base.readRaw(target))
        assertContentEquals(byteArrayOf(2), base.readRaw(temporary))
        assertFalse(base.exists(backup))
    }

    @Test
    fun replacementRetriesOnlyIoFailuresAndHonorsTheTenAttemptLimit() {
        val retryBase = FakeFileSystem()
        val retryTemporary = "/retry/.tmp-value".toPath()
        val retryTarget = "/retry/value.dat".toPath()
        val retryBackup = "/retry/value.dat_old".toPath()
        retryBase.writeRaw(retryTemporary, byteArrayOf(2))
        retryBase.writeRaw(retryTarget, byteArrayOf(1))
        val retrying = TargetMoveFailingFileSystem(
            retryBase,
            retryTarget,
            IOException("synthetic retryable failure"),
            failures = 2,
        )

        retrying.replaceWithBackup(
            retryTemporary,
            retryTarget,
            retryBackup,
        )

        assertEquals(3, retrying.attempts)
        assertContentEquals(byteArrayOf(2), retryBase.readRaw(retryTarget))
        assertContentEquals(byteArrayOf(1), retryBase.readRaw(retryBackup))

        val exhaustedBase = FakeFileSystem()
        val exhaustedTemporary = "/exhausted/.tmp-value".toPath()
        val exhaustedTarget = "/exhausted/value.dat".toPath()
        val exhaustedBackup = "/exhausted/value.dat_old".toPath()
        exhaustedBase.writeRaw(exhaustedTemporary, byteArrayOf(2))
        exhaustedBase.writeRaw(exhaustedTarget, byteArrayOf(1))
        val ioFailure = IOException("synthetic permanent failure")
        val exhausted = TargetMoveFailingFileSystem(
            exhaustedBase,
            exhaustedTarget,
            ioFailure,
        )

        val exhaustedFailure = assertFailsWith<WorldIOException> {
            exhausted.replaceWithBackup(
                exhaustedTemporary,
                exhaustedTarget,
                exhaustedBackup,
            )
        }

        assertEquals(10, exhausted.attempts)
        assertSame(ioFailure, exhaustedFailure.cause)
        assertContentEquals(
            byteArrayOf(1),
            exhaustedBase.readRaw(exhaustedTarget),
        )
        assertContentEquals(
            byteArrayOf(2),
            exhaustedBase.readRaw(exhaustedTemporary),
        )
        assertFalse(exhaustedBase.exists(exhaustedBackup))

        val runtimeBase = FakeFileSystem()
        val runtimeTemporary = "/runtime/.tmp-value".toPath()
        val runtimeTarget = "/runtime/value.dat".toPath()
        val runtimeBackup = "/runtime/value.dat_old".toPath()
        runtimeBase.writeRaw(runtimeTemporary, byteArrayOf(2))
        runtimeBase.writeRaw(runtimeTarget, byteArrayOf(1))
        val runtimeFailure = IllegalStateException("synthetic runtime failure")
        val runtime = TargetMoveFailingFileSystem(
            runtimeBase,
            runtimeTarget,
            runtimeFailure,
        )

        assertSame(
            runtimeFailure,
            assertFailsWith<IllegalStateException> {
                runtime.replaceWithBackup(
                    runtimeTemporary,
                    runtimeTarget,
                    runtimeBackup,
                )
            },
        )
        assertEquals(1, runtime.attempts)
        assertContentEquals(byteArrayOf(1), runtimeBase.readRaw(runtimeTarget))
        assertContentEquals(
            byteArrayOf(2),
            runtimeBase.readRaw(runtimeTemporary),
        )
        assertFalse(runtimeBase.exists(runtimeBackup))
    }

    @Test
    fun unexpectedFinalReplacementFailuresPropagateWithoutRollback() {
        listOf<Throwable>(
            CancellationException("replacement cancelled"),
            IllegalStateException("synthetic replacement failure"),
        ).forEachIndexed { index, expected ->
            val base = FakeFileSystem()
            val temporary = "/unexpected-$index/.tmp-value".toPath()
            val target = "/unexpected-$index/value.dat".toPath()
            val backup = "/unexpected-$index/value.dat_old".toPath()
            base.writeRaw(temporary, byteArrayOf(2))
            base.writeRaw(target, byteArrayOf(1))
            val fileSystem = MovePairThrowingFileSystem(
                base,
                source = temporary,
                target = target,
                failure = expected,
            )

            assertSame(
                expected,
                assertFails {
                    fileSystem.replaceWithBackup(
                        temporary,
                        target,
                        backup,
                    )
                },
            )
            assertEquals(1, fileSystem.attempts)
            assertFalse(base.exists(target))
            assertContentEquals(byteArrayOf(1), base.readRaw(backup))
            assertContentEquals(byteArrayOf(2), base.readRaw(temporary))
        }
    }

    @Test
    fun rollbackRetriesTransientIoFailuresAfterReplacementExhaustion() {
        val base = FakeFileSystem()
        val temporary = "/rollback/.tmp-value".toPath()
        val target = "/rollback/value.dat".toPath()
        val backup = "/rollback/value.dat_old".toPath()
        base.writeRaw(temporary, byteArrayOf(2))
        base.writeRaw(target, byteArrayOf(1))
        val fileSystem = ReplacementAndTransientRollbackFileSystem(
            delegate = base,
            temporary = temporary,
            target = target,
            backup = backup,
            rollbackFailures = 2,
        )

        assertFailsWith<WorldIOException> {
            fileSystem.replaceWithBackup(temporary, target, backup)
        }

        assertEquals(10, fileSystem.replacementAttempts)
        assertEquals(3, fileSystem.rollbackAttempts)
        assertContentEquals(byteArrayOf(1), base.readRaw(target))
        assertFalse(base.exists(backup))
        assertContentEquals(byteArrayOf(2), base.readRaw(temporary))
    }
}

private class RecordingSink : Sink {
    val buffer = Buffer()
    var flushes = 0
    var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        buffer.write(source, byteCount)
    }

    override fun flush() {
        flushes++
    }

    override fun timeout(): Timeout = buffer.timeout()

    override fun close() {
        closed = true
    }
}

private class CloseFailingSourceFileSystem(
    delegate: FileSystem,
    private val closeFailure: Throwable,
) : ForwardingFileSystem(delegate) {
    override fun source(file: Path) = object : ForwardingSource(
        super.source(file),
    ) {
        override fun close() {
            super.close()
            throw closeFailure
        }
    }
}

private class MetadataSizeFileSystem(
    delegate: FileSystem,
    private val target: Path,
    private val reportedSize: Long?,
) : ForwardingFileSystem(delegate) {
    override fun metadataOrNull(path: Path): FileMetadata? {
        val metadata = super.metadataOrNull(path) ?: return null
        return if (path == target) {
            metadata.copy(size = reportedSize)
        } else {
            metadata
        }
    }
}

private class DeleteFailingFileSystem(
    delegate: FileSystem,
    private val target: Path,
    private val failure: Throwable,
) : ForwardingFileSystem(delegate) {
    override fun delete(path: Path, mustExist: Boolean) {
        if (path == target) throw failure
        super.delete(path, mustExist)
    }
}

private class TemporarySinkFileSystem(
    delegate: FileSystem,
    private var collisions: Int = 0,
    private val failureWithoutFile: IOException? = null,
) : ForwardingFileSystem(delegate) {
    var attempts = 0

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (!file.name.startsWith(".tmp-")) {
            return super.sink(file, mustCreate)
        }
        attempts++
        failureWithoutFile?.let { throw it }
        if (collisions > 0) {
            collisions--
            super.sink(file, mustCreate).close()
            throw IOException("synthetic temporary sink collision")
        }
        return super.sink(file, mustCreate)
    }
}

private class TemporaryHandleFileSystem(
    delegate: FileSystem,
    private var collisions: Int = 0,
    private val failureWithoutFile: IOException? = null,
) : ForwardingFileSystem(delegate) {
    var attempts = 0

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        if (!file.name.startsWith(".tmp-")) {
            return super.openReadWrite(file, mustCreate, mustExist)
        }
        attempts++
        failureWithoutFile?.let { throw it }
        if (collisions > 0) {
            collisions--
            super.openReadWrite(file, mustCreate, mustExist).close()
            throw IOException("synthetic temporary handle collision")
        }
        return super.openReadWrite(file, mustCreate, mustExist)
    }
}

private class TargetMoveFailingFileSystem(
    delegate: FileSystem,
    private val target: Path,
    private val failure: Throwable,
    private var failures: Int = Int.MAX_VALUE,
) : ForwardingFileSystem(delegate) {
    var attempts = 0

    override fun atomicMove(source: Path, target: Path) {
        if (source == this.target && failures > 0) {
            attempts++
            failures--
            throw failure
        }
        if (source == this.target) {
            attempts++
        }
        super.atomicMove(source, target)
    }
}

private class MovePairThrowingFileSystem(
    delegate: FileSystem,
    private val source: Path,
    private val target: Path,
    private val failure: Throwable,
) : ForwardingFileSystem(delegate) {
    var attempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (source == this.source && target == this.target) {
            attempts++
            throw failure
        }
        super.atomicMove(source, target)
    }
}

private class ReplacementAndTransientRollbackFileSystem(
    delegate: FileSystem,
    private val temporary: Path,
    private val target: Path,
    private val backup: Path,
    private var rollbackFailures: Int,
) : ForwardingFileSystem(delegate) {
    var replacementAttempts = 0
        private set
    var rollbackAttempts = 0
        private set

    override fun atomicMove(source: Path, target: Path) {
        if (source == temporary && target == this.target) {
            replacementAttempts++
            throw IOException("synthetic replacement failure")
        }
        if (source == backup && target == this.target) {
            rollbackAttempts++
            if (rollbackFailures > 0) {
                rollbackFailures--
                throw IOException("synthetic rollback failure")
            }
        }
        super.atomicMove(source, target)
    }
}

private fun FileSystem.writeRaw(path: Path, bytes: ByteArray) {
    path.parent?.let(::createDirectories)
    val sink = sink(path)
    val buffer = Buffer().apply { write(bytes) }
    try {
        sink.write(buffer, bytes.size.toLong())
    } finally {
        sink.close()
    }
}

private fun FileSystem.readRaw(path: Path): ByteArray =
    readFileWithinLimit(path, Int.MAX_VALUE)
