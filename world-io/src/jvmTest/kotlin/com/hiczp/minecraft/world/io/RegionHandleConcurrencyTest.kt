package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.*
import kotlin.uuid.Uuid

class RegionHandleConcurrencyTest {
    @Test
    fun writesToDifferentRegionFilesReachTheirCommitPointsConcurrently() = runTest {
        val directory = "/world/region".toPath()
        val firstPosition = ChunkPosition(0, 0)
        val secondPosition = ChunkPosition(32, 0)
        val firstGate = BlockingGate()
        val secondGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            writeGate = firstGate,
            additionalWriteGates = mapOf(directory / "r.1.0.mca" to secondGate),
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(firstPosition, concurrencyChunk(1))
            }
            jobs += first
            firstGate.awaitEntered()
            val second = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(secondPosition, concurrencyChunk(2))
            }
            jobs += second
            secondGate.awaitEntered()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(2, gatedFileSystem.activeWrites.get())
            assertEquals(2, gatedFileSystem.maximumConcurrentWrites.get())

            firstGate.open()
            secondGate.open()
            first.await()
            second.await()
            assertContentEquals(byteArrayOf(1), regionStorage.readCompressedChunk(firstPosition).bytesOrNull())
            assertContentEquals(byteArrayOf(2), regionStorage.readCompressedChunk(secondPosition).bytesOrNull())
            assertEquals(0, regionStorage.activeRegionCount())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                firstGate.open()
                secondGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun slowSameRegionReadKeepsOnePhysicalHandleForQueuedWriteWhileAnotherRegionProgresses() = runTest {
        val directory = "/world/region".toPath()
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory)
        val readGate = BlockingGate()
        val writeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = directory / "r.0.0.mca",
            readGate = readGate,
            writeGate = writeGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val sameFile = ChunkPosition(0, 0)
        val otherFile = ChunkPosition(32, 0)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val slow = async(Dispatchers.Default) { regionStorage.readCompressedChunk(sameFile) }
            jobs += slow
            readGate.awaitEntered()

            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.writeCompressedChunk(sameFile, concurrencyChunk(7))
            }
            jobs += queued
            assertFalse(queued.isCompleted)
            assertEquals(2, regionStorage.activeRegionUsers(sameFile.regionPosition))
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(0, gatedFileSystem.closes.get())

            val independent = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(otherFile, concurrencyChunk(9))
            }
            jobs += independent
            independent.await()
            assertFalse(queued.isCompleted)

            readGate.open()
            slow.await()
            writeGate.awaitEntered()
            assertFalse(queued.isCompleted)
            assertEquals(1, regionStorage.activeRegionUsers(sameFile.regionPosition))
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(0, gatedFileSystem.closes.get())

            writeGate.open()
            queued.await()
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(1, gatedFileSystem.closes.get())
            assertEquals(0, regionStorage.activeRegionCount())
            gatedFileSystem.base.checkNoOpenFiles()

            assertContentEquals(byteArrayOf(7), regionStorage.readCompressedChunk(sameFile).bytesOrNull())
            assertContentEquals(byteArrayOf(9), regionStorage.readCompressedChunk(otherFile).bytesOrNull())
            assertEquals(0, regionStorage.activeRegionCount())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                writeGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellingAWaitingWriterReleasesItsEntryPin() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory, chunkPosition)
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { regionStorage.readCompressedChunk(chunkPosition) }
            jobs += reader
            readGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.writeCompressedChunk(chunkPosition, concurrencyChunk(3))
            }
            jobs += writer
            assertEquals(2, regionStorage.activeRegionUsers(chunkPosition.regionPosition))

            writer.cancelAndJoin()
            assertEquals(1, regionStorage.activeRegionUsers(chunkPosition.regionPosition))
            readGate.open()
            reader.await()
            assertEquals(0, regionStorage.activeRegionCount())
            assertEquals(1, gatedFileSystem.closes.get())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun readersResumeConcurrentlyAsSoonAsWriterReleasesExclusiveAccess() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val writeGate = BlockingGate()
        val readGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = target,
            readGate = readGate,
            writeGate = writeGate,
            gateReadsInitially = false,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writer = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(ChunkPosition(0, 0), concurrencyChunk(4))
            }
            jobs += writer
            writeGate.awaitEntered()
            gatedFileSystem.enableReadGate()

            val firstReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(ChunkPosition(0, 0))
            }
            val secondReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(ChunkPosition(0, 0))
            }
            jobs += firstReader
            jobs += secondReader
            assertFalse(firstReader.isCompleted)
            assertFalse(secondReader.isCompleted)

            writeGate.open()
            writer.await()
            readGate.awaitEntered()
            assertEquals(2, gatedFileSystem.maximumConcurrentReads.get())
            assertEquals(0, gatedFileSystem.flushes.get())
            assertEquals(0, gatedFileSystem.closes.get())
            assertEquals(1, regionStorage.activeRegionCount())
            readGate.open()
            assertContentEquals(byteArrayOf(4), firstReader.await().bytesOrNull())
            assertContentEquals(byteArrayOf(4), secondReader.await().bytesOrNull())
            assertEquals(1, gatedFileSystem.flushes.get())
            assertEquals(1, gatedFileSystem.closes.get())
            assertEquals(0, regionStorage.activeRegionCount())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun syncWritesFlushEachCommitButCloseWaitsForTheLastReader() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val writeGate = BlockingGate()
        val readGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = target,
            readGate = readGate,
            writeGate = writeGate,
            gateReadsInitially = false,
        )
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = gatedFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = true),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writer = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(ChunkPosition(0, 0), concurrencyChunk(4))
            }
            jobs += writer
            writeGate.awaitEntered()
            gatedFileSystem.enableReadGate()
            val firstReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(ChunkPosition(0, 0))
            }
            val secondReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(ChunkPosition(0, 0))
            }
            jobs += firstReader
            jobs += secondReader

            writeGate.open()
            writer.await()
            readGate.awaitEntered()
            assertEquals(2, gatedFileSystem.flushes.get())
            assertEquals(0, gatedFileSystem.closes.get())
            readGate.open()
            firstReader.await()
            secondReader.await()
            assertEquals(3, gatedFileSystem.flushes.get())
            assertEquals(1, gatedFileSystem.closes.get())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun readersOfAnAlreadyOpenRegionUseTheHandleConcurrently() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbtDocument(ChunkPosition(0, 0), concurrencyDocument(12), Compression.NONE)
        setup.close()

        val decodeGate = BlockingGate()
        val readGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = target,
            readGate = readGate,
            gateReadsInitially = false,
        )
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = gatedFileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) {
                regionStorage.readChunkNbtDocument(ChunkPosition(0, 0))
            }
            jobs += decoding
            decodeGate.awaitEntered()
            gatedFileSystem.enableReadGate()

            val firstReader = async(Dispatchers.Default) { regionStorage.readCompressedChunk(ChunkPosition(0, 0)) }
            val secondReader = async(Dispatchers.Default) { regionStorage.readCompressedChunk(ChunkPosition(0, 0)) }
            jobs += firstReader
            jobs += secondReader
            readGate.awaitEntered()
            assertEquals(2, gatedFileSystem.maximumConcurrentReads.get())

            readGate.open()
            firstReader.await()
            secondReader.await()
            decodeGate.open()
            assertEquals(concurrencyDocument(12), decoding.await())
            assertEquals(0, regionStorage.activeRegionCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                decodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun flushKeepsCancellationPrimaryAndOnlyReleasesRemainingPins() = runTest {
        val directory = "/world/region".toPath()
        val positions = listOf(
            ChunkPosition(0, 0),
            ChunkPosition(32, 0),
            ChunkPosition(64, 0),
        )
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        positions.forEachIndexed { index, chunkPosition ->
            setup.writeChunkNbtDocument(chunkPosition, concurrencyDocument(index), Compression.NONE)
        }
        setup.close()

        val earlierFailure = IOException("synthetic flush failure before cancellation")
        val cancellationException = CancellationException("synthetic flush cancellation")
        val sequencedFlushFailureFileSystem = SequencedFlushFailureFileSystem(
            delegate = threadSafeFakeFileSystem(base),
            failures = listOf(earlierFailure, cancellationException),
        )
        val readGate = BlockingGate(expectedEntrants = positions.size)
        val encodeGate = BlockingGate(expectedEntrants = positions.size)
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = sequencedFlushFailureFileSystem,
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reading = positions.map { chunkPosition ->
                async(Dispatchers.Default) {
                    regionStorage.withCompressedChunkSource(chunkPosition) { _, source ->
                        readGate.awaitRelease()
                        source.readByteArray()
                    }
                }
            }
            jobs += reading
            readGate.awaitEntered()
            val encoding = positions.mapIndexed { index, chunkPosition ->
                async(Dispatchers.Default) {
                    regionStorage.writeChunkNbtDocument(
                        chunkPosition,
                        concurrencyDocument(index + 10),
                        Compression.NONE
                    )
                }
            }
            jobs += encoding
            encodeGate.awaitEntered()
            readGate.open()
            reading.awaitAll()

            val failure = assertFailsWith<CancellationException> { regionStorage.flush() }

            assertSame(cancellationException, failure)
            assertSame(earlierFailure, failure.suppressedExceptions.single())
            assertEquals(2, sequencedFlushFailureFileSystem.flushAttempts.get())
            positions.forEach { chunkPosition ->
                assertEquals(1, regionStorage.activeRegionUsers(chunkPosition.regionPosition))
            }

            encodeGate.open()
            encoding.awaitAll()
            assertEquals(0, regionStorage.activeRegionCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                encodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringTheLastPhysicalFlushIsObservedAfterPinCleanup() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbtDocument(chunkPosition, concurrencyDocument(7), Compression.NONE)
        setup.close()
        val bytesBeforeCancellation = base.read(target) { readByteArray() }

        val readGate = BlockingGate()
        val encodeGate = BlockingGate()
        val flushGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = target,
            flushGate = flushGate,
        )
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = gatedFileSystem,
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reading = async(Dispatchers.Default) {
                regionStorage.withCompressedChunkSource(chunkPosition) { _, source ->
                    readGate.awaitRelease()
                    source.readByteArray()
                }
            }
            jobs += reading
            readGate.awaitEntered()
            val encoding = async(Dispatchers.Default) {
                regionStorage.writeChunkNbtDocument(chunkPosition, concurrencyDocument(8), Compression.NONE)
            }
            jobs += encoding
            encodeGate.awaitEntered()
            readGate.open()
            assertNotNull(reading.await())

            val returned = CompletableDeferred<Unit>()
            val flushing = async(Dispatchers.Default) {
                regionStorage.flush()
                returned.complete(Unit)
            }
            jobs += flushing
            flushGate.awaitEntered()

            val cancellationException = CancellationException("cancelled during physical flush")
            flushing.cancel(cancellationException)
            flushGate.open()
            val failure = assertFailsWith<CancellationException> { flushing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertEquals(1, regionStorage.activeRegionUsers(chunkPosition.regionPosition))

            val holderCancellation = CancellationException("cancelled flush pin holder")
            encoding.cancel(holderCancellation)
            encodeGate.open()
            val holderFailure = assertFailsWith<CancellationException> { encoding.await() }
            assertEquals(holderCancellation.message, holderFailure.message)
            assertEquals(0, regionStorage.activeRegionCount())
            assertContentEquals(bytesBeforeCancellation, base.read(target) { readByteArray() })
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                flushGate.open()
                readGate.open()
                encodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringPhysicalWriteCompletesAValidCommitBeforeReleasingState() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeCompressedChunk(chunkPosition, concurrencyChunk(1))
        setup.close()

        val writeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, writeGate = writeGate)
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(chunkPosition, concurrencyChunk(9))
                returned.complete(Unit)
            }
            jobs += writing
            writeGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(chunkPosition).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(2, regionStorage.activeRegionUsers(chunkPosition.regionPosition))

            val cancellationException = CancellationException("cancelled during physical write")
            writing.cancel(cancellationException)
            writeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertContentEquals(byteArrayOf(9), reading.await().bytesOrNull())
            assertEquals(0, regionStorage.activeRegionCount())
            base.checkNoOpenFiles()

            val verifier = concurrencyStore(base)
            try {
                assertContentEquals(byteArrayOf(9), verifier.readCompressedChunk(chunkPosition).bytesOrNull())
            } finally {
                verifier.close()
            }
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringExternalWriteCompletesTheSidecarCommitAndRemovesItsTemporaryFile() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val regionPath = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val externalBytes = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 17 + 3).toByte() }
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeCompressedChunk(chunkPosition, concurrencyChunk(1))
        setup.close()

        val writeGate = BlockingGate()
        val regionStorage = concurrencyStore(GatedFileSystem(base, regionPath, writeGate = writeGate))
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(
                    chunkPosition,
                    CompressedChunk(
                        compression = Compression.NONE,
                        compressedBytes = externalBytes,
                    ),
                )
                returned.complete(Unit)
            }
            jobs += writing
            writeGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(chunkPosition).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(2, regionStorage.activeRegionUsers(chunkPosition.regionPosition))

            val cancellationException = CancellationException("cancelled during external chunk commit")
            writing.cancel(cancellationException)
            writeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertContentEquals(externalBytes, reading.await().bytesOrNull())
            assertEquals(0, regionStorage.activeRegionCount())
            assertTrue(base.exists(sidecar))
            assertTrue(base.list(directory).none { it.name.startsWith(".mcc-") })
            base.checkNoOpenFiles()

            val verifier = concurrencyStore(base)
            try {
                assertContentEquals(externalBytes, verifier.readCompressedChunk(chunkPosition).bytesOrNull())
            } finally {
                verifier.close()
            }
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringEncodingPreventsTheLaterPhysicalCommitAndReleasesState() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val original = concurrencyDocument(4)
        val setup = concurrencyStore(base)
        setup.writeChunkNbtDocument(chunkPosition, original, Compression.NONE)
        setup.close()
        val bytesBeforeCancellation = base.read(target) { readByteArray() }

        val encodeGate = BlockingGate()
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = GatedFileSystem(base, target),
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                regionStorage.writeChunkNbtDocument(chunkPosition, concurrencyDocument(8), Compression.NONE)
                returned.complete(Unit)
            }
            jobs += writing
            encodeGate.awaitEntered()

            val cancellationException = CancellationException("cancelled during chunk encoding")
            writing.cancel(cancellationException)
            encodeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertEquals(0, regionStorage.activeRegionCount())
            assertContentEquals(bytesBeforeCancellation, base.read(target) { readByteArray() })
            base.checkNoOpenFiles()

            val verifier = CoordinatedRegionStore(
                directory = directory,
                fileSystem = base,
                regionStorageConfiguration = concurrencyConfiguration(),
            )
            try {
                assertEquals(original, verifier.readChunkNbtDocument(chunkPosition))
            } finally {
                verifier.close()
            }
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                encodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancelledCloseOwnerFinishesCleanupWhileCancelledWaiterDoesNotAffectTheBarrier() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbtDocument(chunkPosition, concurrencyDocument(6), Compression.NONE)
        setup.close()
        val bytesBeforeClose = base.read(target) { readByteArray() }

        val decodeGate = BlockingGate()
        val flushGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, flushGate = flushGate)
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = gatedFileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) { regionStorage.readChunkNbtDocument(chunkPosition) }
            jobs += decoding
            decodeGate.awaitEntered()

            val ownerReturned = CompletableDeferred<Unit>()
            val owner = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.close()
                ownerReturned.complete(Unit)
            }
            val waiterReturned = CompletableDeferred<Unit>()
            val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.close()
                waiterReturned.complete(Unit)
            }
            jobs += owner
            jobs += waiter

            val waiterCancellation = CancellationException("close waiter cancelled")
            waiter.cancel(waiterCancellation)
            val waiterFailure = assertFailsWith<CancellationException> { waiter.await() }
            assertEquals(waiterCancellation.message, waiterFailure.message)
            assertFalse(waiterReturned.isCompleted)
            assertFalse(owner.isCompleted)

            decodeGate.open()
            flushGate.awaitEntered()
            val ownerCancellation = CancellationException("close owner cancelled")
            owner.cancel(ownerCancellation)
            flushGate.open()
            val ownerFailure = assertFailsWith<CancellationException> { owner.await() }

            assertEquals(ownerCancellation.message, ownerFailure.message)
            assertFalse(ownerReturned.isCompleted)
            assertEquals(concurrencyDocument(6), decoding.await())
            assertEquals(0, regionStorage.activeRegionCount())
            assertContentEquals(bytesBeforeClose, base.read(target) { readByteArray() })
            base.checkNoOpenFiles()

            regionStorage.close()
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                decodeGate.open()
                flushGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancelledCloseOwnerKeepsThePhysicalCleanupFailureForLaterCloseCallers() = runTest {
        supervisorScope {
            val directory = "/world/region".toPath()
            val chunkPosition = ChunkPosition(0, 0)
            val target = directory / "r.0.0.mca"
            val base = concurrencyFakeFileSystem()
            seedConcurrencyRegion(base, directory, chunkPosition)
            val closeGate = BlockingGate()
            val gatedFileSystem = GatedFileSystem(
                base = base,
                target = target,
                closeGate = closeGate,
                closeFailures = 1,
            )
            val regionStorage = concurrencyStore(gatedFileSystem)
            val jobs = mutableListOf<Deferred<*>>()
            try {
                val operation = async(Dispatchers.Default) {
                    runCatching { regionStorage.readCompressedChunk(chunkPosition) }
                }
                jobs += operation
                closeGate.awaitEntered()

                val returned = CompletableDeferred<Unit>()
                val observedCloseFailure = CompletableDeferred<Throwable>()
                val closing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        regionStorage.close()
                        returned.complete(Unit)
                    } catch (caught: Throwable) {
                        observedCloseFailure.complete(caught)
                        throw caught
                    }
                }
                jobs += closing
                val cancellationException = CancellationException("close owner cancelled before cleanup failure")
                closing.cancel(cancellationException)
                closeGate.open()

                val operationFailure = assertIs<IOException>(operation.await().exceptionOrNull())
                val closeCancellation = assertFailsWith<CancellationException> { closing.await() }
                val observedCancellation = assertIs<CancellationException>(observedCloseFailure.await())
                assertEquals(cancellationException.message, closeCancellation.message)
                assertSame(operationFailure, observedCancellation.suppressedExceptions.single())
                assertFalse(returned.isCompleted)
                assertEquals(0, regionStorage.activeRegionCount())
                base.checkNoOpenFiles()

                val laterFailure = assertFailsWith<IOException> { regionStorage.close() }
                assertSame(operationFailure, laterFailure)
                assertFailsWith<IllegalStateException> { regionStorage.readCompressedChunk(chunkPosition) }
            } finally {
                withContext(NonCancellable) {
                    closeGate.open()
                    jobs.joinAll()
                    runCatching { regionStorage.close() }
                    base.checkNoOpenFiles()
                }
            }
        }
    }

    @Test
    fun mcaHeaderAndExternalSidecarsShareOneExclusiveBoundary() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val sidecar = directory / "c.0.0.mcc"
        val externalBytes = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 13 + 5).toByte() }
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeCompressedChunk(
            chunkPosition,
            CompressedChunk(
                compression = Compression.NONE,
                compressedBytes = externalBytes,
            ),
        )
        setup.close()

        val sourceGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, sidecar, sourceGate = sourceGate)
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { regionStorage.readCompressedChunk(chunkPosition) }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.writeCompressedChunk(chunkPosition, concurrencyChunk(6))
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(2, regionStorage.activeRegionUsers(chunkPosition.regionPosition))

            sourceGate.open()
            assertContentEquals(externalBytes, reader.await().bytesOrNull())
            writer.await()
            assertFalse(base.exists(sidecar))
            assertContentEquals(byteArrayOf(6), regionStorage.readCompressedChunk(chunkPosition).bytesOrNull())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun concurrentFirstAccessOpensARegionOnceAndReleasesIt() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory)
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, readGate = readGate)
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            jobs += async(Dispatchers.Default) { regionStorage.readCompressedChunk(ChunkPosition(0, 0)) }
            readGate.awaitEntered()
            repeat(8) { index ->
                jobs += async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    regionStorage.readCompressedChunk(ChunkPosition(index + 1, 0))
                }
            }
            assertEquals(9, regionStorage.activeRegionUsers(RegionPosition(0, 0)))

            readGate.open()
            jobs.awaitAll()
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(1, gatedFileSystem.closes.get())
            assertEquals(0, regionStorage.activeRegionCount())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun concurrentWritesToOneRegionRemainComplete() = runTest {
        val directory = "/world/region".toPath()
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            jobs += async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(ChunkPosition(0, 0), concurrencyChunk(0))
            }
            readGate.awaitEntered()
            repeat(15) { index ->
                jobs += async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    val value = (index + 1).toByte()
                    regionStorage.writeCompressedChunk(ChunkPosition(index + 1, 0), concurrencyChunk(value))
                }
            }
            assertEquals(16, regionStorage.activeRegionUsers(RegionPosition(0, 0)))

            readGate.open()
            jobs.awaitAll()
            assertEquals(1, gatedFileSystem.maximumConcurrentWrites.get())
            repeat(16) { index ->
                assertContentEquals(
                    byteArrayOf(index.toByte()),
                    regionStorage.readCompressedChunk(ChunkPosition(index, 0)).bytesOrNull(),
                )
            }
            assertEquals(0, regionStorage.activeRegionCount())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun nonSyncQueuedWritesFlushOnceAtTheFinalRelease() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(concurrencyFakeFileSystem(), target, readGate = readGate)
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(ChunkPosition(0, 0), concurrencyChunk(1))
            }
            jobs += first
            readGate.awaitEntered()
            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.writeCompressedChunk(ChunkPosition(1, 0), concurrencyChunk(2))
            }
            jobs += queued
            assertEquals(2, regionStorage.activeRegionUsers(RegionPosition(0, 0)))

            readGate.open()
            jobs.awaitAll()
            assertEquals(1, gatedFileSystem.flushes.get())
            assertEquals(1, gatedFileSystem.closes.get())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun lastReleaseCloseAndImmediateReopenDoNotOverlap() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory)
        val closeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, closeGate = closeGate)
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { regionStorage.readCompressedChunk(ChunkPosition(0, 0)) }
            jobs += first
            closeGate.awaitEntered()

            val reopen = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(ChunkPosition(0, 0))
            }
            jobs += reopen
            assertFalse(reopen.isCompleted)
            assertEquals(1, gatedFileSystem.opens.get())

            closeGate.open()
            first.await()
            reopen.await()
            assertEquals(2, gatedFileSystem.opens.get())
            assertEquals(2, gatedFileSystem.closes.get())
            val firstCloseEnd = gatedFileSystem.events.indexOf("close-end")
            val secondOpen = gatedFileSystem.events.lastIndexOf("open")
            assertTrue(firstCloseEnd in 0 until secondOpen)
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeFailureUnblocksSameRegionReopenWithoutPoisoningLaterStoreClose() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory, chunkPosition)
        val closeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = target,
            closeGate = closeGate,
            closeFailures = 1,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                runCatching { regionStorage.readCompressedChunk(chunkPosition) }
            }
            jobs += first
            closeGate.awaitEntered()

            val reopen = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(chunkPosition)
            }
            jobs += reopen
            assertFalse(reopen.isCompleted)
            assertEquals(1, gatedFileSystem.opens.get())

            closeGate.open()
            val operationFailure = assertIs<IOException>(first.await().exceptionOrNull())
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertContentEquals(byteArrayOf(0), reopen.await().bytesOrNull())

            assertEquals(2, gatedFileSystem.opens.get())
            assertEquals(2, gatedFileSystem.closes.get())
            assertEquals(0, regionStorage.activeRegionCount())
            val firstCloseEnd = gatedFileSystem.events.indexOf("close-end")
            val secondOpen = gatedFileSystem.events.lastIndexOf("open")
            assertTrue(firstCloseEnd in 0 until secondOpen)
            gatedFileSystem.base.checkNoOpenFiles()

            regionStorage.close()
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeBarrierAndConcurrentWaiterObserveLastReleaseCloseFailure() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory, chunkPosition)
        val closeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = target,
            closeGate = closeGate,
            closeFailures = 1,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val operation = async(Dispatchers.Default) {
                runCatching { regionStorage.readCompressedChunk(chunkPosition) }
            }
            jobs += operation
            closeGate.awaitEntered()

            val firstClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                runCatching { regionStorage.close() }
            }
            val secondClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                runCatching { regionStorage.close() }
            }
            jobs += firstClose
            jobs += secondClose
            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)
            assertFailsWith<IllegalStateException> { regionStorage.readCompressedChunk(chunkPosition) }

            closeGate.open()
            val operationFailure = assertIs<IOException>(operation.await().exceptionOrNull())
            val firstCloseFailure = assertIs<IOException>(firstClose.await().exceptionOrNull())
            val secondCloseFailure = assertIs<IOException>(secondClose.await().exceptionOrNull())
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertSame(operationFailure, firstCloseFailure)
            assertSame(operationFailure, secondCloseFailure)
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(1, gatedFileSystem.closes.get())
            gatedFileSystem.base.checkNoOpenFiles()

            val laterCloseFailure = assertFailsWith<IOException> { regionStorage.close() }
            assertSame(operationFailure, laterCloseFailure)
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                runCatching { regionStorage.close() }
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForAdmittedWaitingReadersThenRejectsNewReaders() = runTest {
        val directory = "/world/region".toPath()
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory)
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { regionStorage.readCompressedChunk(ChunkPosition(0, 0)) }
            jobs += first
            readGate.awaitEntered()
            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.readCompressedChunk(ChunkPosition(1, 0))
            }
            jobs += queued
            assertEquals(2, regionStorage.activeRegionUsers(RegionPosition(0, 0)))

            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionStorage.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> {
                regionStorage.readCompressedChunk(ChunkPosition(2, 0))
            }

            readGate.open()
            first.await()
            queued.await()
            close.await()
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForAWriterAdmittedBehindAnActiveReader() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory, chunkPosition)
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { regionStorage.readCompressedChunk(chunkPosition) }
            jobs += reader
            readGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionStorage.writeCompressedChunk(chunkPosition, concurrencyChunk(5))
            }
            jobs += writer
            assertEquals(2, regionStorage.activeRegionUsers(chunkPosition.regionPosition))
            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionStorage.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> { regionStorage.readCompressedChunk(chunkPosition) }

            readGate.open()
            reader.await()
            writer.await()
            close.await()
            assertEquals(1, gatedFileSystem.closes.get())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun concurrentClosesCoalesceAroundOneCompletion() = runTest {
        val directory = "/world/region".toPath()
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory)
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { regionStorage.readCompressedChunk(ChunkPosition(0, 0)) }
            jobs += reader
            readGate.awaitEntered()
            val firstClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionStorage.close() }
            val secondClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionStorage.close() }
            jobs += firstClose
            jobs += secondClose
            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)

            readGate.open()
            reader.await()
            firstClose.await()
            secondClose.await()
            assertEquals(1, gatedFileSystem.closes.get())
            gatedFileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun metadataReadsWaitForChunkCommitAndReturnDetachedSnapshots() = runTest {
        val directory = "/world/region".toPath()
        val regionPosition = RegionPosition(0, 0)
        val firstPosition = LocalChunkPosition(0, 0)
        val secondPosition = LocalChunkPosition(1, 0)
        val writeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            writeGate = writeGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val regionHandle = regionStorage.openRegion(regionPosition)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val write = async(Dispatchers.Default) {
                regionHandle.writeCompressedChunk(firstPosition, concurrencyChunk(1))
            }
            jobs += write
            writeGate.awaitEntered()

            val chunkCount = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionHandle.readChunkCount()
            }
            val localChunkPositions = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionHandle.readLocalChunkPositions()
            }
            val hasChunk = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionHandle.hasChunk(firstPosition)
            }
            jobs += listOf(chunkCount, localChunkPositions, hasChunk)

            assertFalse(chunkCount.isCompleted)
            assertFalse(localChunkPositions.isCompleted)
            assertFalse(hasChunk.isCompleted)

            writeGate.open()
            write.await()
            assertEquals(1, chunkCount.await())
            val firstSnapshot = localChunkPositions.await()
            assertEquals(listOf(firstPosition), firstSnapshot)
            assertTrue(hasChunk.await())

            regionHandle.writeCompressedChunk(secondPosition, concurrencyChunk(2))
            assertEquals(listOf(firstPosition), firstSnapshot)
            assertEquals(2, regionHandle.readChunkCount())
            assertEquals(listOf(firstPosition, secondPosition), regionHandle.readLocalChunkPositions())
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                regionHandle.close()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun explicitRegionSerializesWritesWhileKeepingOneHandleOpen() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val writeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = target,
            writeGate = writeGate,
        )
        val regionStorage = concurrencyStore(gatedFileSystem)
        val regionHandle = regionStorage.openRegion(RegionPosition(0, 0))
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                regionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(1))
            }
            jobs += first
            writeGate.awaitEntered()
            val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                regionHandle.writeCompressedChunk(LocalChunkPosition(1, 0), concurrencyChunk(2))
            }
            jobs += second

            assertFalse(second.isCompleted)
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(0, gatedFileSystem.closes.get())
            assertEquals(1, regionStorage.activeRegionUsers(regionHandle.regionPosition))

            writeGate.open()
            jobs.awaitAll()
            assertEquals(1, gatedFileSystem.maximumConcurrentWrites.get())
            assertContentEquals(
                byteArrayOf(1),
                regionHandle.readCompressedChunk(LocalChunkPosition(0, 0)).bytesOrNull(),
            )
            assertContentEquals(
                byteArrayOf(2),
                regionHandle.readCompressedChunk(LocalChunkPosition(1, 0)).bytesOrNull(),
            )
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(0, gatedFileSystem.closes.get())

            regionHandle.close()
            assertEquals(1, gatedFileSystem.closes.get())
            assertEquals(0, regionStorage.activeRegionCount())
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                regionHandle.close()
                regionStorage.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun explicitRegionAndStoreCloseWaitForAnAdmittedRead() = runTest {
        val directory = "/world/region".toPath()
        val chunkPosition = ChunkPosition(0, 0)
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, directory, chunkPosition)
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, directory / "r.0.0.mca", readGate = readGate)
        val regionStorage = concurrencyStore(gatedFileSystem)
        val regionHandle = regionStorage.openRegion(chunkPosition.regionPosition)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reading =
                async(Dispatchers.Default) { regionHandle.readCompressedChunk(chunkPosition.localChunkPosition) }
            jobs += reading
            readGate.awaitEntered()

            val regionClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionHandle.close() }
            val storeClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionStorage.close() }
            jobs += regionClose
            jobs += storeClose
            assertFalse(regionClose.isCompleted)
            assertFalse(storeClose.isCompleted)
            assertFailsWith<IllegalStateException> { regionHandle.readCompressedChunk(chunkPosition.localChunkPosition) }
            assertFailsWith<IllegalStateException> { regionStorage.readCompressedChunk(chunkPosition) }

            readGate.open()
            assertContentEquals(byteArrayOf(0), reading.await().bytesOrNull())
            regionClose.await()
            storeClose.await()
            assertEquals(1, gatedFileSystem.opens.get())
            assertEquals(1, gatedFileSystem.closes.get())
            assertEquals(0, regionStorage.activeRegionCount())
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                regionHandle.close()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun nbtEncodingDoesNotHoldTheRegionFileMutex() = runTest {
        val directory = "/world/region".toPath()
        val encodeGate = BlockingGate()
        val base = concurrencyFakeFileSystem()
        val gatedFileSystem = GatedFileSystem(base, directory / "r.0.0.mca")
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = gatedFileSystem,
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val encoding = async(Dispatchers.Default) {
                regionStorage.writeChunkNbtDocument(ChunkPosition(0, 0), concurrencyDocument(42), Compression.NONE)
            }
            jobs += encoding
            encodeGate.awaitEntered()
            val sameFile = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(ChunkPosition(1, 0), concurrencyChunk(8))
            }
            jobs += sameFile
            sameFile.await()
            assertFalse(encoding.isCompleted)
            assertEquals(0, gatedFileSystem.flushes.get())
            assertEquals(0, gatedFileSystem.closes.get())
            assertEquals(1, regionStorage.activeRegionCount())

            encodeGate.open()
            encoding.await()
            assertEquals(1, gatedFileSystem.flushes.get())
            assertEquals(1, gatedFileSystem.closes.get())
            assertEquals(concurrencyDocument(42), regionStorage.readChunkNbtDocument(ChunkPosition(0, 0)))
            assertContentEquals(byteArrayOf(8), regionStorage.readCompressedChunk(ChunkPosition(1, 0)).bytesOrNull())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                encodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun streamingNbtDecodingHoldsSharedFileAccessAgainstWriters() = runTest {
        val directory = "/world/region".toPath()
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbtDocument(ChunkPosition(0, 0), concurrencyDocument(42), Compression.NONE)
        setup.close()

        val decodeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, directory / "r.0.0.mca")
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = gatedFileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) {
                regionStorage.readChunkNbtDocument(ChunkPosition(0, 0))
            }
            jobs += decoding
            decodeGate.awaitEntered()
            val sameFile = async(Dispatchers.Default) {
                regionStorage.writeCompressedChunk(ChunkPosition(1, 0), concurrencyChunk(8))
            }
            jobs += sameFile
            assertFalse(sameFile.isCompleted)
            assertFalse(decoding.isCompleted)
            assertEquals(0, gatedFileSystem.flushes.get())
            assertEquals(0, gatedFileSystem.closes.get())
            assertEquals(1, regionStorage.activeRegionCount())

            decodeGate.open()
            assertEquals(concurrencyDocument(42), decoding.await())
            sameFile.await()
            assertEquals(1, gatedFileSystem.flushes.get())
            assertEquals(1, gatedFileSystem.closes.get())
            assertContentEquals(byteArrayOf(8), regionStorage.readCompressedChunk(ChunkPosition(1, 0)).bytesOrNull())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                decodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForEncodingOutsideTheFileMutex() = runTest {
        val directory = "/world/region".toPath()
        val encodeGate = BlockingGate()
        val base = concurrencyFakeFileSystem()
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = threadSafeFakeFileSystem(base),
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val encoding = async(Dispatchers.Default) {
                regionStorage.writeChunkNbtDocument(ChunkPosition(0, 0), concurrencyDocument(42), Compression.NONE)
            }
            jobs += encoding
            encodeGate.awaitEntered()
            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionStorage.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> {
                regionStorage.writeCompressedChunk(ChunkPosition(0, 0), concurrencyChunk(1))
            }

            encodeGate.open()
            encoding.await()
            close.await()
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                encodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun entityHandleCloseWaitsForEncodingBeforeTheSingleWriteAdmission() = runTest {
        val directory = "/world/entities".toPath()
        val base = concurrencyFakeFileSystem()
        val encodeGate = BlockingGate()
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = threadSafeFakeFileSystem(base),
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val entityRegionHandle = EntityRegionHandle(regionStorage.openRegion(RegionPosition(0, 0)))
        val entityChunkNbtCodec = EntityChunkNbtCodec(NbtEntityDataRegistry())
        val entity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.NIL,
            data = NbtCompound(emptyMap()),
            position = EntityVector3d(0.5, 64.0, 0.5),
        )
        val entityChunk = EntityChunk(ChunkPosition(0, 0), 1, listOf(entity))
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writing = async(Dispatchers.Default) {
                entityRegionHandle.writeChunk(entityChunk, entityChunkNbtCodec, Compression.NONE)
            }
            jobs += writing
            encodeGate.awaitEntered()

            val closing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                entityRegionHandle.close()
            }
            jobs += closing
            assertFalse(closing.isCompleted)

            encodeGate.open()
            writing.await()
            closing.await()
            assertNotNull(RegionFileStore(directory, base).readCompressedChunk(entityChunk.chunkPosition))
            regionStorage.close()
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                encodeGate.open()
                jobs.joinAll()
                entityRegionHandle.close()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForDecodingAfterSharedFileAccessEnds() = runTest {
        val directory = "/world/region".toPath()
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbtDocument(ChunkPosition(0, 0), concurrencyDocument(7), Compression.NONE)
        setup.close()

        val decodeGate = BlockingGate()
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = base,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            regionStorageConfiguration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) {
                regionStorage.readChunkNbtDocument(ChunkPosition(0, 0))
            }
            jobs += decoding
            decodeGate.awaitEntered()
            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { regionStorage.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> {
                regionStorage.readCompressedChunk(ChunkPosition(0, 0))
            }

            decodeGate.open()
            assertEquals(concurrencyDocument(7), decoding.await())
            close.await()
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                decodeGate.open()
                jobs.joinAll()
                regionStorage.close()
                base.checkNoOpenFiles()
            }
        }
    }
}

private fun concurrencyStore(fileSystem: FileSystem): CoordinatedRegionStore = CoordinatedRegionStore(
    directory = "/world/region".toPath(),
    fileSystem = if (fileSystem is okio.fakefilesystem.FakeFileSystem) {
        threadSafeFakeFileSystem(fileSystem)
    } else {
        fileSystem
    },
    regionStorageConfiguration = concurrencyConfiguration(),
)

private fun concurrencyConfiguration() = RegionStorageConfiguration(syncWrites = false)
