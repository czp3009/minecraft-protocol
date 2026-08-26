package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.*

class OpenMinecraftWorldConcurrencyTest {
    @Test
    fun repeatedMetadataAccessDoesNotRetainLogicalFileEntries() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, base)
        try {
            repeat(2_048) { index ->
                assertNull(openMinecraftWorld.readPlayerDataDocument("player_$index"))
                assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            }
            base.checkNoOpenFiles()
        } finally {
            openMinecraftWorld.close()
            base.checkNoOpenFiles()
        }
    }

    @Test
    fun levelReadersShareUntilOfficialRecoveryNeedsExclusiveAccess() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val levelDataStore = LevelDataStore(minecraftWorldPaths, NbtFileStore(base))
        val fallback = concurrencyDocument(1)
        levelDataStore.writeDocument(fallback)
        levelDataStore.writeDocument(concurrencyDocument(2))
        base.write(minecraftWorldPaths.levelData) { writeByte(0) }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(base, minecraftWorldPaths.levelData, sourceGate = sourceGate)
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { openMinecraftWorld.readLevelDataDocument() }
            val second = async(Dispatchers.Default) { openMinecraftWorld.readLevelDataDocument() }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, openMinecraftWorld.activeMetadataUsers())

            sourceGate.open()
            assertEquals(fallback, first.await())
            assertEquals(fallback, second.await())
            assertEquals(fallback, NbtFileStore(base).readDocument(minecraftWorldPaths.levelData))
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun healthyPlayerDataReadersShareLogicalFileAccess() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val nbtDocument = concurrencyDocument(3)
        val base = concurrencyFakeFileSystem()
        PlayerDataStore(minecraftWorldPaths, NbtFileStore(base)).writeDocument(player, nbtDocument)
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(base, minecraftWorldPaths.playerData(player), sourceGate = sourceGate)
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { openMinecraftWorld.readPlayerDataDocument(player) }
            val second = async(Dispatchers.Default) { openMinecraftWorld.readPlayerDataDocument(player) }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, openMinecraftWorld.activeMetadataUsers())

            sourceGate.open()
            assertEquals(nbtDocument, first.await())
            assertEquals(nbtDocument, second.await())
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun advancementReadersShareLogicalFileAccess() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val target = minecraftWorldPaths.advancement(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("{\"value\":1}") }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { openMinecraftWorld.readAdvancementsText(player) }
            val second = async(Dispatchers.Default) { openMinecraftWorld.readAdvancementsText(player) }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(1, openMinecraftWorld.activeMetadataEntryCount())
            assertEquals(2, openMinecraftWorld.activeMetadataUsers())

            sourceGate.open()
            assertEquals("{\"value\":1}", first.await())
            assertEquals("{\"value\":1}", second.await())
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
        assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
    }

    @Test
    fun statisticsReadersShareLogicalFileAccess() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val target = minecraftWorldPaths.statistics(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("{\"value\":2}") }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { openMinecraftWorld.readStatisticsText(player) }
            val second = async(Dispatchers.Default) { openMinecraftWorld.readStatisticsText(player) }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, openMinecraftWorld.activeMetadataUsers())

            sourceGate.open()
            assertEquals("{\"value\":2}", first.await())
            assertEquals("{\"value\":2}", second.await())
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun typedTreeTextAndRawStatisticsShareOneWriterPreferringBoundary() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "typed-player"
        val target = minecraftWorldPaths.statistics(player)
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
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = target,
            sourceGate = sourceGate,
            sinkGate = sinkGate,
        )
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val typed = async(Dispatchers.Default) {
                openMinecraftWorld.readStatistics(player, PlayerStatistics.serializer())
            }
            val tree = async(Dispatchers.Default) {
                openMinecraftWorld.readStatistics(player, JsonElement.serializer())
            }
            val raw = async(Dispatchers.Default) {
                openMinecraftWorld.readStatistics(player) { source -> source.readString() }
            }
            jobs += typed
            jobs += tree
            jobs += raw
            sourceGate.awaitEntered()
            assertEquals(3, openMinecraftWorld.activeMetadataUsers())

            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                openMinecraftWorld.writeStatistics(player, PlayerStatistics.serializer(), replacement)
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(4, openMinecraftWorld.activeMetadataUsers())

            sourceGate.open()
            assertEquals(initial, typed.await())
            assertEquals(initial, Json.decodeFromJsonElement<PlayerStatistics>(tree.await()))
            assertEquals(initial, Json.decodeFromString<PlayerStatistics>(raw.await()))
            sinkGate.awaitEntered()

            val text = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                openMinecraftWorld.readStatisticsText(player)
            }
            jobs += text
            assertFalse(text.isCompleted)
            sinkGate.open()
            writer.await()
            assertEquals(replacement, Json.decodeFromString<PlayerStatistics>(text.await()))
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                sinkGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun statisticsWriterIsExclusiveWithoutBlockingAnotherLogicalFile() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val statistics = minecraftWorldPaths.statistics(player)
        val advancements = minecraftWorldPaths.advancement(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(statistics.parent))
        base.createDirectories(checkNotNull(advancements.parent))
        base.write(statistics) { writeUtf8("old") }
        base.write(advancements) { writeUtf8("old-advancement") }
        val sinkGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, statistics, sinkGate = sinkGate)
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writer = async(Dispatchers.Default) {
                openMinecraftWorld.writeStatisticsText(player, "new")
            }
            jobs += writer
            sinkGate.awaitEntered()
            val sameFileReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                openMinecraftWorld.readStatisticsText(player)
            }
            jobs += sameFileReader
            assertFalse(sameFileReader.isCompleted)

            val independent = async(Dispatchers.Default) {
                openMinecraftWorld.writeAdvancementsText(player, "new-advancement")
            }
            jobs += independent
            independent.await()
            assertFalse(sameFileReader.isCompleted)
            assertEquals("new-advancement", base.read(advancements) { readUtf8() })

            sinkGate.open()
            writer.await()
            assertEquals("new", sameFileReader.await())
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sinkGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun canonicalSavedDataAliasesShareOneExclusiveBoundary() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val target = minecraftWorldPaths.savedData("minecraft:foo")
        val base = concurrencyFakeFileSystem()
        val initial = concurrencyDocument(1)
        val replacement = concurrencyDocument(2)
        SavedDataFileStore(minecraftWorldPaths, nbtFileStore = NbtFileStore(base)).writeDocument("foo", initial)
        val sourceGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                openMinecraftWorld.readSavedDataDocument("foo", DimensionDirectory.Overworld)
            }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                openMinecraftWorld.writeSavedDataDocument("minecraft:foo", replacement, DimensionDirectory.Overworld)
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(1, openMinecraftWorld.activeMetadataEntryCount())
            assertEquals(2, openMinecraftWorld.activeMetadataUsers())

            sourceGate.open()
            assertEquals(initial, reader.await())
            writer.await()
            assertEquals(replacement, openMinecraftWorld.readSavedDataDocument("foo", DimensionDirectory.Overworld))
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun closeWaitsForMetadataReaderAndQueuedWriterThenReleasesDirectoryLock() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val target = minecraftWorldPaths.statistics(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("value") }
        val sourceGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) { openMinecraftWorld.readStatisticsText(player) }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                openMinecraftWorld.writeStatisticsText(player, "replacement")
            }
            jobs += writer
            assertEquals(2, openMinecraftWorld.activeMetadataUsers())
            val firstClose =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { openMinecraftWorld.close() }
            val secondClose =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { openMinecraftWorld.close() }
            jobs += firstClose
            jobs += secondClose
            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)
            assertTrue(recordingWorldDirectoryLock.isValid)
            assertEquals(0, recordingWorldDirectoryLock.closeAttempts.get())
            assertFailsWith<IllegalStateException> { openMinecraftWorld.readStatisticsText(player) }

            sourceGate.open()
            assertEquals("value", reader.await())
            writer.await()
            firstClose.await()
            secondClose.await()
            assertFalse(recordingWorldDirectoryLock.isValid)
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
            assertEquals("replacement", base.read(target) { readUtf8() })
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun directoryLockDoesNotSerializeDifferentRegionFiles() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val target = minecraftWorldPaths.regionFile(ChunkPosition(0, 0).regionPosition)
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, checkNotNull(target.parent))
        val readGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, readGate = readGate)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                openMinecraftWorld.readCompressedChunk(
                    ChunkPosition(0, 0),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += reader
            readGate.awaitEntered()
            val sameFileWriter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                openMinecraftWorld.writeCompressedChunk(
                    ChunkPosition(1, 0),
                    concurrencyChunk(1),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += sameFileWriter
            val otherFileWriter = async(Dispatchers.Default) {
                openMinecraftWorld.writeCompressedChunk(
                    ChunkPosition(32, 0),
                    concurrencyChunk(2),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            jobs += otherFileWriter
            otherFileWriter.await()
            assertFalse(sameFileWriter.isCompleted)
            assertTrue(recordingWorldDirectoryLock.isValid)
            assertEquals(1, openMinecraftWorld.activeRegionStorageCount())

            readGate.open()
            reader.await()
            sameFileWriter.await()
            assertEquals(0, openMinecraftWorld.activeRegionStorageCount())
            assertTrue(recordingWorldDirectoryLock.isValid)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
        assertFalse(recordingWorldDirectoryLock.isValid)
        assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
    }

    @Test
    fun worldCloseWaitsForAnExplicitRegionAndReleasesTheDirectoryLockLast() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, base, recordingWorldDirectoryLock)
        val regionHandle = openMinecraftWorld.openRegion(
            RegionPosition(0, 0),
            RegionStorageDirectory.CHUNKS,
            DimensionDirectory.Overworld,
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            regionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(1))
            assertEquals(1, openMinecraftWorld.activeRegionStorageUsers())

            val closing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { openMinecraftWorld.close() }
            jobs += closing
            assertFalse(closing.isCompleted)
            assertTrue(recordingWorldDirectoryLock.isValid)
            assertEquals(0, recordingWorldDirectoryLock.closeAttempts.get())
            assertFailsWith<IllegalStateException> {
                openMinecraftWorld.readCompressedChunk(
                    ChunkPosition(0, 0),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }

            regionHandle.close()
            closing.await()
            assertFalse(recordingWorldDirectoryLock.isValid)
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
            assertEquals(0, openMinecraftWorld.activeRegionStorageCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                regionHandle.close()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringMetadataWriteCompletesTheFileBeforeReleasingItsEntry() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val playerUuid = "cancelled-writer"
        val target = minecraftWorldPaths.statistics(playerUuid)
        val playerStatistics = PlayerStatistics(
            stats = mapOf("minecraft:custom" to mapOf("minecraft:play_time" to 7)),
            dataVersion = 4_903,
        )
        val base = concurrencyFakeFileSystem()
        val sinkGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, sinkGate = sinkGate)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                openMinecraftWorld.writeStatistics(playerUuid, PlayerStatistics.serializer(), playerStatistics)
                returned.complete(Unit)
            }
            jobs += writing
            sinkGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                openMinecraftWorld.readStatistics(playerUuid, PlayerStatistics.serializer()).also {
                    readerReturned.complete(Unit)
                }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(1, openMinecraftWorld.activeMetadataEntryCount())
            assertEquals(2, openMinecraftWorld.activeMetadataUsers())

            val cancellationException = CancellationException("cancelled during metadata write")
            writing.cancel(cancellationException)
            sinkGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertEquals(playerStatistics, reading.await())
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            assertEquals(playerStatistics, openMinecraftWorld.readStatistics(playerUuid, PlayerStatistics.serializer()))
            assertEquals(0, openMinecraftWorld.activeMetadataEntryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sinkGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                assertFalse(recordingWorldDirectoryLock.isValid)
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancellationDuringNestedRegionWriteCompletesAValidCommitAndReleasesBothStateLayers() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val chunkPosition = ChunkPosition(0, 0)
        val target = minecraftWorldPaths.regionFile(chunkPosition.regionPosition)
        val base = concurrencyFakeFileSystem()
        val writeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, writeGate = writeGate)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                openMinecraftWorld.writeCompressedChunk(
                    chunkPosition,
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
                openMinecraftWorld.readCompressedChunk(
                    chunkPosition,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                ).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(1, openMinecraftWorld.activeRegionStorageCount())
            assertEquals(2, openMinecraftWorld.activeRegionStorageUsers())

            val cancellationException = CancellationException("cancelled during nested region write")
            writing.cancel(cancellationException)
            writeGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertContentEquals(
                byteArrayOf(5),
                reading.await().bytesOrNull(),
            )
            assertEquals(0, openMinecraftWorld.activeRegionStorageCount())
            assertContentEquals(
                byteArrayOf(5),
                openMinecraftWorld.readCompressedChunk(
                    chunkPosition,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                ).bytesOrNull(),
            )
            assertEquals(0, openMinecraftWorld.activeRegionStorageCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                assertFalse(recordingWorldDirectoryLock.isValid)
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun cancelledCloseOwnerFinishesDirectoryLockCleanupAndPublishesSuccess() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val closeGate = BlockingGate()
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock(closeGate)
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, base, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val closing = async(Dispatchers.Default) {
                openMinecraftWorld.close()
                returned.complete(Unit)
            }
            jobs += closing
            closeGate.awaitEntered()

            val cancellationException = CancellationException("world close owner cancelled")
            closing.cancel(cancellationException)
            closeGate.open()
            val failure = assertFailsWith<CancellationException> { closing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertFalse(recordingWorldDirectoryLock.isValid)
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
            base.checkNoOpenFiles()

            openMinecraftWorld.close()
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun flushKeepsCancellationPrimaryAcrossRegionStoragesAndOnlyReleasesRemainingPins() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val chunkPosition = ChunkPosition(0, 0)
        val storageDirectories = RegionStorageDirectory.entries
        val base = concurrencyFakeFileSystem()
        storageDirectories.forEachIndexed { index, regionStorageDirectory ->
            val setup = RegionStorage(
                minecraftWorldPaths = minecraftWorldPaths,
                regionStorageDirectory = regionStorageDirectory,
                fileSystem = base,
                regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
            )
            setup.writeChunkNbtDocument(chunkPosition, concurrencyDocument(index), Compression.NONE)
            setup.close()
        }

        val earlierFailure = IOException("synthetic world flush failure before cancellation")
        val cancellationException = CancellationException("synthetic world flush cancellation")
        val sequencedFlushFailureFileSystem = SequencedFlushFailureFileSystem(
            delegate = threadSafeFakeFileSystem(base),
            failures = listOf(earlierFailure, cancellationException),
        )
        val readGate = BlockingGate(expectedEntrants = storageDirectories.size)
        val encodeGate = BlockingGate(expectedEntrants = storageDirectories.size)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = OpenMinecraftWorld(
            minecraftWorldPaths = minecraftWorldPaths,
            worldFileAccess = WorldFileAccess.mutable(sequencedFlushFailureFileSystem),
            chunkNbtFormat = gatedNbtFormat(encodeGate),
            regionStorageConfiguration = RegionStorageConfiguration(
                syncWrites = false,
                writeCompression = Compression.NONE,
            ),
            worldDirectoryLock = recordingWorldDirectoryLock,
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reading = storageDirectories.map { regionStorageDirectory ->
                async(Dispatchers.Default) {
                    openMinecraftWorld.withCompressedChunkSource(chunkPosition, regionStorageDirectory, DimensionDirectory.Overworld) { _, source ->
                        readGate.awaitRelease()
                        source.readByteArray()
                    }
                }
            }
            jobs += reading
            readGate.awaitEntered()
            val encoding = storageDirectories.mapIndexed { index, regionStorageDirectory ->
                async(Dispatchers.Default) {
                    openMinecraftWorld.writeChunkNbtDocument(
                        chunkPosition,
                        concurrencyDocument(index + 10),
                        regionStorageDirectory,
                        DimensionDirectory.Overworld,
                    )
                }
            }
            jobs += encoding
            encodeGate.awaitEntered()
            readGate.open()
            reading.awaitAll()

            val failure = assertFailsWith<CancellationException> { openMinecraftWorld.flush() }

            assertSame(cancellationException, failure)
            assertSame(earlierFailure, failure.suppressedExceptions.single())
            assertEquals(2, sequencedFlushFailureFileSystem.flushAttempts.get())
            assertEquals(storageDirectories.size, openMinecraftWorld.activeRegionStorageCount())

            encodeGate.open()
            encoding.awaitAll()
            assertEquals(0, openMinecraftWorld.activeRegionStorageCount())
            openMinecraftWorld.close()
            assertFalse(recordingWorldDirectoryLock.isValid)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                encodeGate.open()
                jobs.joinAll()
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun completedCloseFailureIsReportedToLaterCloseCallers() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val expected = IOException("synthetic directory lock close failure")
        var closeAttempts = 0
        val worldDirectoryLock = object : WorldDirectoryLock {
            private var valid = true

            override val isValid: Boolean
                get() = valid

            override fun close() {
                closeAttempts++
                valid = false
                throw expected
            }
        }
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, base, worldDirectoryLock)

        val first = assertFailsWith<IOException> { openMinecraftWorld.close() }
        val later = assertFailsWith<IOException> { openMinecraftWorld.close() }

        assertSame(expected, first)
        assertSame(first, later)
        assertEquals(1, closeAttempts)
        assertFalse(worldDirectoryLock.isValid)
        base.checkNoOpenFiles()
    }

    @Test
    fun completedRegionCleanupFailureDoesNotPoisonLaterWorldClose() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val chunkPosition = ChunkPosition(0, 0)
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(base, checkNotNull(minecraftWorldPaths.regionFile(chunkPosition.regionPosition).parent), chunkPosition)
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = minecraftWorldPaths.regionFile(chunkPosition.regionPosition),
            closeFailures = 1,
        )
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val openMinecraftWorld = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        try {
            val operationFailure = assertFailsWith<IOException> {
                openMinecraftWorld.readCompressedChunk(
                    chunkPosition,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )
            }
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertEquals(0, openMinecraftWorld.activeRegionStorageCount())
            assertTrue(recordingWorldDirectoryLock.isValid)

            openMinecraftWorld.close()

            assertFalse(recordingWorldDirectoryLock.isValid)
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
            assertEquals(1, gatedFileSystem.closes.get())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                openMinecraftWorld.close()
                base.checkNoOpenFiles()
            }
        }
    }
}

private fun concurrencyWorld(
    minecraftWorldPaths: MinecraftWorldPaths,
    fileSystem: okio.FileSystem,
    worldDirectoryLock: WorldDirectoryLock = RecordingWorldDirectoryLock(),
): OpenMinecraftWorld = OpenMinecraftWorld(
    minecraftWorldPaths = minecraftWorldPaths,
    worldFileAccess = WorldFileAccess.mutable(
        if (fileSystem is okio.fakefilesystem.FakeFileSystem) {
            threadSafeFakeFileSystem(fileSystem)
        } else {
            fileSystem
        },
    ),
    regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
    worldDirectoryLock = worldDirectoryLock,
)
