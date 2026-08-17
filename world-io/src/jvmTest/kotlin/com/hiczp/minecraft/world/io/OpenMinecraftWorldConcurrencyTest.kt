package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.Compression
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.*

class OpenMinecraftWorldConcurrencyTest {
    @Test
    fun repeatedMetadataAccessDoesNotRetainLogicalFileEntries() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val world = concurrencyWorld(paths, base)
        try {
            repeat(2_048) { index ->
                assertNull(world.readPlayerData("player_$index"))
                assertEquals(0, world.activeMetadataEntryCount())
            }
            base.checkNoOpenFiles()
        } finally {
            world.close()
            base.checkNoOpenFiles()
        }
    }

    @Test
    fun levelReadersShareUntilOfficialRecoveryNeedsExclusiveAccess() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val levelStore = LevelDataStore(paths, NbtFileStore(base))
        val fallback = concurrencyDocument(1)
        levelStore.write(fallback)
        levelStore.write(concurrencyDocument(2))
        base.write(paths.levelData) { writeByte(0) }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(base, paths.levelData, sourceGate = sourceGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { world.readLevelData() }
            val second = async(Dispatchers.Default) { world.readLevelData() }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals(fallback, first.await())
            assertEquals(fallback, second.await())
            assertEquals(fallback, NbtFileStore(base).read(paths.levelData))
            assertEquals(0, world.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun healthyPlayerDataReadersShareLogicalFileAccess() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val document = concurrencyDocument(3)
        val base = concurrencyFakeFileSystem()
        PlayerDataStore(paths, NbtFileStore(base)).write(player, document)
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(base, paths.playerData(player), sourceGate = sourceGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { world.readPlayerData(player) }
            val second = async(Dispatchers.Default) { world.readPlayerData(player) }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals(document, first.await())
            assertEquals(document, second.await())
            assertEquals(0, world.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun advancementReadersShareLogicalFileAccess() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val target = paths.advancement(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("{\"value\":1}") }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { world.readAdvancements(player) }
            val second = async(Dispatchers.Default) { world.readAdvancements(player) }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(1, world.activeMetadataEntryCount())
            assertEquals(2, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals("{\"value\":1}", first.await())
            assertEquals("{\"value\":1}", second.await())
            assertEquals(0, world.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
        assertEquals(1, lock.closeAttempts.get())
    }

    @Test
    fun statisticsReadersShareLogicalFileAccess() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val target = paths.statistics(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("{\"value\":2}") }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { world.readStatistics(player) }
            val second = async(Dispatchers.Default) { world.readStatistics(player) }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals("{\"value\":2}", first.await())
            assertEquals("{\"value\":2}", second.await())
            assertEquals(0, world.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun statisticsWriterIsExclusiveWithoutBlockingAnotherLogicalFile() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val statistics = paths.statistics(player)
        val advancements = paths.advancement(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(statistics.parent))
        base.createDirectories(checkNotNull(advancements.parent))
        base.write(statistics) { writeUtf8("old") }
        base.write(advancements) { writeUtf8("old-advancement") }
        val sinkGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, statistics, sinkGate = sinkGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writer = async(Dispatchers.Default) {
                world.writeStatistics(player, "new")
            }
            jobs += writer
            sinkGate.awaitEntered()
            val sameFileReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.readStatistics(player)
            }
            jobs += sameFileReader
            assertFalse(sameFileReader.isCompleted)

            val independent = async(Dispatchers.Default) {
                world.writeAdvancements(player, "new-advancement")
            }
            jobs += independent
            independent.await()
            assertFalse(sameFileReader.isCompleted)
            assertEquals("new-advancement", base.read(advancements) { readUtf8() })

            sinkGate.open()
            writer.await()
            assertEquals("new", sameFileReader.await())
            assertEquals(0, world.activeMetadataEntryCount())
            assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sinkGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun canonicalSavedDataAliasesShareOneExclusiveBoundary() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val target = paths.savedData("minecraft:foo")
        val base = concurrencyFakeFileSystem()
        val initial = concurrencyDocument(1)
        val replacement = concurrencyDocument(2)
        SavedDataFileStore(paths, nbtFiles = NbtFileStore(base)).write("foo", initial)
        val sourceGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { world.readSavedData("foo", DimensionDirectory.Overworld) }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.writeSavedData("minecraft:foo", replacement, DimensionDirectory.Overworld)
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(1, world.activeMetadataEntryCount())
            assertEquals(2, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals(initial, reader.await())
            writer.await()
            assertEquals(replacement, world.readSavedData("foo", DimensionDirectory.Overworld))
            assertEquals(0, world.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForMetadataReaderAndQueuedWriterThenReleasesDirectoryLock() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val target = paths.statistics(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("value") }
        val sourceGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { world.readStatistics(player) }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.writeStatistics(player, "replacement")
            }
            jobs += writer
            assertEquals(2, world.activeMetadataUsers())
            val firstClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { world.close() }
            val secondClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { world.close() }
            jobs += firstClose
            jobs += secondClose
            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)
            assertTrue(lock.isValid)
            assertEquals(0, lock.closeAttempts.get())
            assertFailsWith<IllegalStateException> { world.readStatistics(player) }

            sourceGate.open()
            assertEquals("value", reader.await())
            writer.await()
            firstClose.await()
            secondClose.await()
            assertFalse(lock.isValid)
            assertEquals(1, lock.closeAttempts.get())
            assertEquals("replacement", base.read(target) { readUtf8() })
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun directoryLockDoesNotSerializeDifferentRegionFiles() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val target = paths.regionFile(ChunkPosition(0, 0).region)
        val base = concurrencyFakeFileSystem()
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, readGate = readGate)
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                world.readChunk(
                    ChunkPosition(0, 0),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += reader
            readGate.awaitEntered()
            val sameFileWriter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.writeChunk(
                    ChunkPosition(1, 0),
                    concurrencyChunk(1),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += sameFileWriter
            val otherFileWriter = async(Dispatchers.Default) {
                world.writeChunk(
                    ChunkPosition(32, 0),
                    concurrencyChunk(2),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += otherFileWriter
            otherFileWriter.await()
            assertFalse(sameFileWriter.isCompleted)
            assertTrue(lock.isValid)
            assertEquals(1, world.activeRegionStoreCount())

            readGate.open()
            reader.await()
            sameFileWriter.await()
            assertEquals(0, world.activeRegionStoreCount())
            assertTrue(lock.isValid)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
        assertFalse(lock.isValid)
        assertEquals(1, lock.closeAttempts.get())
    }

    @Test
    fun cancellationDuringMetadataWriteCompletesTheFileBeforeReleasingItsEntry() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val playerUuid = "cancelled-writer"
        val target = paths.statistics(playerUuid)
        val json = "{\"complete\":true}"
        val base = concurrencyFakeFileSystem()
        val sinkGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, sinkGate = sinkGate)
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                world.writeStatistics(playerUuid, json)
                returned.complete(Unit)
            }
            jobs += writing
            sinkGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.readStatistics(playerUuid).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(1, world.activeMetadataEntryCount())
            assertEquals(2, world.activeMetadataUsers())

            val cancellation = CancellationException("cancelled during metadata write")
            writing.cancel(cancellation)
            sinkGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertEquals(json, reading.await())
            assertEquals(0, world.activeMetadataEntryCount())
            assertEquals(json, world.readStatistics(playerUuid))
            assertEquals(0, world.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sinkGate.open()
                jobs.joinAll()
                world.close()
                assertFalse(lock.isValid)
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringNestedRegionWriteCompletesAValidCommitAndReleasesBothStateLayers() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val position = ChunkPosition(0, 0)
        val target = paths.regionFile(position.region)
        val base = concurrencyFakeFileSystem()
        val writeGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, writeGate = writeGate)
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                world.writeChunk(
                    position,
                    concurrencyChunk(5),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
                returned.complete(Unit)
            }
            jobs += writing
            writeGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.readChunk(
                    position,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                ).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(1, world.activeRegionStoreCount())
            assertEquals(2, world.activeRegionStoreUsers())

            val cancellation = CancellationException("cancelled during nested region write")
            writing.cancel(cancellation)
            writeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertContentEquals(
                byteArrayOf(5),
                reading.await()?.payload?.compressedBytes,
            )
            assertEquals(0, world.activeRegionStoreCount())
            assertContentEquals(
                byteArrayOf(5),
                world.readChunk(
                    position,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )?.payload?.compressedBytes,
            )
            assertEquals(0, world.activeRegionStoreCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                world.close()
                assertFalse(lock.isValid)
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancelledCloseOwnerFinishesDirectoryLockCleanupAndPublishesSuccess() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val closeGate = BlockingGate()
        val lock = RecordingWorldDirectoryLock(closeGate)
        val world = concurrencyWorld(paths, base, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val closing = async(Dispatchers.Default) {
                world.close()
                returned.complete(Unit)
            }
            jobs += closing
            closeGate.awaitEntered()

            val cancellation = CancellationException("world close owner cancelled")
            closing.cancel(cancellation)
            closeGate.open()
            val failure = assertFailsWith<CancellationException> { closing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertFalse(lock.isValid)
            assertEquals(1, lock.closeAttempts.get())
            base.checkNoOpenFiles()

            world.close()
            assertEquals(1, lock.closeAttempts.get())
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun flushKeepsCancellationPrimaryAcrossRegionStoresAndOnlyReleasesRemainingPins() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val position = ChunkPosition(0, 0)
        val storageDirectories = RegionStorageDirectory.entries
        val base = concurrencyFakeFileSystem()
        storageDirectories.forEachIndexed { index, storage ->
            val setup = WorldRegionStore(
                paths = paths,
                storage = storage,
                fileSystem = base,
                configuration = WorldRegionStoreConfiguration(syncWrites = false),
            )
            setup.writeChunkNbt(position, concurrencyDocument(index), Compression.NONE)
            setup.close()
        }

        val earlierFailure = IOException("synthetic world flush failure before cancellation")
        val cancellation = CancellationException("synthetic world flush cancellation")
        val fileSystem = SequencedFlushFailureFileSystem(
            delegate = threadSafeFakeFileSystem(base),
            failures = listOf(earlierFailure, cancellation),
        )
        val readGate = BlockingGate(expectedEntrants = storageDirectories.size)
        val encodeGate = BlockingGate(expectedEntrants = storageDirectories.size)
        val lock = RecordingWorldDirectoryLock()
        val world = OpenMinecraftWorld(
            paths = paths,
            files = WorldFileAccess.mutable(fileSystem),
            regionChunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStoreConfiguration = WorldRegionStoreConfiguration(
                syncWrites = false,
                writeCompression = Compression.NONE,
            ),
            directoryLock = lock,
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reading = storageDirectories.map { storage ->
                async(Dispatchers.Default) {
                    world.readChunk(position, storage, DimensionDirectory.Overworld) { _, source ->
                        readGate.awaitRelease()
                        source.readByteArray()
                    }
                }
            }
            jobs += reading
            readGate.awaitEntered()
            val encoding = storageDirectories.mapIndexed { index, storage ->
                async(Dispatchers.Default) {
                    world.writeChunkNbt(
                        position,
                        concurrencyDocument(index + 10),
                        storage,
                        DimensionDirectory.Overworld,
                    )
                }
            }
            jobs += encoding
            encodeGate.awaitEntered()
            readGate.open()
            reading.awaitAll()

            val failure = assertFailsWith<CancellationException> { world.flush() }

            assertSame(cancellation, failure)
            assertSame(earlierFailure, failure.suppressedExceptions.single())
            assertEquals(2, fileSystem.flushAttempts.get())
            assertEquals(storageDirectories.size, world.activeRegionStoreCount())

            encodeGate.open()
            encoding.awaitAll()
            assertEquals(0, world.activeRegionStoreCount())
            world.close()
            assertFalse(lock.isValid)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                encodeGate.open()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun completedCloseFailureIsReportedToLaterCloseCallers() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val expected = IOException("synthetic directory lock close failure")
        var closeAttempts = 0
        val lock = object : WorldDirectoryLock {
            private var valid = true

            override val isValid: Boolean
                get() = valid

            override fun close() {
                closeAttempts++
                valid = false
                throw expected
            }
        }
        val world = concurrencyWorld(paths, base, lock)

        val first = assertFailsWith<IOException> { world.close() }
        val later = assertFailsWith<IOException> { world.close() }

        assertSame(expected, first)
        assertSame(first, later)
        assertEquals(1, closeAttempts)
        assertFalse(lock.isValid)
        base.checkNoOpenFiles()
    }

    @Test
    fun completedRegionCleanupFailureDoesNotPoisonLaterWorldClose() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val position = ChunkPosition(0, 0)
        val base = concurrencyFakeFileSystem()
        val fileSystem = GatedFileSystem(
            base = base,
            target = paths.regionFile(position.region),
            closeFailures = 1,
        )
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        try {
            val operationFailure = assertFailsWith<IOException> {
                world.readChunk(
                    position,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertEquals(0, world.activeRegionStoreCount())
            assertTrue(lock.isValid)

            world.close()

            assertFalse(lock.isValid)
            assertEquals(1, lock.closeAttempts.get())
            assertEquals(1, fileSystem.closes.get())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }
}

private fun concurrencyWorld(
    paths: MinecraftWorldPaths,
    fileSystem: okio.FileSystem,
    lock: WorldDirectoryLock = RecordingWorldDirectoryLock(),
): OpenMinecraftWorld = OpenMinecraftWorld(
    paths = paths,
    files = WorldFileAccess.mutable(
        if (fileSystem is okio.fakefilesystem.FakeFileSystem) {
            threadSafeFakeFileSystem(fileSystem)
        } else {
            fileSystem
        },
    ),
    regionStoreConfiguration = WorldRegionStoreConfiguration(syncWrites = false),
    directoryLock = lock,
)
