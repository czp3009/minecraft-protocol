package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.ChunkPosition
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
