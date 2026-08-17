package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.*

class WorldRegionConcurrencyTest {
    @Test
    fun writesToDifferentRegionFilesReachTheirCommitPointsConcurrently() = runTest {
        val directory = "/world/region".toPath()
        val firstPosition = ChunkPosition(0, 0)
        val secondPosition = ChunkPosition(32, 0)
        val firstGate = BlockingGate()
        val secondGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            writeGate = firstGate,
            additionalWriteGates = mapOf(directory / "r.1.0.mca" to secondGate),
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                store.writeChunk(firstPosition, concurrencyChunk(1))
            }
            jobs += first
            firstGate.awaitEntered()
            val second = async(Dispatchers.Default) {
                store.writeChunk(secondPosition, concurrencyChunk(2))
            }
            jobs += second
            secondGate.awaitEntered()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(2, fileSystem.activeWrites.get())
            assertEquals(2, fileSystem.maximumConcurrentWrites.get())

            firstGate.open()
            secondGate.open()
            first.await()
            second.await()
            assertContentEquals(byteArrayOf(1), store.readChunk(firstPosition)?.payload?.compressedBytes)
            assertContentEquals(byteArrayOf(2), store.readChunk(secondPosition)?.payload?.compressedBytes)
            assertEquals(0, store.activeRegionCount())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                firstGate.open()
                secondGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun slowSameFileReadQueuesSameFileButNotAnotherFile() = runTest {
        val directory = "/world/region".toPath()
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val store = concurrencyStore(fileSystem)
        val sameFile = ChunkPosition(0, 0)
        val otherFile = ChunkPosition(32, 0)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val slow = async(Dispatchers.Default) { store.readChunk(sameFile) }
            jobs += slow
            readGate.awaitEntered()

            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.writeChunk(sameFile, concurrencyChunk(7))
            }
            jobs += queued
            assertFalse(queued.isCompleted)
            assertEquals(2, store.activeRegionUsers(sameFile.region))

            val independent = async(Dispatchers.Default) {
                store.writeChunk(otherFile, concurrencyChunk(9))
            }
            jobs += independent
            independent.await()
            assertFalse(queued.isCompleted)

            readGate.open()
            slow.await()
            queued.await()
            assertEquals(0, store.activeRegionCount())
            fileSystem.base.checkNoOpenFiles()

            assertContentEquals(byteArrayOf(7), store.readChunk(sameFile)?.payload?.compressedBytes)
            assertContentEquals(byteArrayOf(9), store.readChunk(otherFile)?.payload?.compressedBytes)
            assertEquals(0, store.activeRegionCount())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellingAWaitingWriterReleasesItsEntryPin() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { store.readChunk(position) }
            jobs += reader
            readGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.writeChunk(position, concurrencyChunk(3))
            }
            jobs += writer
            assertEquals(2, store.activeRegionUsers(position.region))

            writer.cancelAndJoin()
            assertEquals(1, store.activeRegionUsers(position.region))
            readGate.open()
            reader.await()
            assertEquals(0, store.activeRegionCount())
            assertEquals(1, fileSystem.closes.get())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun readersResumeConcurrentlyAsSoonAsWriterReleasesExclusiveAccess() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val writeGate = BlockingGate()
        val readGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = target,
            readGate = readGate,
            writeGate = writeGate,
            gateReadsInitially = false,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writer = async(Dispatchers.Default) {
                store.writeChunk(ChunkPosition(0, 0), concurrencyChunk(4))
            }
            jobs += writer
            writeGate.awaitEntered()
            fileSystem.enableReadGate()

            val firstReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(ChunkPosition(0, 0))
            }
            val secondReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(ChunkPosition(0, 0))
            }
            jobs += firstReader
            jobs += secondReader
            assertFalse(firstReader.isCompleted)
            assertFalse(secondReader.isCompleted)

            writeGate.open()
            writer.await()
            readGate.awaitEntered()
            assertEquals(2, fileSystem.maximumConcurrentReads.get())
            assertEquals(0, fileSystem.flushes.get())
            assertEquals(0, fileSystem.closes.get())
            assertEquals(1, store.activeRegionCount())
            readGate.open()
            assertContentEquals(byteArrayOf(4), firstReader.await()?.payload?.compressedBytes)
            assertContentEquals(byteArrayOf(4), secondReader.await()?.payload?.compressedBytes)
            assertEquals(1, fileSystem.flushes.get())
            assertEquals(1, fileSystem.closes.get())
            assertEquals(0, store.activeRegionCount())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun syncWritesFlushEachCommitButCloseWaitsForTheLastReader() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val writeGate = BlockingGate()
        val readGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = target,
            readGate = readGate,
            writeGate = writeGate,
            gateReadsInitially = false,
        )
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            configuration = WorldRegionStoreConfiguration(syncWrites = true),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writer = async(Dispatchers.Default) {
                store.writeChunk(ChunkPosition(0, 0), concurrencyChunk(4))
            }
            jobs += writer
            writeGate.awaitEntered()
            fileSystem.enableReadGate()
            val firstReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(ChunkPosition(0, 0))
            }
            val secondReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(ChunkPosition(0, 0))
            }
            jobs += firstReader
            jobs += secondReader

            writeGate.open()
            writer.await()
            readGate.awaitEntered()
            assertEquals(2, fileSystem.flushes.get())
            assertEquals(0, fileSystem.closes.get())
            readGate.open()
            firstReader.await()
            secondReader.await()
            assertEquals(3, fileSystem.flushes.get())
            assertEquals(1, fileSystem.closes.get())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun readersOfAnAlreadyOpenRegionUseTheHandleConcurrently() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbt(ChunkPosition(0, 0), concurrencyDocument(12), Compression.NONE)
        setup.close()

        val decodeGate = BlockingGate()
        val readGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(
            base = base,
            target = target,
            readGate = readGate,
            gateReadsInitially = false,
        )
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) {
                store.readChunkNbt(ChunkPosition(0, 0))
            }
            jobs += decoding
            decodeGate.awaitEntered()
            fileSystem.enableReadGate()

            val firstReader = async(Dispatchers.Default) { store.readChunk(ChunkPosition(0, 0)) }
            val secondReader = async(Dispatchers.Default) { store.readChunk(ChunkPosition(0, 0)) }
            jobs += firstReader
            jobs += secondReader
            readGate.awaitEntered()
            assertEquals(2, fileSystem.maximumConcurrentReads.get())

            readGate.open()
            firstReader.await()
            secondReader.await()
            decodeGate.open()
            assertEquals(concurrencyDocument(12), decoding.await())
            assertEquals(0, store.activeRegionCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                decodeGate.open()
                jobs.joinAll()
                store.close()
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
        positions.forEachIndexed { index, position ->
            setup.writeChunkNbt(position, concurrencyDocument(index), Compression.NONE)
        }
        setup.close()

        val earlierFailure = IOException("synthetic flush failure before cancellation")
        val cancellation = CancellationException("synthetic flush cancellation")
        val fileSystem = SequencedFlushFailureFileSystem(
            delegate = threadSafeFakeFileSystem(base),
            failures = listOf(earlierFailure, cancellation),
        )
        val decodeGate = BlockingGate(expectedEntrants = positions.size)
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = positions.map { position ->
                async(Dispatchers.Default) { store.readChunkNbt(position) }
            }
            jobs += decoding
            decodeGate.awaitEntered()

            val failure = assertFailsWith<CancellationException> { store.flush() }

            assertSame(cancellation, failure)
            assertSame(earlierFailure, failure.suppressedExceptions.single())
            assertEquals(2, fileSystem.flushAttempts.get())
            positions.forEach { position ->
                assertEquals(1, store.activeRegionUsers(position.region))
            }

            decodeGate.open()
            decoding.forEachIndexed { index, result ->
                assertEquals(concurrencyDocument(index), result.await())
            }
            assertEquals(0, store.activeRegionCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                decodeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringTheLastPhysicalFlushIsObservedAfterPinCleanup() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbt(position, concurrencyDocument(7), Compression.NONE)
        setup.close()
        val bytesBeforeCancellation = base.read(target) { readByteArray() }

        val decodeGate = BlockingGate()
        val flushGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = base,
            target = target,
            flushGate = flushGate,
        )
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) { store.readChunkNbt(position) }
            jobs += decoding
            decodeGate.awaitEntered()

            val returned = CompletableDeferred<Unit>()
            val flushing = async(Dispatchers.Default) {
                store.flush()
                returned.complete(Unit)
            }
            jobs += flushing
            flushGate.awaitEntered()

            val cancellation = CancellationException("cancelled during physical flush")
            flushing.cancel(cancellation)
            flushGate.open()
            val failure = assertFailsWith<CancellationException> { flushing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertEquals(1, store.activeRegionUsers(position.region))

            decodeGate.open()
            assertEquals(concurrencyDocument(7), decoding.await())
            assertEquals(0, store.activeRegionCount())
            assertContentEquals(bytesBeforeCancellation, base.read(target) { readByteArray() })
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                flushGate.open()
                decodeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringPhysicalWriteCompletesAValidCommitBeforeReleasingState() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunk(position, concurrencyChunk(1))
        setup.close()

        val writeGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, writeGate = writeGate)
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                store.writeChunk(position, concurrencyChunk(9))
                returned.complete(Unit)
            }
            jobs += writing
            writeGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(position).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(2, store.activeRegionUsers(position.region))

            val cancellation = CancellationException("cancelled during physical write")
            writing.cancel(cancellation)
            writeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertContentEquals(byteArrayOf(9), reading.await()?.payload?.compressedBytes)
            assertEquals(0, store.activeRegionCount())
            base.checkNoOpenFiles()

            val verifier = concurrencyStore(base)
            try {
                assertContentEquals(byteArrayOf(9), verifier.readChunk(position)?.payload?.compressedBytes)
            } finally {
                verifier.close()
            }
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringExternalWriteCompletesTheSidecarCommitAndRemovesItsTemporaryFile() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val region = directory / "r.0.0.mca"
        val sidecar = directory / "c.0.0.mcc"
        val externalBytes = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 17 + 3).toByte() }
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunk(position, concurrencyChunk(1))
        setup.close()

        val writeGate = BlockingGate()
        val store = concurrencyStore(GatedFileSystem(base, region, writeGate = writeGate))
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                store.writeChunk(
                    position,
                    RegionChunk(
                        compression = Compression.NONE,
                        payload = RegionChunkPayload.Inline(externalBytes),
                    ),
                )
                returned.complete(Unit)
            }
            jobs += writing
            writeGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(position).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(2, store.activeRegionUsers(position.region))

            val cancellation = CancellationException("cancelled during external chunk commit")
            writing.cancel(cancellation)
            writeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertContentEquals(externalBytes, reading.await()?.payload?.compressedBytes)
            assertEquals(0, store.activeRegionCount())
            assertTrue(base.exists(sidecar))
            assertTrue(base.list(directory).none { it.name.startsWith(".mcc-") })
            base.checkNoOpenFiles()

            val verifier = concurrencyStore(base)
            try {
                assertContentEquals(externalBytes, verifier.readChunk(position)?.payload?.compressedBytes)
            } finally {
                verifier.close()
            }
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringEncodingPreventsTheLaterPhysicalCommitAndReleasesState() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val original = concurrencyDocument(4)
        val setup = concurrencyStore(base)
        setup.writeChunkNbt(position, original, Compression.NONE)
        setup.close()
        val bytesBeforeCancellation = base.read(target) { readByteArray() }

        val encodeGate = BlockingGate()
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = GatedFileSystem(base, target),
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                store.writeChunkNbt(position, concurrencyDocument(8), Compression.NONE)
                returned.complete(Unit)
            }
            jobs += writing
            encodeGate.awaitEntered()

            val cancellation = CancellationException("cancelled during chunk encoding")
            writing.cancel(cancellation)
            encodeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertEquals(0, store.activeRegionCount())
            assertContentEquals(bytesBeforeCancellation, base.read(target) { readByteArray() })
            base.checkNoOpenFiles()

            val verifier = WorldRegionStore(
                directory = directory,
                fileSystem = base,
                configuration = concurrencyConfiguration(),
            )
            try {
                assertEquals(original, verifier.readChunkNbt(position))
            } finally {
                verifier.close()
            }
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                encodeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancelledCloseOwnerFinishesCleanupWhileCancelledWaiterDoesNotAffectTheBarrier() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbt(position, concurrencyDocument(6), Compression.NONE)
        setup.close()
        val bytesBeforeClose = base.read(target) { readByteArray() }

        val decodeGate = BlockingGate()
        val flushGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, flushGate = flushGate)
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) { store.readChunkNbt(position) }
            jobs += decoding
            decodeGate.awaitEntered()

            val ownerReturned = CompletableDeferred<Unit>()
            val owner = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.close()
                ownerReturned.complete(Unit)
            }
            val waiterReturned = CompletableDeferred<Unit>()
            val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.close()
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
            assertEquals(0, store.activeRegionCount())
            assertContentEquals(bytesBeforeClose, base.read(target) { readByteArray() })
            base.checkNoOpenFiles()

            store.close()
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                decodeGate.open()
                flushGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancelledCloseOwnerKeepsThePhysicalCleanupFailureForLaterCloseCallers() = runTest {
        supervisorScope {
            val directory = "/world/region".toPath()
            val position = ChunkPosition(0, 0)
            val target = directory / "r.0.0.mca"
            val base = concurrencyFakeFileSystem()
            val closeGate = BlockingGate()
            val fileSystem = GatedFileSystem(
                base = base,
                target = target,
                closeGate = closeGate,
                closeFailures = 1,
            )
            val store = concurrencyStore(fileSystem)
            val jobs = mutableListOf<Deferred<*>>()
            try {
                val operation = async(Dispatchers.Default) {
                    runCatching { store.readChunk(position) }
                }
                jobs += operation
                closeGate.awaitEntered()

                val returned = CompletableDeferred<Unit>()
                val observedCloseFailure = CompletableDeferred<Throwable>()
                val closing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        store.close()
                        returned.complete(Unit)
                    } catch (caught: Throwable) {
                        observedCloseFailure.complete(caught)
                        throw caught
                    }
                }
                jobs += closing
                val cancellation = CancellationException("close owner cancelled before cleanup failure")
                closing.cancel(cancellation)
                closeGate.open()

                val operationFailure = assertIs<IOException>(operation.await().exceptionOrNull())
                val closeCancellation = assertFailsWith<CancellationException> { closing.await() }
                val observedCancellation = assertIs<CancellationException>(observedCloseFailure.await())
                assertEquals(cancellation.message, closeCancellation.message)
                assertSame(operationFailure, observedCancellation.suppressedExceptions.single())
                assertFalse(returned.isCompleted)
                assertEquals(0, store.activeRegionCount())
                base.checkNoOpenFiles()

                val laterFailure = assertFailsWith<IOException> { store.close() }
                assertSame(operationFailure, laterFailure)
                assertFailsWith<IllegalStateException> { store.readChunk(position) }
            } finally {
                withContext(NonCancellable) {
                    closeGate.open()
                    jobs.joinAll()
                    runCatching { store.close() }
                    base.checkNoOpenFiles()
                }
            }
        }
    }

    @Test
    fun mcaHeaderAndExternalSidecarsShareOneExclusiveBoundary() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val sidecar = directory / "c.0.0.mcc"
        val externalBytes = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 13 + 5).toByte() }
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunk(
            position,
            RegionChunk(
                compression = Compression.NONE,
                payload = RegionChunkPayload.Inline(externalBytes),
            ),
        )
        setup.close()

        val sourceGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, sidecar, sourceGate = sourceGate)
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { store.readChunk(position) }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.writeChunk(position, concurrencyChunk(6))
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(2, store.activeRegionUsers(position.region))

            sourceGate.open()
            assertContentEquals(externalBytes, reader.await()?.payload?.compressedBytes)
            writer.await()
            assertFalse(base.exists(sidecar))
            assertContentEquals(byteArrayOf(6), store.readChunk(position)?.payload?.compressedBytes)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun concurrentFirstAccessOpensARegionOnceAndReleasesIt() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(concurrencyFakeFileSystem(), target, readGate = readGate)
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            jobs += async(Dispatchers.Default) { store.readChunk(ChunkPosition(0, 0)) }
            readGate.awaitEntered()
            repeat(8) { index ->
                jobs += async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    store.readChunk(ChunkPosition(index + 1, 0))
                }
            }
            assertEquals(9, store.activeRegionUsers(RegionPosition(0, 0)))

            readGate.open()
            jobs.awaitAll()
            assertEquals(1, fileSystem.opens.get())
            assertEquals(1, fileSystem.closes.get())
            assertEquals(0, store.activeRegionCount())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun concurrentWritesToOneRegionRemainComplete() = runTest {
        val directory = "/world/region".toPath()
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            jobs += async(Dispatchers.Default) {
                store.writeChunk(ChunkPosition(0, 0), concurrencyChunk(0))
            }
            readGate.awaitEntered()
            repeat(15) { index ->
                jobs += async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    val value = (index + 1).toByte()
                    store.writeChunk(ChunkPosition(index + 1, 0), concurrencyChunk(value))
                }
            }
            assertEquals(16, store.activeRegionUsers(RegionPosition(0, 0)))

            readGate.open()
            jobs.awaitAll()
            assertEquals(1, fileSystem.maximumConcurrentWrites.get())
            repeat(16) { index ->
                assertContentEquals(
                    byteArrayOf(index.toByte()),
                    store.readChunk(ChunkPosition(index, 0))?.payload?.compressedBytes,
                )
            }
            assertEquals(0, store.activeRegionCount())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun nonSyncQueuedWritesFlushOnceAtTheFinalRelease() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(concurrencyFakeFileSystem(), target, readGate = readGate)
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                store.writeChunk(ChunkPosition(0, 0), concurrencyChunk(1))
            }
            jobs += first
            readGate.awaitEntered()
            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.writeChunk(ChunkPosition(1, 0), concurrencyChunk(2))
            }
            jobs += queued
            assertEquals(2, store.activeRegionUsers(RegionPosition(0, 0)))

            readGate.open()
            jobs.awaitAll()
            assertEquals(1, fileSystem.flushes.get())
            assertEquals(1, fileSystem.closes.get())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun lastReleaseCloseAndImmediateReopenDoNotOverlap() = runTest {
        val directory = "/world/region".toPath()
        val target = directory / "r.0.0.mca"
        val closeGate = BlockingGate()
        val fileSystem = GatedFileSystem(concurrencyFakeFileSystem(), target, closeGate = closeGate)
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { store.readChunk(ChunkPosition(0, 0)) }
            jobs += first
            closeGate.awaitEntered()

            val reopen = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(ChunkPosition(0, 0))
            }
            jobs += reopen
            assertFalse(reopen.isCompleted)
            assertEquals(1, fileSystem.opens.get())

            closeGate.open()
            first.await()
            reopen.await()
            assertEquals(2, fileSystem.opens.get())
            assertEquals(2, fileSystem.closes.get())
            val firstCloseEnd = fileSystem.events.indexOf("close-end")
            val secondOpen = fileSystem.events.lastIndexOf("open")
            assertTrue(firstCloseEnd in 0 until secondOpen)
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeFailureUnblocksSameRegionReopenWithoutPoisoningLaterStoreClose() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val closeGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = target,
            closeGate = closeGate,
            closeFailures = 1,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                runCatching { store.readChunk(position) }
            }
            jobs += first
            closeGate.awaitEntered()

            val reopen = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(position)
            }
            jobs += reopen
            assertFalse(reopen.isCompleted)
            assertEquals(1, fileSystem.opens.get())

            closeGate.open()
            val operationFailure = assertIs<IOException>(first.await().exceptionOrNull())
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertNull(reopen.await())

            assertEquals(2, fileSystem.opens.get())
            assertEquals(2, fileSystem.closes.get())
            assertEquals(0, store.activeRegionCount())
            val firstCloseEnd = fileSystem.events.indexOf("close-end")
            val secondOpen = fileSystem.events.lastIndexOf("open")
            assertTrue(firstCloseEnd in 0 until secondOpen)
            fileSystem.base.checkNoOpenFiles()

            store.close()
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeBarrierAndConcurrentWaiterObserveLastReleaseCloseFailure() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val target = directory / "r.0.0.mca"
        val closeGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = target,
            closeGate = closeGate,
            closeFailures = 1,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val operation = async(Dispatchers.Default) {
                runCatching { store.readChunk(position) }
            }
            jobs += operation
            closeGate.awaitEntered()

            val firstClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                runCatching { store.close() }
            }
            val secondClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                runCatching { store.close() }
            }
            jobs += firstClose
            jobs += secondClose
            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)
            assertFailsWith<IllegalStateException> { store.readChunk(position) }

            closeGate.open()
            val operationFailure = assertIs<IOException>(operation.await().exceptionOrNull())
            val firstCloseFailure = assertIs<IOException>(firstClose.await().exceptionOrNull())
            val secondCloseFailure = assertIs<IOException>(secondClose.await().exceptionOrNull())
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertSame(operationFailure, firstCloseFailure)
            assertSame(operationFailure, secondCloseFailure)
            assertEquals(1, fileSystem.opens.get())
            assertEquals(1, fileSystem.closes.get())
            fileSystem.base.checkNoOpenFiles()

            val laterCloseFailure = assertFailsWith<IOException> { store.close() }
            assertSame(operationFailure, laterCloseFailure)
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                runCatching { store.close() }
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForAdmittedWaitingReadersThenRejectsNewReaders() = runTest {
        val directory = "/world/region".toPath()
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { store.readChunk(ChunkPosition(0, 0)) }
            jobs += first
            readGate.awaitEntered()
            val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.readChunk(ChunkPosition(1, 0))
            }
            jobs += queued
            assertEquals(2, store.activeRegionUsers(RegionPosition(0, 0)))

            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> {
                store.readChunk(ChunkPosition(2, 0))
            }

            readGate.open()
            first.await()
            queued.await()
            close.await()
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForAWriterAdmittedBehindAnActiveReader() = runTest {
        val directory = "/world/region".toPath()
        val position = ChunkPosition(0, 0)
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { store.readChunk(position) }
            jobs += reader
            readGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                store.writeChunk(position, concurrencyChunk(5))
            }
            jobs += writer
            assertEquals(2, store.activeRegionUsers(position.region))
            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> { store.readChunk(position) }

            readGate.open()
            reader.await()
            writer.await()
            close.await()
            assertEquals(1, fileSystem.closes.get())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun concurrentClosesCoalesceAroundOneCompletion() = runTest {
        val directory = "/world/region".toPath()
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = directory / "r.0.0.mca",
            readGate = readGate,
        )
        val store = concurrencyStore(fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { store.readChunk(ChunkPosition(0, 0)) }
            jobs += reader
            readGate.awaitEntered()
            val firstClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.close() }
            val secondClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.close() }
            jobs += firstClose
            jobs += secondClose
            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)

            readGate.open()
            reader.await()
            firstClose.await()
            secondClose.await()
            assertEquals(1, fileSystem.closes.get())
            fileSystem.base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                store.close()
                fileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun nbtEncodingDoesNotHoldTheRegionFileMutex() = runTest {
        val directory = "/world/region".toPath()
        val encodeGate = BlockingGate()
        val base = concurrencyFakeFileSystem()
        val fileSystem = GatedFileSystem(base, directory / "r.0.0.mca")
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val encoding = async(Dispatchers.Default) {
                store.writeChunkNbt(ChunkPosition(0, 0), concurrencyDocument(42), Compression.NONE)
            }
            jobs += encoding
            encodeGate.awaitEntered()
            val sameFile = async(Dispatchers.Default) {
                store.writeChunk(ChunkPosition(1, 0), concurrencyChunk(8))
            }
            jobs += sameFile
            sameFile.await()
            assertFalse(encoding.isCompleted)
            assertEquals(0, fileSystem.flushes.get())
            assertEquals(0, fileSystem.closes.get())
            assertEquals(1, store.activeRegionCount())

            encodeGate.open()
            encoding.await()
            assertEquals(1, fileSystem.flushes.get())
            assertEquals(1, fileSystem.closes.get())
            assertEquals(concurrencyDocument(42), store.readChunkNbt(ChunkPosition(0, 0)))
            assertContentEquals(byteArrayOf(8), store.readChunk(ChunkPosition(1, 0))?.payload?.compressedBytes)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                encodeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun nbtDecodingDoesNotHoldTheRegionFileMutex() = runTest {
        val directory = "/world/region".toPath()
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbt(ChunkPosition(0, 0), concurrencyDocument(42), Compression.NONE)
        setup.close()

        val decodeGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, directory / "r.0.0.mca")
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = fileSystem,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) {
                store.readChunkNbt(ChunkPosition(0, 0))
            }
            jobs += decoding
            decodeGate.awaitEntered()
            val sameFile = async(Dispatchers.Default) {
                store.writeChunk(ChunkPosition(1, 0), concurrencyChunk(8))
            }
            jobs += sameFile
            sameFile.await()
            assertFalse(decoding.isCompleted)
            assertEquals(0, fileSystem.flushes.get())
            assertEquals(0, fileSystem.closes.get())
            assertEquals(1, store.activeRegionCount())

            decodeGate.open()
            assertEquals(concurrencyDocument(42), decoding.await())
            assertEquals(1, fileSystem.flushes.get())
            assertEquals(1, fileSystem.closes.get())
            assertContentEquals(byteArrayOf(8), store.readChunk(ChunkPosition(1, 0))?.payload?.compressedBytes)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                decodeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForEncodingOutsideTheFileMutex() = runTest {
        val directory = "/world/region".toPath()
        val encodeGate = BlockingGate()
        val base = concurrencyFakeFileSystem()
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = base,
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val encoding = async(Dispatchers.Default) {
                store.writeChunkNbt(ChunkPosition(0, 0), concurrencyDocument(42), Compression.NONE)
            }
            jobs += encoding
            encodeGate.awaitEntered()
            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> {
                store.writeChunk(ChunkPosition(0, 0), concurrencyChunk(1))
            }

            encodeGate.open()
            encoding.await()
            close.await()
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                encodeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForDecodingAfterSharedFileAccessEnds() = runTest {
        val directory = "/world/region".toPath()
        val base = concurrencyFakeFileSystem()
        val setup = concurrencyStore(base)
        setup.writeChunkNbt(ChunkPosition(0, 0), concurrencyDocument(7), Compression.NONE)
        setup.close()

        val decodeGate = BlockingGate()
        val store = WorldRegionStore(
            directory = directory,
            fileSystem = base,
            chunkNbtFormat = gatedNbtFormat(decodeGate),
            configuration = concurrencyConfiguration(),
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val decoding = async(Dispatchers.Default) {
                store.readChunkNbt(ChunkPosition(0, 0))
            }
            jobs += decoding
            decodeGate.awaitEntered()
            val close = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.close() }
            jobs += close
            assertFalse(close.isCompleted)
            assertFailsWith<IllegalStateException> {
                store.readChunk(ChunkPosition(0, 0))
            }

            decodeGate.open()
            assertEquals(concurrencyDocument(7), decoding.await())
            close.await()
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                decodeGate.open()
                jobs.joinAll()
                store.close()
                base.checkNoOpenFiles()
            }
        }
    }
}

private fun concurrencyStore(fileSystem: FileSystem): WorldRegionStore = WorldRegionStore(
    directory = "/world/region".toPath(),
    fileSystem = if (fileSystem is okio.fakefilesystem.FakeFileSystem) {
        threadSafeFakeFileSystem(fileSystem)
    } else {
        fileSystem
    },
    configuration = concurrencyConfiguration(),
)

private fun concurrencyConfiguration() = WorldRegionStoreConfiguration(syncWrites = false)
