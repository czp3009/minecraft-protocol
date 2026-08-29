package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okio.IOException
import okio.Path.Companion.toPath
import java.util.concurrent.Executors
import kotlin.test.*

class MinecraftWorldAccessConcurrencyTest {
    @Test
    fun distinctWorldResourcesWriteConcurrentlyAndReuseOnePhysicalOpenEach() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val regionPosition = RegionPosition(0, 0)
        val chunkRegionPath = minecraftWorldPaths.regionFile(regionPosition)
        val entityRegionPath = minecraftWorldPaths.regionFile(
            regionPosition,
            RegionStorageDirectory.ENTITIES,
        )
        val poiRegionPath = minecraftWorldPaths.regionFile(
            regionPosition,
            RegionStorageDirectory.POINTS_OF_INTEREST,
        )
        val chunkRegionGate = BlockingGate()
        val entityRegionGate = BlockingGate()
        val poiRegionGate = BlockingGate()
        val levelDataGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = chunkRegionPath,
            writeGate = chunkRegionGate,
            additionalWriteGates = mapOf(
                entityRegionPath to entityRegionGate,
                poiRegionPath to poiRegionGate,
            ),
            dynamicWriteGate = { path ->
                levelDataGate.takeIf {
                    path.parent == minecraftWorldPaths.root && path.name.startsWith(".tmp-")
                }
            },
        )
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val overworld = minecraftWorldAccess.dimensions.overworld
        val regionHandle = overworld.openRegion(regionPosition)
        val entityRegionHandle = overworld.openEntityRegion(regionPosition)
        val poiRegionHandle = overworld.openPoiRegion(regionPosition)
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val chunkWrite = async(dispatcher) {
                regionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(1))
            }
            val entityWrite = async(dispatcher) {
                entityRegionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(2))
            }
            val poiWrite = async(dispatcher) {
                poiRegionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(3))
            }
            val levelDataWrite = async(dispatcher) {
                minecraftWorldAccess.writeLevelDataDocument(concurrencyDocument(4))
            }
            jobs += listOf(chunkWrite, entityWrite, poiWrite, levelDataWrite)

            chunkRegionGate.awaitEntered()
            entityRegionGate.awaitEntered()
            poiRegionGate.awaitEntered()
            levelDataGate.awaitEntered()
            assertEquals(4, gatedFileSystem.activeWrites.get())
            assertEquals(4, gatedFileSystem.maximumConcurrentWrites.get())
            assertEquals(3, minecraftWorldAccess.activeRegionDirectoryCount())
            assertEquals(1, minecraftWorldAccess.activeLogicalResourceCount())

            chunkRegionGate.open()
            entityRegionGate.open()
            poiRegionGate.open()
            levelDataGate.open()
            jobs.awaitAll()

            assertContentEquals(
                byteArrayOf(1),
                regionHandle.readCompressedChunk(LocalChunkPosition(0, 0)).bytesOrNull(),
            )
            assertContentEquals(
                byteArrayOf(2),
                entityRegionHandle.readCompressedChunk(LocalChunkPosition(0, 0)).bytesOrNull(),
            )
            assertContentEquals(
                byteArrayOf(3),
                poiRegionHandle.readCompressedChunk(LocalChunkPosition(0, 0)).bytesOrNull(),
            )
            val openPaths = gatedFileSystem.trackedOpenPaths.toList()
            assertEquals(4, openPaths.size)
            assertEquals(4, openPaths.distinct().size)
            assertEquals(1, openPaths.count { it == chunkRegionPath })
            assertEquals(1, openPaths.count { it == entityRegionPath })
            assertEquals(1, openPaths.count { it == poiRegionPath })
            assertEquals(
                1,
                openPaths.count { it.parent == minecraftWorldPaths.root && it.name.startsWith(".tmp-") },
            )
            assertEquals(1, gatedFileSystem.trackedClosePaths.size)

            regionHandle.close()
            entityRegionHandle.close()
            poiRegionHandle.close()
            assertEquals(openPaths.toSet(), gatedFileSystem.trackedClosePaths.toSet())
            assertEquals(4, gatedFileSystem.trackedClosePaths.size)
        } finally {
            withContext(NonCancellable) {
                chunkRegionGate.open()
                entityRegionGate.open()
                poiRegionGate.open()
                levelDataGate.open()
                jobs.joinAll()
                regionHandle.close()
                entityRegionHandle.close()
                poiRegionHandle.close()
                minecraftWorldAccess.close()
                dispatcher.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun publicWorldWritesMultipleChunksAcrossRegionsAtRegionGranularity() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val firstRegionPosition = RegionPosition(0, 0)
        val secondRegionPosition = RegionPosition(1, 0)
        val firstGate = BlockingGate()
        val secondGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = concurrencyFakeFileSystem(),
            target = minecraftWorldPaths.regionFile(firstRegionPosition),
            writeGate = firstGate,
            additionalWriteGates = mapOf(minecraftWorldPaths.regionFile(secondRegionPosition) to secondGate),
        )
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val firstRegionHandle = minecraftWorldAccess.dimensions.overworld.openRegion(firstRegionPosition)
        val secondRegionHandle = minecraftWorldAccess.dimensions.overworld.openRegion(secondRegionPosition)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val firstRegionWrite = async(Dispatchers.Default) {
                firstRegionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(1))
            }
            jobs += firstRegionWrite
            firstGate.awaitEntered()

            val secondRegionWrite = async(Dispatchers.Default) {
                secondRegionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(2))
            }
            jobs += secondRegionWrite
            secondGate.awaitEntered()

            val queuedFirstRegionWrite = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                firstRegionHandle.writeCompressedChunk(LocalChunkPosition(1, 0), concurrencyChunk(3))
            }
            val queuedSecondRegionWrite = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                secondRegionHandle.writeCompressedChunk(LocalChunkPosition(1, 0), concurrencyChunk(4))
            }
            jobs += queuedFirstRegionWrite
            jobs += queuedSecondRegionWrite

            assertFalse(queuedFirstRegionWrite.isCompleted)
            assertFalse(queuedSecondRegionWrite.isCompleted)
            assertEquals(2, gatedFileSystem.activeWrites.get())
            assertEquals(2, gatedFileSystem.maximumConcurrentWrites.get())

            firstGate.open()
            secondGate.open()
            jobs.awaitAll()

            assertContentEquals(
                byteArrayOf(1),
                firstRegionHandle.readCompressedChunk(LocalChunkPosition(0, 0)).bytesOrNull(),
            )
            assertContentEquals(
                byteArrayOf(3),
                firstRegionHandle.readCompressedChunk(LocalChunkPosition(1, 0)).bytesOrNull(),
            )
            assertContentEquals(
                byteArrayOf(2),
                secondRegionHandle.readCompressedChunk(LocalChunkPosition(0, 0)).bytesOrNull(),
            )
            assertContentEquals(
                byteArrayOf(4),
                secondRegionHandle.readCompressedChunk(LocalChunkPosition(1, 0)).bytesOrNull(),
            )
        } finally {
            withContext(NonCancellable) {
                firstGate.open()
                secondGate.open()
                jobs.joinAll()
                firstRegionHandle.close()
                secondRegionHandle.close()
                minecraftWorldAccess.close()
                gatedFileSystem.base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun repeatedMetadataAccessDoesNotRetainLogicalFileEntries() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val base = concurrencyFakeFileSystem()
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, base)
        try {
            repeat(2_048) { index ->
                assertNull(minecraftWorldAccess.players.readDataDocument("player_$index"))
                assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            }
            base.checkNoOpenFiles()
        } finally {
            minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { minecraftWorldAccess.readLevelDataDocument() }
            val second = async(Dispatchers.Default) { minecraftWorldAccess.readLevelDataDocument() }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, minecraftWorldAccess.activeLogicalResourceUsers())

            sourceGate.open()
            assertEquals(fallback, first.await())
            assertEquals(fallback, second.await())
            assertEquals(fallback, NbtFileStore(base).readDocument(minecraftWorldPaths.levelData))
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) { minecraftWorldAccess.players.readDataDocument(player) }
            val second = async(Dispatchers.Default) { minecraftWorldAccess.players.readDataDocument(player) }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, minecraftWorldAccess.activeLogicalResourceUsers())

            sourceGate.open()
            assertEquals(nbtDocument, first.await())
            assertEquals(nbtDocument, second.await())
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun advancementReadersShareLogicalFileAccess() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val target = minecraftWorldPaths.advancements(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("{\"value\":1}") }
        val sourceGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readAdvancements(player) { source -> source.readUtf8() }
            }
            val second = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readAdvancements(player) { source -> source.readUtf8() }
            }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(1, minecraftWorldAccess.activeLogicalResourceCount())
            assertEquals(2, minecraftWorldAccess.activeLogicalResourceUsers())

            sourceGate.open()
            assertEquals("{\"value\":1}", first.await())
            assertEquals("{\"value\":1}", second.await())
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val first = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }
            }
            val second = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }
            }
            jobs += first
            jobs += second
            sourceGate.awaitEntered()
            assertEquals(2, minecraftWorldAccess.activeLogicalResourceUsers())

            sourceGate.open()
            assertEquals("{\"value\":2}", first.await())
            assertEquals("{\"value\":2}", second.await())
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun typedTreeAndRawStatisticsShareOneWriterPreferringBoundary() = runTest {
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
        Utf8JsonFileStore(base).writeJson(target, initial, PlayerStatistics.serializer())
        val sourceGate = BlockingGate(expectedEntrants = 3)
        val sinkGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = target,
            sourceGate = sourceGate,
            sinkGate = sinkGate,
        )
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val typed = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readStatistics(player, PlayerStatistics.serializer())
            }
            val tree = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readStatistics(player, JsonElement.serializer())
            }
            val raw = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }
            }
            jobs += typed
            jobs += tree
            jobs += raw
            sourceGate.awaitEntered()
            assertEquals(3, minecraftWorldAccess.activeLogicalResourceUsers())

            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.players.writeStatistics(
                    player,
                    replacement,
                    PlayerStatistics.serializer(),
                )
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(4, minecraftWorldAccess.activeLogicalResourceUsers())

            sourceGate.open()
            assertEquals(initial, typed.await())
            assertEquals(initial, Json.decodeFromJsonElement<PlayerStatistics>(checkNotNull(tree.await())))
            assertEquals(initial, Json.decodeFromString<PlayerStatistics>(checkNotNull(raw.await())))
            sinkGate.awaitEntered()

            val rawAfterWrite = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }
            }
            jobs += rawAfterWrite
            assertFalse(rawAfterWrite.isCompleted)
            sinkGate.open()
            writer.await()
            assertEquals(replacement, Json.decodeFromString<PlayerStatistics>(checkNotNull(rawAfterWrite.await())))
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                sinkGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun statisticsWriterIsExclusiveWithoutBlockingAnotherLogicalFile() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "player"
        val statistics = minecraftWorldPaths.statistics(player)
        val advancements = minecraftWorldPaths.advancements(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(statistics.parent))
        base.createDirectories(checkNotNull(advancements.parent))
        base.write(statistics) { writeUtf8("old") }
        base.write(advancements) { writeUtf8("old-advancement") }
        val sinkGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, statistics, sinkGate = sinkGate)
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val writer = async(Dispatchers.Default) {
                minecraftWorldAccess.players.writeStatistics(player) { sink -> sink.writeUtf8("new") }
            }
            jobs += writer
            sinkGate.awaitEntered()
            val sameFileReader = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }
            }
            jobs += sameFileReader
            assertFalse(sameFileReader.isCompleted)

            val independent = async(Dispatchers.Default) {
                minecraftWorldAccess.players.writeAdvancements(player) { sink -> sink.writeUtf8("new-advancement") }
            }
            jobs += independent
            independent.await()
            assertFalse(sameFileReader.isCompleted)
            assertEquals("new-advancement", base.read(advancements) { readUtf8() })

            sinkGate.open()
            writer.await()
            assertEquals("new", sameFileReader.await())
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            assertTrue(base.allPaths.none { it.name.startsWith(".tmp-") })
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sinkGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun dimensionSavedDataReadsAndWritesShareOneExclusiveBoundary() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val savedDataId = SavedDataId("foo")
        val savedDataScope = SavedDataScope.Dimension(DimensionId.Overworld)
        val target = minecraftWorldPaths.savedData(savedDataId, savedDataScope)
        val base = concurrencyFakeFileSystem()
        val initial = concurrencyDocument(1)
        val replacement = concurrencyDocument(2)
        SavedDataStore(
            minecraftWorldPaths,
            savedDataScope,
            NbtFileStore(base),
        ).writeDocument(savedDataId, initial)
        val sourceGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, sourceGate = sourceGate)
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                minecraftWorldAccess.dimensions.overworld.data.readDocument(savedDataId)
            }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.dimensions.overworld.data.writeDocument(savedDataId, replacement)
            }
            jobs += writer
            assertFalse(writer.isCompleted)
            assertEquals(1, minecraftWorldAccess.activeLogicalResourceCount())
            assertEquals(2, minecraftWorldAccess.activeLogicalResourceUsers())

            sourceGate.open()
            assertEquals(initial, reader.await())
            writer.await()
            assertEquals(
                replacement,
                minecraftWorldAccess.dimensions.overworld.data.readDocument(savedDataId),
            )
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }
            }
            jobs += reader
            sourceGate.awaitEntered()
            val writer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.players.writeStatistics(player) { sink -> sink.writeUtf8("replacement") }
            }
            jobs += writer
            assertEquals(2, minecraftWorldAccess.activeLogicalResourceUsers())
            val firstClose =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { minecraftWorldAccess.close() }
            val secondClose =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { minecraftWorldAccess.close() }
            jobs += firstClose
            jobs += secondClose
            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)
            assertTrue(recordingWorldDirectoryLock.isValid)
            assertEquals(0, recordingWorldDirectoryLock.closeAttempts.get())
            assertFailsWith<IllegalStateException> {
                minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }
            }

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
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reader = async(Dispatchers.Default) {
                minecraftWorldAccess.readCompressedChunk(
                    ChunkPosition(0, 0),
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                )
            }
            jobs += reader
            readGate.awaitEntered()
            val sameFileWriter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.writeCompressedChunk(
                    ChunkPosition(1, 0),
                    concurrencyChunk(1),
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                )
            }
            jobs += sameFileWriter
            val otherFileWriter = async(Dispatchers.Default) {
                minecraftWorldAccess.writeCompressedChunk(
                    ChunkPosition(32, 0),
                    concurrencyChunk(2),
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                )
            }
            jobs += otherFileWriter
            otherFileWriter.await()
            assertFalse(sameFileWriter.isCompleted)
            assertTrue(recordingWorldDirectoryLock.isValid)
            assertEquals(1, minecraftWorldAccess.activeRegionDirectoryCount())

            readGate.open()
            reader.await()
            sameFileWriter.await()
            assertEquals(0, minecraftWorldAccess.activeRegionDirectoryCount())
            assertTrue(recordingWorldDirectoryLock.isValid)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, base, recordingWorldDirectoryLock)
        val regionHandle = minecraftWorldAccess.dimensions.overworld.openRegion(RegionPosition(0, 0))
        val jobs = mutableListOf<Deferred<*>>()
        try {
            regionHandle.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(1))
            assertEquals(1, minecraftWorldAccess.activeRegionDirectoryUsers())

            val closing =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { minecraftWorldAccess.close() }
            jobs += closing
            assertFalse(closing.isCompleted)
            assertTrue(recordingWorldDirectoryLock.isValid)
            assertEquals(0, recordingWorldDirectoryLock.closeAttempts.get())
            assertFailsWith<IllegalStateException> {
                minecraftWorldAccess.readCompressedChunk(
                    ChunkPosition(0, 0),
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                )
            }

            regionHandle.close()
            closing.await()
            assertFalse(recordingWorldDirectoryLock.isValid)
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
            assertEquals(0, minecraftWorldAccess.activeRegionDirectoryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                regionHandle.close()
                jobs.joinAll()
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun repeatedDimensionSelectionSharesOneRegionEntryAndPhysicalHandle() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val regionPosition = RegionPosition(0, 0)
        val path = minecraftWorldPaths.regionFile(regionPosition, dimensionId = DimensionId.Overworld)
        val base = concurrencyFakeFileSystem()
        val countingFileSystem = CountingMutableRegionFileSystem(base, path)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val minecraftWorldAccess = concurrencyWorld(
            minecraftWorldPaths,
            countingFileSystem,
            recordingWorldDirectoryLock,
        )
        val overworldRegion = minecraftWorldAccess.dimensions.overworld.openRegion(regionPosition)
        val selectedRegion = minecraftWorldAccess.dimensions[DimensionId("overworld")].openRegion(regionPosition)
        try {
            assertEquals(1, minecraftWorldAccess.activeRegionDirectoryCount())
            assertEquals(2, minecraftWorldAccess.activeRegionDirectoryUsers())

            overworldRegion.writeCompressedChunk(LocalChunkPosition(0, 0), concurrencyChunk(7))
            assertContentEquals(
                byteArrayOf(7),
                selectedRegion.readCompressedChunk(LocalChunkPosition(0, 0)).bytesOrNull(),
            )
            assertEquals(1, countingFileSystem.mutableOpens)
        } finally {
            withContext(NonCancellable) {
                overworldRegion.close()
                selectedRegion.close()
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
        assertEquals(1, countingFileSystem.closes)
        assertFalse(recordingWorldDirectoryLock.isValid)
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                minecraftWorldAccess.players.writeStatistics(
                    playerUuid,
                    playerStatistics,
                    PlayerStatistics.serializer(),
                )
                returned.complete(Unit)
            }
            jobs += writing
            sinkGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.players.readStatistics(playerUuid, PlayerStatistics.serializer()).also {
                    readerReturned.complete(Unit)
                }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(1, minecraftWorldAccess.activeLogicalResourceCount())
            assertEquals(2, minecraftWorldAccess.activeLogicalResourceUsers())

            val cancellationException = CancellationException("cancelled during metadata write")
            writing.cancel(cancellationException)
            sinkGate.open()
            val failure = assertFailsWith<CancellationException> { writing.await() }

            assertEquals(cancellationException.message, failure.message)
            assertFalse(returned.isCompleted)
            assertEquals(playerStatistics, reading.await())
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            assertEquals(
                playerStatistics,
                minecraftWorldAccess.players.readStatistics(playerUuid, PlayerStatistics.serializer())
            )
            assertEquals(0, minecraftWorldAccess.activeLogicalResourceCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sinkGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val writing = async(Dispatchers.Default) {
                minecraftWorldAccess.writeCompressedChunk(
                    chunkPosition,
                    concurrencyChunk(5),
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                )
                returned.complete(Unit)
            }
            jobs += writing
            writeGate.awaitEntered()

            val readerReturned = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.readCompressedChunk(
                    chunkPosition,
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                ).also { readerReturned.complete(Unit) }
            }
            jobs += reading
            assertFalse(readerReturned.isCompleted)
            assertEquals(1, minecraftWorldAccess.activeRegionDirectoryCount())
            assertEquals(2, minecraftWorldAccess.activeRegionDirectoryUsers())

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
            assertEquals(0, minecraftWorldAccess.activeRegionDirectoryCount())
            assertContentEquals(
                byteArrayOf(5),
                minecraftWorldAccess.readCompressedChunk(
                    chunkPosition,
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                ).bytesOrNull(),
            )
            assertEquals(0, minecraftWorldAccess.activeRegionDirectoryCount())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                writeGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, base, recordingWorldDirectoryLock)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val returned = CompletableDeferred<Unit>()
            val closing = async(Dispatchers.Default) {
                minecraftWorldAccess.close()
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

            minecraftWorldAccess.close()
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
            val setup = CoordinatedRegionStore(
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
        val minecraftWorldAccess = MinecraftWorldAccess.create(
            minecraftWorldPaths,
            sequencedFlushFailureFileSystem,
            MinecraftWorldAccessConfiguration(
                chunkNbtFormat = gatedNbtFormat(encodeGate),
                regionStorageConfiguration = RegionStorageConfiguration(
                    syncWrites = false,
                    writeCompression = Compression.NONE,
                ),
            ),
            recordingWorldDirectoryLock,
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val reading = storageDirectories.map { regionStorageDirectory ->
                async(Dispatchers.Default) {
                    minecraftWorldAccess.withCompressedChunkSource(
                        chunkPosition,
                        regionStorageDirectory,
                        DimensionId.Overworld,
                    ) { _, source ->
                        readGate.awaitRelease()
                        source.readByteArray()
                    }
                }
            }
            jobs += reading
            readGate.awaitEntered()
            val encoding = storageDirectories.mapIndexed { index, regionStorageDirectory ->
                async(Dispatchers.Default) {
                    minecraftWorldAccess.writeChunkNbtDocument(
                        chunkPosition,
                        concurrencyDocument(index + 10),
                        regionStorageDirectory,
                        DimensionId.Overworld,
                    )
                }
            }
            jobs += encoding
            encodeGate.awaitEntered()
            readGate.open()
            reading.awaitAll()

            val failure = assertFailsWith<CancellationException> { minecraftWorldAccess.flush() }

            assertSame(cancellationException, failure)
            assertSame(earlierFailure, failure.suppressedExceptions.single())
            assertEquals(2, sequencedFlushFailureFileSystem.flushAttempts.get())
            assertEquals(storageDirectories.size, minecraftWorldAccess.activeRegionDirectoryCount())

            encodeGate.open()
            encoding.awaitAll()
            assertEquals(0, minecraftWorldAccess.activeRegionDirectoryCount())
            minecraftWorldAccess.close()
            assertFalse(recordingWorldDirectoryLock.isValid)
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                readGate.open()
                encodeGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
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
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, base, worldDirectoryLock)

        val first = assertFailsWith<IOException> { minecraftWorldAccess.close() }
        val later = assertFailsWith<IOException> { minecraftWorldAccess.close() }

        assertSame(expected, first)
        assertSame(first, later)
        assertEquals(1, closeAttempts)
        assertFalse(worldDirectoryLock.isValid)
        base.checkNoOpenFiles()
    }

    @Test
    fun waitingWorldCloseAndRegionHandleObserveTheSameCleanupFailure() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val chunkPosition = ChunkPosition(0, 0)
        val base = concurrencyFakeFileSystem()
        val directory = checkNotNull(minecraftWorldPaths.regionFile(chunkPosition.regionPosition).parent)
        seedConcurrencyRegion(base, directory, chunkPosition)
        val closeGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = minecraftWorldPaths.regionFile(chunkPosition.regionPosition),
            closeGate = closeGate,
            closeFailures = 1,
        )
        val worldDirectoryLock = RecordingWorldDirectoryLock()
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, worldDirectoryLock)
        val regionHandle = minecraftWorldAccess.dimensions.overworld.openRegion(chunkPosition.regionPosition)
        val jobs = mutableListOf<Deferred<*>>()
        try {
            assertNotNull(regionHandle.readCompressedChunk(chunkPosition))
            val handleClose = async(Dispatchers.Default) { runCatching { regionHandle.close() } }
            jobs += handleClose
            closeGate.awaitEntered()

            val worldClose = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                runCatching { minecraftWorldAccess.close() }
            }
            jobs += worldClose
            assertFalse(worldClose.isCompleted)

            closeGate.open()
            val handleFailure = assertIs<IOException>(handleClose.await().exceptionOrNull())
            val worldFailure = assertIs<IOException>(worldClose.await().exceptionOrNull())

            assertSame(handleFailure, worldFailure)
            assertEquals("synthetic gated close failure", handleFailure.message)
            assertSame(worldFailure, assertFailsWith<IOException> { minecraftWorldAccess.close() })
            assertFalse(worldDirectoryLock.isValid)
            assertEquals(1, worldDirectoryLock.closeAttempts.get())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                closeGate.open()
                jobs.joinAll()
                runCatching { regionHandle.close() }
                runCatching { minecraftWorldAccess.close() }
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun completedRegionCleanupFailureDoesNotPoisonLaterWorldClose() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val chunkPosition = ChunkPosition(0, 0)
        val base = concurrencyFakeFileSystem()
        seedConcurrencyRegion(
            base,
            checkNotNull(minecraftWorldPaths.regionFile(chunkPosition.regionPosition).parent),
            chunkPosition
        )
        val gatedFileSystem = GatedFileSystem(
            base = base,
            target = minecraftWorldPaths.regionFile(chunkPosition.regionPosition),
            closeFailures = 1,
        )
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val minecraftWorldAccess = concurrencyWorld(minecraftWorldPaths, gatedFileSystem, recordingWorldDirectoryLock)
        try {
            val operationFailure = assertFailsWith<IOException> {
                minecraftWorldAccess.readCompressedChunk(
                    chunkPosition,
                    RegionStorageDirectory.CHUNKS,
                    DimensionId.Overworld,
                )
            }
            assertEquals("synthetic gated close failure", operationFailure.message)
            assertEquals(0, minecraftWorldAccess.activeRegionDirectoryCount())
            assertTrue(recordingWorldDirectoryLock.isValid)

            minecraftWorldAccess.close()

            assertFalse(recordingWorldDirectoryLock.isValid)
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
            assertEquals(1, gatedFileSystem.closes.get())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun directFilesBypassLogicalCoordinationButRemainInsideTheWorldCloseBarrier() = runTest {
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "direct-player"
        val target = minecraftWorldPaths.statistics(player)
        val base = concurrencyFakeFileSystem()
        val sinkGate = BlockingGate(expectedEntrants = 2)
        val gatedFileSystem = GatedFileSystem(base, target, sinkGate = sinkGate)
        val recordingWorldDirectoryLock = RecordingWorldDirectoryLock()
        val minecraftWorldAccess = concurrencyWorld(
            minecraftWorldPaths,
            gatedFileSystem,
            recordingWorldDirectoryLock,
        )
        val jobs = mutableListOf<Deferred<*>>()
        try {
            val directWrite = async(Dispatchers.Default) {
                minecraftWorldAccess.directFiles.write(target) { sink -> sink.writeUtf8("direct") }
            }
            val semanticWrite = async(Dispatchers.Default) {
                minecraftWorldAccess.players.writeStatistics(player) { sink -> sink.writeUtf8("semantic") }
            }
            jobs += directWrite
            jobs += semanticWrite
            sinkGate.awaitEntered()
            assertFalse(directWrite.isCompleted)
            assertFalse(semanticWrite.isCompleted)

            val closing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                minecraftWorldAccess.close()
            }
            jobs += closing
            assertFalse(closing.isCompleted)
            assertTrue(recordingWorldDirectoryLock.isValid)
            assertFailsWith<IllegalStateException> {
                minecraftWorldAccess.directFiles.readBytes(target)
            }

            sinkGate.open()
            directWrite.await()
            semanticWrite.await()
            closing.await()
            assertFalse(recordingWorldDirectoryLock.isValid)
            assertEquals(1, recordingWorldDirectoryLock.closeAttempts.get())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sinkGate.open()
                jobs.joinAll()
                minecraftWorldAccess.close()
                base.checkNoOpenFiles()
            }
        }
    }
}

private fun concurrencyWorld(
    minecraftWorldPaths: MinecraftWorldPaths,
    fileSystem: okio.FileSystem,
    worldDirectoryLock: WorldDirectoryLock = RecordingWorldDirectoryLock(),
): MinecraftWorldAccess = MinecraftWorldAccess.create(
    minecraftWorldPaths = minecraftWorldPaths,
    fileSystem = if (fileSystem is okio.fakefilesystem.FakeFileSystem) {
        threadSafeFakeFileSystem(fileSystem)
    } else {
        fileSystem
    },
    configuration = MinecraftWorldAccessConfiguration(
        regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
    ),
    worldDirectoryLock = worldDirectoryLock,
)

private suspend fun MinecraftWorldAccess.readCompressedChunk(
    chunkPosition: ChunkPosition,
    regionStorageDirectory: RegionStorageDirectory,
    dimensionId: DimensionId,
): CompressedChunk? = when (regionStorageDirectory) {
    RegionStorageDirectory.CHUNKS -> dimensions[dimensionId].openRegion(chunkPosition.regionPosition).use {
        it.readCompressedChunk(chunkPosition)
    }

    RegionStorageDirectory.ENTITIES -> dimensions[dimensionId].openEntityRegion(chunkPosition.regionPosition).use {
        it.readCompressedChunk(chunkPosition)
    }

    RegionStorageDirectory.POINTS_OF_INTEREST -> dimensions[dimensionId]
        .openPoiRegion(chunkPosition.regionPosition).use {
            it.readCompressedChunk(chunkPosition)
        }
}

private suspend fun MinecraftWorldAccess.writeCompressedChunk(
    chunkPosition: ChunkPosition,
    compressedChunk: CompressedChunk,
    regionStorageDirectory: RegionStorageDirectory,
    dimensionId: DimensionId,
) = when (regionStorageDirectory) {
    RegionStorageDirectory.CHUNKS -> dimensions[dimensionId].openRegion(chunkPosition.regionPosition).use {
        it.writeCompressedChunk(chunkPosition, compressedChunk)
    }

    RegionStorageDirectory.ENTITIES -> dimensions[dimensionId].openEntityRegion(chunkPosition.regionPosition).use {
        it.writeCompressedChunk(chunkPosition, compressedChunk)
    }

    RegionStorageDirectory.POINTS_OF_INTEREST -> dimensions[dimensionId]
        .openPoiRegion(chunkPosition.regionPosition).use {
            it.writeCompressedChunk(chunkPosition, compressedChunk)
        }
}

private suspend fun <R> MinecraftWorldAccess.withCompressedChunkSource(
    chunkPosition: ChunkPosition,
    regionStorageDirectory: RegionStorageDirectory,
    dimensionId: DimensionId,
    block: (RegionChunkInfo, okio.BufferedSource) -> R,
): R? = when (regionStorageDirectory) {
    RegionStorageDirectory.CHUNKS -> dimensions[dimensionId].openRegion(chunkPosition.regionPosition).use {
        it.withCompressedChunkSource(chunkPosition, block)
    }

    RegionStorageDirectory.ENTITIES -> dimensions[dimensionId].openEntityRegion(chunkPosition.regionPosition).use {
        it.withCompressedChunkSource(chunkPosition, block)
    }

    RegionStorageDirectory.POINTS_OF_INTEREST -> dimensions[dimensionId]
        .openPoiRegion(chunkPosition.regionPosition).use {
            it.withCompressedChunkSource(chunkPosition, block)
        }
}

private suspend fun MinecraftWorldAccess.writeChunkNbtDocument(
    chunkPosition: ChunkPosition,
    nbtDocument: com.hiczp.minecraft.nbt.NbtDocument,
    regionStorageDirectory: RegionStorageDirectory,
    dimensionId: DimensionId,
) = when (regionStorageDirectory) {
    RegionStorageDirectory.CHUNKS -> dimensions[dimensionId].openRegion(chunkPosition.regionPosition).use {
        it.writeChunkNbtDocument(chunkPosition, nbtDocument)
    }

    RegionStorageDirectory.ENTITIES -> dimensions[dimensionId].openEntityRegion(chunkPosition.regionPosition).use {
        it.writeChunkNbtDocument(chunkPosition, nbtDocument)
    }

    RegionStorageDirectory.POINTS_OF_INTEREST -> dimensions[dimensionId]
        .openPoiRegion(chunkPosition.regionPosition).use {
            it.writeChunkNbtDocument(chunkPosition, nbtDocument)
        }
}
