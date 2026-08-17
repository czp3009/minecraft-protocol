package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.*
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.RawSink as KotlinxRawSink
import kotlinx.io.RawSource as KotlinxRawSource
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

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

private fun gatedNbtFormat(gate: BlockingGate): RegionChunkNbtFormat = RegionChunkNbtFormat(
    compressionCodecs = CompressionCodecs(
        mapOf(Compression.NONE to GatedIdentityCompressionCodec(gate)),
    ),
)

private class GatedIdentityCompressionCodec(
    private val gate: BlockingGate,
) : CompressionCodec {
    override fun compressingSink(sink: KotlinxSink): KotlinxRawSink = object : KotlinxRawSink {
        override fun write(
            source: KotlinxBuffer,
            byteCount: Long,
        ) {
            gate.awaitRelease()
            sink.write(source, byteCount)
        }

        override fun flush() = sink.flush()

        override fun close() = sink.flush()
    }

    override fun decompressingSource(
        source: KotlinxSource,
        maximumOutputBytes: Int,
    ): KotlinxRawSource {
        require(maximumOutputBytes >= 0)
        return object : KotlinxRawSource {
            override fun readAtMostTo(
                sink: KotlinxBuffer,
                byteCount: Long,
            ): Long {
                gate.awaitRelease()
                return source.readAtMostTo(sink, byteCount)
            }

            override fun close() = Unit
        }
    }
}
