package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
                assertNull(world.readPlayerDataDocument("player_$index"))
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
        levelStore.writeDocument(fallback)
        levelStore.writeDocument(concurrencyDocument(2))
        base.write(paths.levelData) { writeByte(0) }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(base, paths.levelData, sourceGate = sourceGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { world.readLevelDataDocument() }
            val second = async(Dispatchers.Default) { world.readLevelDataDocument() }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals(fallback, first.await())
            assertEquals(fallback, second.await())
            assertEquals(fallback, NbtFileStore(base).readDocument(paths.levelData))
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
        PlayerDataStore(paths, NbtFileStore(base)).writeDocument(player, document)
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val fileSystem = GatedFileSystem(base, paths.playerData(player), sourceGate = sourceGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { world.readPlayerDataDocument(player) }
            val second = async(Dispatchers.Default) { world.readPlayerDataDocument(player) }
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
            val first = async(Dispatchers.Default) { world.readAdvancementsText(player) }
            val second = async(Dispatchers.Default) { world.readAdvancementsText(player) }
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
            val first = async(Dispatchers.Default) { world.readStatisticsText(player) }
            val second = async(Dispatchers.Default) { world.readStatisticsText(player) }
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
    fun typedTreeTextAndRawStatisticsShareOneWriterPreferringBoundary() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val player = "typed-player"
        val target = paths.statistics(player)
        val initial = PlayerStatistics(
            stats = mapOf("minecraft:custom" to mapOf("minecraft:play_time" to 1)),
            dataVersion = 4_903,
        )
        val replacement = initial.copy(
            stats = mapOf("minecraft:custom" to mapOf("minecraft:play_time" to 2)),
        )
        val base = concurrencyFakeFileSystem()
        Utf8JsonFileStore(base).writeJson(target, PlayerStatistics.serializer(), initial)
        val sourceGate = BlockingGate(expectedEntrants = 3)
        val sinkGate = BlockingGate()
        val fileSystem = GatedFileSystem(
            base = base,
            target = target,
            sourceGate = sourceGate,
            sinkGate = sinkGate,
        )
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val typed = async(Dispatchers.Default) {
                world.readStatistics(player, PlayerStatistics.serializer())
            }
            val tree = async(Dispatchers.Default) {
                world.readStatistics(player, JsonElement.serializer())
            }
            val raw = async(Dispatchers.Default) {
                world.readStatistics(player) { source -> source.readString() }
            }
            jobs += typed
            jobs += tree
            jobs += raw
            sourceGate.awaitEntered()
            assertEquals(3, world.activeMetadataUsers())

            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.writeStatistics(player, PlayerStatistics.serializer(), replacement)
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(4, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals(initial, typed.await())
            assertEquals(initial, Json.decodeFromJsonElement(PlayerStatistics.serializer(), tree.await()))
            assertEquals(initial, Json.decodeFromString(PlayerStatistics.serializer(), raw.await()))
            sinkGate.awaitEntered()

            val text = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.readStatisticsText(player)
            }
            jobs += text
            assertFalse(text.isCompleted)
            sinkGate.open()
            writer.await()
            assertEquals(replacement, Json.decodeFromString(PlayerStatistics.serializer(), text.await()))
            assertEquals(0, world.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                sinkGate.open()
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
                world.writeStatisticsText(player, "new")
            }
            jobs += writer
            sinkGate.awaitEntered()
            val sameFileReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.readStatisticsText(player)
            }
            jobs += sameFileReader
            assertFalse(sameFileReader.isCompleted)

            val independent = async(Dispatchers.Default) {
                world.writeAdvancementsText(player, "new-advancement")
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
        SavedDataFileStore(paths, nbtFiles = NbtFileStore(base)).writeDocument("foo", initial)
        val sourceGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val world = concurrencyWorld(paths, fileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                world.readSavedDataDocument("foo", DimensionDirectory.Overworld)
            }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.writeSavedDataDocument("minecraft:foo", replacement, DimensionDirectory.Overworld)
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(1, world.activeMetadataEntryCount())
            assertEquals(2, world.activeMetadataUsers())

            sourceGate.open()
            assertEquals(initial, reader.await())
            writer.await()
            assertEquals(replacement, world.readSavedDataDocument("foo", DimensionDirectory.Overworld))
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
            val reader = async(Dispatchers.Default) { world.readStatisticsText(player) }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.writeStatisticsText(player, "replacement")
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
            assertFailsWith<IllegalStateException> { world.readStatisticsText(player) }

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
        seedConcurrencyRegion(base, checkNotNull(target.parent))
        val readGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, readGate = readGate)
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                world.readCompressedChunk(
                    ChunkPosition(0, 0),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += reader
            readGate.awaitEntered()
            val sameFileWriter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.writeCompressedChunk(
                    ChunkPosition(1, 0),
                    concurrencyChunk(1),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += sameFileWriter
            val otherFileWriter = async(Dispatchers.Default) {
                world.writeCompressedChunk(
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
            assertEquals(1, world.activeRegionStorageCount())

            readGate.open()
            reader.await()
            sameFileWriter.await()
            assertEquals(0, world.activeRegionStorageCount())
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
    fun worldCloseWaitsForAnExplicitRegionAndReleasesTheDirectoryLockLast() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, base, lock)
        val region = world.openRegion(
            RegionPosition(0, 0),
            RegionStorageDirectory.CHUNKS,
            DimensionDirectory.Overworld,
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            region.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(1))
            assertEquals(1, world.activeRegionStorageUsers())

            val closing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { world.close() }
            jobs += closing
            assertFalse(closing.isCompleted)
            assertTrue(lock.isValid)
            assertEquals(0, lock.closeAttempts.get())
            assertFailsWith<IllegalStateException> {
                world.readCompressedChunk(
                    ChunkPosition(0, 0),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }

            region.close()
            closing.await()
            assertFalse(lock.isValid)
            assertEquals(1, lock.closeAttempts.get())
            assertEquals(0, world.activeRegionStorageCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                region.close()
                jobs.joinAll()
                world.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringMetadataWriteCompletesTheFileBeforeReleasingItsEntry() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val playerUuid = "cancelled-writer"
        val target = paths.statistics(playerUuid)
        val statistics = PlayerStatistics(
            stats = mapOf("minecraft:custom" to mapOf("minecraft:play_time" to 7)),
            dataVersion = 4_903,
        )
        val base = concurrencyFakeFileSystem()
        val sinkGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, sinkGate = sinkGate)
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                world.writeStatistics(playerUuid, PlayerStatistics.serializer(), statistics)
                returned.complete(Unit)
            }
            jobs += writing
            sinkGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                world.readStatistics(playerUuid, PlayerStatistics.serializer()).also {
                    readerReturned.complete(Unit)
                }
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
            assertEquals(statistics, reading.await())
            assertEquals(0, world.activeMetadataEntryCount())
            assertEquals(statistics, world.readStatistics(playerUuid, PlayerStatistics.serializer()))
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
                world.writeCompressedChunk(
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
                world.readCompressedChunk(
                    position,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                ).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(1, world.activeRegionStorageCount())
            assertEquals(2, world.activeRegionStorageUsers())

            val cancellation = CancellationException("cancelled during nested region write")
            writing.cancel(cancellation)
            writeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellation.message, failure.message)
            assertFalse(returned.isCompleted)
            assertContentEquals(
                byteArrayOf(5),
                reading.await().bytesOrNull(),
            )
            assertEquals(0, world.activeRegionStorageCount())
            assertContentEquals(
                byteArrayOf(5),
                world.readCompressedChunk(
                    position,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                ).bytesOrNull(),
            )
            assertEquals(0, world.activeRegionStorageCount())
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
    fun flushKeepsCancellationPrimaryAcrossRegionStoragesAndOnlyReleasesRemainingPins() = runTest {
        val paths = MinecraftWorldPaths("/world".toPath())
        val position = ChunkPosition(0, 0)
        val storageDirectories = RegionStorageDirectory.entries
        val base = concurrencyFakeFileSystem()
        storageDirectories.forEachIndexed { index, storage ->
            val setup = RegionStorage(
                paths = paths,
                storage = storage,
                fileSystem = base,
                configuration = RegionStorageConfiguration(syncWrites = false),
            )
            setup.writeChunkNbtDocument(position, concurrencyDocument(index), Compression.NONE)
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
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = RegionStorageConfiguration(
                syncWrites = false,
                writeCompression = Compression.NONE,
            ),
            directoryLock = lock,
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reading = storageDirectories.map { storage ->
                async(Dispatchers.Default) {
                    world.withCompressedChunkSource(position, storage, DimensionDirectory.Overworld) { _, source ->
                        readGate.awaitRelease()
                        source.readByteArray()
                    }
                }
            }
            jobs += reading
            readGate.awaitEntered()
            val encoding = storageDirectories.mapIndexed { index, storage ->
                async(Dispatchers.Default) {
                    world.writeChunkNbtDocument(
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
            assertEquals(storageDirectories.size, world.activeRegionStorageCount())

            encodeGate.open()
            encoding.awaitAll()
            assertEquals(0, world.activeRegionStorageCount())
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
        seedConcurrencyRegion(base, checkNotNull(paths.regionFile(position.region).parent), position)
        val fileSystem = GatedFileSystem(
            base = base,
            target = paths.regionFile(position.region),
            closeFailures = 1,
        )
        val lock = RecordingWorldDirectoryLock()
        val world = concurrencyWorld(paths, fileSystem, lock)
        try {
            val operationFailure = assertFailsWith<IOException> {
                world.readCompressedChunk(
                    position,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertEquals(0, world.activeRegionStorageCount())
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
    regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
    directoryLock = lock,
)
