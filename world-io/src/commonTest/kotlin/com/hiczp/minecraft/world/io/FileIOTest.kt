package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionCodec
import com.hiczp.minecraft.world.format.CompressionRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.okio.asOkioSource
import kotlinx.serialization.json.Json
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class FileIOTest {
    @Test
    fun terminalFormatCallsDoNotExposeKotlinxIoFailures() {
        val base = FakeFileSystem()
        val nbtPath = "/world/value.dat".toPath()
        val jsonPath = "/world/value.json".toPath()
        val nbtDocument = NbtDocument(NbtCompound(emptyMap()))
        base.createDirectories(checkNotNull(nbtPath.parent))
        NbtFileStore(base).writeDocument(nbtPath, nbtDocument, Compression.NONE)
        Utf8JsonFileStore(base).writeText(jsonPath, "{}")

        listOf(
            nbtPath to {
                NbtFileStore(TerminalReadFailingFileSystem(base, nbtPath))
                    .readDocument(nbtPath, Compression.NONE)
            },
            jsonPath to {
                Utf8JsonFileStore(TerminalReadFailingFileSystem(base, jsonPath))
                    .readJsonElement(jsonPath, json = Json)
            },
        ).forEach { (path, read) ->
            val failure = assertFails { read() }
            assertIs<IOException>(failure, "Unexpected public I/O failure for $path")
        }

        listOf(
            nbtPath to {
                NbtFileStore(TerminalWriteFailingFileSystem(base, nbtPath))
                    .writeDocument(nbtPath, nbtDocument, Compression.NONE)
            },
            jsonPath to {
                Utf8JsonFileStore(TerminalWriteFailingFileSystem(base, jsonPath))
                    .writeJsonElement(jsonPath, Json.parseToJsonElement("{}"), Json)
            },
        ).forEach { (path, write) ->
            val failure = assertFails { write() }
            assertIs<IOException>(failure, "Unexpected public I/O failure for $path")
        }

        base.checkNoOpenFiles()
    }

    @Test
    fun worldIoCompressionStreamsExposeKotlinxIoFailuresAsOkioFailures() {
        val readFailure = kotlinx.io.IOException("synthetic decompression I/O")
        val writeFailure = kotlinx.io.IOException("synthetic compression I/O")
        val compressionCodec = object : CompressionCodec {
            override fun compressingSink(sink: kotlinx.io.Sink): RawSink = object : RawSink {
                override fun write(source: kotlinx.io.Buffer, byteCount: Long) = throw writeFailure
                override fun flush() = Unit
                override fun close() = Unit
            }

            override fun decompressingSource(source: kotlinx.io.Source): RawSource = object : RawSource {
                override fun readAtMostTo(sink: kotlinx.io.Buffer, byteCount: Long): Long = throw readFailure
                override fun close() = Unit
            }
        }
        val fakeFileSystem = FakeFileSystem()
        val path = "/world/value.dat".toPath()
        fakeFileSystem.createDirectories(checkNotNull(path.parent))
        fakeFileSystem.write(path) { writeByte(0) }
        val nbtFileStore = NbtFileStore(
            fileSystem = fakeFileSystem,
            compressionCodecs = CompressionRegistry(mapOf(Compression.NONE to compressionCodec)),
        )

        val exposedReadFailure = assertFailsWith<IOException> {
            nbtFileStore.read(path, Compression.NONE) { source -> source.readByte() }
        }
        val readFailureThrowable: Throwable = readFailure
        assertTrue(exposedReadFailure === readFailureThrowable || exposedReadFailure.cause === readFailure)

        val exposedWriteFailure = assertFailsWith<IOException> {
            nbtFileStore.write(path, Compression.NONE) { sink -> sink.writeByte(1) }
        }
        val writeFailureThrowable: Throwable = writeFailure
        assertTrue(exposedWriteFailure === writeFailureThrowable || exposedWriteFailure.cause === writeFailure)
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun officialAdapterMapsKotlinxIoFailuresWithoutInterceptingCancellation() {
        val kotlinxFailure = kotlinx.io.IOException("synthetic kotlinx I/O")
        val failingSource = object : RawSource {
            override fun readAtMostTo(sink: kotlinx.io.Buffer, byteCount: Long): Long = throw kotlinxFailure
            override fun close() = Unit
        }.asOkioSource()
        val exposed = assertFailsWith<IOException> {
            failingSource.read(Buffer(), 1L)
        }
        val exposedFailure: Throwable = exposed

        assertTrue(
            exposedFailure === kotlinxFailure ||
                    exposed.cause === kotlinxFailure,
        )

        val cancellationException = CancellationException("boundary cancelled")
        val cancellingSource = object : RawSource {
            override fun readAtMostTo(sink: kotlinx.io.Buffer, byteCount: Long): Long = throw cancellationException
            override fun close() = Unit
        }.asOkioSource()
        assertSame(
            cancellationException,
            assertFailsWith<CancellationException> {
                cancellingSource.read(Buffer(), 1L)
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
    fun snapshotReadsRejectMissingDirectoriesAndUnderConsumption() {
        val fakeFileSystem = FakeFileSystem()
        val path = "/world/value.dat".toPath()

        assertFailsWith<WorldIOException> {
            fakeFileSystem.readFileBytes(path)
        }
        fakeFileSystem.createDirectories(path)
        assertFailsWith<WorldIOException> {
            fakeFileSystem.readFileBytes(path)
        }
        fakeFileSystem.delete(path)
        fakeFileSystem.writeRaw(path, byteArrayOf(1, 2, 3))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fakeFileSystem.readFileBytes(path),
        )
        assertFailsWith<WorldIOException> {
            fakeFileSystem.readFile(path) { _, _ -> }
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun boundedReadPreservesBlockFailureAndSuppressesCloseFailure() {
        val base = FakeFileSystem()
        val path = "/world/value.dat".toPath()
        base.writeRaw(path, byteArrayOf(1))
        val closeFailure = IllegalArgumentException("close")
        val closeFailingSourceFileSystem = CloseFailingSourceFileSystem(
            base,
            closeFailure,
        )
        val original = IllegalStateException("block")

        val thrown = assertFailsWith<IllegalStateException> {
            closeFailingSourceFileSystem.readFile(path) { _, _ -> throw original }
        }

        assertSame(original, thrown)
        assertSame(closeFailure, thrown.suppressedExceptions.single())

        val closeOnly = assertFailsWith<IllegalArgumentException> {
            closeFailingSourceFileSystem.readFile(path) { bufferedSource, _ ->
                bufferedSource.readByteArray(1)
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
    fun cleanupAggregationKeepsCancellationPrimaryAndRunsEveryCleanup() {
        val operationFailure = IllegalStateException("operation")
        val cancellationException = CancellationException("cleanup cancelled")
        var finalCleanupRan = false

        val thrown = assertFailsWith<CancellationException> {
            try {
                throw operationFailure
            } finally {
                closeAllPreserving(
                    operationFailure,
                    { throw cancellationException },
                    { finalCleanupRan = true },
                )
            }
        }

        assertSame(cancellationException, thrown)
        assertSame(operationFailure, thrown.suppressedExceptions.single())
        assertTrue(finalCleanupRan)
    }

    @Test
    fun nonCancellableCleanupKeepsTheExactThrownCancellationAndItsDiagnostics() = runTest {
        val operationFailure = IllegalStateException("operation")
        val cancellationException = CancellationException("cleanup cancelled")

        val result = collectCleanupFailure(operationFailure) {
            throw cancellationException
        }

        assertSame(cancellationException, result)
        assertSame(operationFailure, cancellationException.suppressedExceptions.single())
    }

    @Test
    fun resourceCloseCancellationOverridesBlockFailureWithoutLosingIt() {
        val operationFailure = IllegalStateException("operation")
        val cancellationException = CancellationException("resource close cancelled")

        val thrown = assertFailsWith<CancellationException> {
            useResource(Unit, { throw cancellationException }) {
                throw operationFailure
            }
        }

        assertSame(cancellationException, thrown)
        assertSame(operationFailure, thrown.suppressedExceptions.single())
    }

    @Test
    fun snapshotReadsRejectUnknownSizeGrowthAndShrinkage() {
        val base = FakeFileSystem()
        val path = "/world/value.dat".toPath()
        base.writeRaw(path, byteArrayOf(1, 2, 3))

        assertFailsWith<WorldIOException> {
            MetadataSizeFileSystem(base, path, reportedSize = null)
                .readFileBytes(path)
        }
        assertFailsWith<IOException> {
            MetadataSizeFileSystem(base, path, reportedSize = 2)
                .readFileBytes(path)
        }
        assertFails {
            MetadataSizeFileSystem(base, path, reportedSize = 4)
                .readFileBytes(path)
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun countingSinkTracksCountsFlushAndCloseOwnership() {
        val delegate = RecordingSink()
        val countingSink = CountingSink(
            delegate = delegate,
            closeDelegate = true,
        )
        val source = Buffer().apply { write(byteArrayOf(1, 2, 3)) }

        assertFailsWith<IllegalArgumentException> {
            countingSink.write(source, -1)
        }
        countingSink.write(source, 3)
        countingSink.flush()
        countingSink.close()

        assertContentEquals(
            byteArrayOf(1, 2, 3),
            delegate.buffer.readByteArray(),
        )
        assertEquals(3, countingSink.bytesWritten)
        assertEquals(1, delegate.flushes)
        assertTrue(delegate.closed)

        val unowned = RecordingSink()
        CountingSink(unowned).close()
        assertFalse(unowned.closed)
    }

    @Test
    fun uniqueTemporaryFilesUseTheirDirectoryAndIndependentHandles() {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/data".toPath()
        val first = fakeFileSystem.openUniqueTemporarySink(directory)
        val second = fakeFileSystem.openUniqueTemporaryHandle(directory)

        assertNotEquals(first.path, second.path)
        assertEquals(directory, first.path.parent)
        assertEquals(directory, second.path.parent)
        first.sink.close()
        second.fileHandle.close()
        assertTrue(fakeFileSystem.exists(first.path))
        assertTrue(fakeFileSystem.exists(second.path))
        fakeFileSystem.delete(first.path)
        fakeFileSystem.delete(second.path)
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun temporaryCreationRetriesCollisionsAndPropagatesUnrelatedIoFailures() {
        val base = FakeFileSystem()
        val temporarySinkFileSystem = TemporarySinkFileSystem(base, collisions = 2)
        val temporaryFileSink = temporarySinkFileSystem.openUniqueTemporarySink(
            "/sink".toPath(),
        )
        assertEquals(3, temporarySinkFileSystem.attempts)
        temporaryFileSink.sink.close()

        val temporaryHandleFileSystem = TemporaryHandleFileSystem(
            base,
            collisions = 2,
        )
        val temporaryFileHandle = temporaryHandleFileSystem.openUniqueTemporaryHandle(
            "/handle".toPath(),
        )
        assertEquals(3, temporaryHandleFileSystem.attempts)
        temporaryFileHandle.fileHandle.close()

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
        val temporarySinkFileSystem = TemporarySinkFileSystem(
            sinkBase,
            collisions = 256,
        )
        assertFailsWith<WorldIOException> {
            temporarySinkFileSystem.openUniqueTemporarySink("/sink".toPath())
        }
        assertEquals(256, temporarySinkFileSystem.attempts)
        sinkBase.checkNoOpenFiles()

        val handleBase = FakeFileSystem()
        val temporaryHandleFileSystem = TemporaryHandleFileSystem(
            handleBase,
            collisions = 256,
        )
        assertFailsWith<WorldIOException> {
            temporaryHandleFileSystem.openUniqueTemporaryHandle("/handle".toPath())
        }
        assertEquals(256, temporaryHandleFileSystem.attempts)
        handleBase.checkNoOpenFiles()
    }

    @Test
    fun replacementWithoutAnExistingTargetMovesDirectlyAndCleanupSuppresses() {
        val fakeFileSystem = FakeFileSystem()
        val temporary = "/world/.tmp-value".toPath()
        val target = "/world/value.dat".toPath()
        val backup = "/world/value.dat_old".toPath()
        fakeFileSystem.writeRaw(temporary, byteArrayOf(4, 5))

        fakeFileSystem.replaceWithBackup(temporary, target, backup)

        assertFalse(fakeFileSystem.exists(temporary))
        assertFalse(fakeFileSystem.exists(backup))
        assertContentEquals(
            byteArrayOf(4, 5),
            fakeFileSystem.readFileBytes(target),
        )
        fakeFileSystem.delete("/world/missing".toPath(), mustExist = false)

        val original = IllegalStateException("original")
        val deleteFailure = IllegalArgumentException("delete")
        DeleteFailingFileSystem(fakeFileSystem, target, deleteFailure)
            .deleteIfExistsPreserving(target, original)
        assertSame(deleteFailure, original.suppressedExceptions.single())
        assertTrue(fakeFileSystem.exists(target))
    }

    @Test
    fun temporaryCleanupDoesNotSuppressCancellation() {
        val fakeFileSystem = FakeFileSystem()
        val target = "/world/value.dat".toPath()
        fakeFileSystem.writeRaw(target, byteArrayOf(1))
        val operationFailure = IllegalStateException("operation")
        val cancellationException = CancellationException("delete cancelled")

        val thrown = assertFailsWith<CancellationException> {
            DeleteFailingFileSystem(fakeFileSystem, target, cancellationException)
                .deleteIfExistsPreserving(target, operationFailure)
        }

        assertSame(cancellationException, thrown)
        assertSame(operationFailure, thrown.suppressedExceptions.single())
        assertTrue(fakeFileSystem.exists(target))
    }

    @Test
    fun replacementRetriesDoNotConsumeCoroutineCancellation() {
        val base = FakeFileSystem()
        val temporary = "/world/.tmp-value".toPath()
        val target = "/world/value.dat".toPath()
        val backup = "/world/value.dat_old".toPath()
        base.writeRaw(temporary, byteArrayOf(2))
        base.writeRaw(target, byteArrayOf(1))
        val cancellationException = CancellationException("cancelled")
        val targetMoveFailingFileSystem = TargetMoveFailingFileSystem(
            base,
            target,
            cancellationException,
        )

        assertSame(
            cancellationException,
            assertFailsWith<CancellationException> {
                targetMoveFailingFileSystem.replaceWithBackup(temporary, target, backup)
            },
        )

        assertEquals(1, targetMoveFailingFileSystem.attempts)
        assertContentEquals(byteArrayOf(1), base.readFileBytes(target))
        assertContentEquals(byteArrayOf(2), base.readFileBytes(temporary))
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
        assertContentEquals(byteArrayOf(2), retryBase.readFileBytes(retryTarget))
        assertContentEquals(byteArrayOf(1), retryBase.readFileBytes(retryBackup))

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
            exhaustedBase.readFileBytes(exhaustedTarget),
        )
        assertContentEquals(
            byteArrayOf(2),
            exhaustedBase.readFileBytes(exhaustedTemporary),
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
        assertContentEquals(byteArrayOf(1), runtimeBase.readFileBytes(runtimeTarget))
        assertContentEquals(
            byteArrayOf(2),
            runtimeBase.readFileBytes(runtimeTemporary),
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
            val movePairThrowingFileSystem = MovePairThrowingFileSystem(
                base,
                source = temporary,
                target = target,
                failure = expected,
            )

            assertSame(
                expected,
                assertFails {
                    movePairThrowingFileSystem.replaceWithBackup(
                        temporary,
                        target,
                        backup,
                    )
                },
            )
            assertEquals(1, movePairThrowingFileSystem.attempts)
            assertFalse(base.exists(target))
            assertContentEquals(byteArrayOf(1), base.readFileBytes(backup))
            assertContentEquals(byteArrayOf(2), base.readFileBytes(temporary))
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
        val replacementAndTransientRollbackFileSystem = ReplacementAndTransientRollbackFileSystem(
            delegate = base,
            temporary = temporary,
            target = target,
            backup = backup,
            rollbackFailures = 2,
        )

        assertFailsWith<WorldIOException> {
            replacementAndTransientRollbackFileSystem.replaceWithBackup(temporary, target, backup)
        }

        assertEquals(10, replacementAndTransientRollbackFileSystem.replacementAttempts)
        assertEquals(3, replacementAndTransientRollbackFileSystem.rollbackAttempts)
        assertContentEquals(byteArrayOf(1), base.readFileBytes(target))
        assertFalse(base.exists(backup))
        assertContentEquals(byteArrayOf(2), base.readFileBytes(temporary))
    }
}

private class TerminalReadFailingFileSystem(
    delegate: FileSystem,
    private val target: Path,
) : ForwardingFileSystem(delegate) {
    override fun source(file: Path): Source {
        val source = super.source(file)
        if (file != target) return source
        return object : ForwardingSource(source) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                throw IOException("synthetic terminal format read failure")
        }
    }
}

private class TerminalWriteFailingFileSystem(
    delegate: FileSystem,
    private val target: Path,
) : ForwardingFileSystem(delegate) {
    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val sink = super.sink(file, mustCreate)
        if (file != target) return sink
        return object : Sink by sink {
            override fun write(source: Buffer, byteCount: Long): Unit =
                throw IOException("synthetic terminal format write failure")
        }
    }

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val fileHandle = super.openReadWrite(file, mustCreate, mustExist)
        if (file != target) return fileHandle
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
            ): Unit = throw IOException("synthetic terminal format write failure")

            override fun protectedFlush() = fileHandle.flush()

            override fun protectedResize(size: Long) = fileHandle.resize(size)

            override fun protectedSize(): Long = fileHandle.size()

            override fun protectedClose() = fileHandle.close()
        }
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
        val fileMetadata = super.metadataOrNull(path) ?: return null
        return if (path == target) {
            fileMetadata.copy(size = reportedSize)
        } else {
            fileMetadata
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
