package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveMinecraftWorldAccessConcurrencyTest {
    @Test
    fun everyMetadataFileKindAllowsSameFileReadsToReachIoTogether() = runTest {
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val player = "player"
        val nbtDocument = concurrencyDocument(7)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(root)
        val nbtFileStore = NbtFileStore(base)
        LevelDataStore(minecraftWorldPaths, nbtFileStore).writeDocument(nbtDocument)
        PlayerDataStore(minecraftWorldPaths, nbtFileStore).writeDocument(player, nbtDocument)
        SavedDataFileStore(minecraftWorldPaths, nbtFileStore = nbtFileStore).writeDocument("example:data", nbtDocument)
        val utf8JsonFileStore = Utf8JsonFileStore(base)
        utf8JsonFileStore.writeText(minecraftWorldPaths.statistics(player), "statistics")
        utf8JsonFileStore.writeText(minecraftWorldPaths.advancement(player), "advancements")

        assertConcurrentSourceReads(root, base, minecraftWorldPaths.levelData, nbtDocument) {
            readLevelDataDocument()
        }
        assertConcurrentSourceReads(root, base, minecraftWorldPaths.playerData(player), nbtDocument) {
            readPlayerDataDocument(player)
        }
        assertConcurrentSourceReads(root, base, minecraftWorldPaths.savedData("example:data"), nbtDocument) {
            readSavedDataDocument("example:data")
        }
        assertConcurrentSourceReads(root, base, minecraftWorldPaths.statistics(player), "statistics") {
            readStatisticsText(player)
        }
        assertConcurrentSourceReads(root, base, minecraftWorldPaths.advancement(player), "advancements") {
            readAdvancementsText(player)
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun mcaAndMccReadsOfTheSameFilesReachIoTogether() = runTest {
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val inlinePosition = ChunkPosition(0, 0)
        val externalPosition = ChunkPosition(1, 0)
        val externalPayload = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> index.toByte() }
        val base = concurrencyFakeFileSystem()
        val setup = RegionStorage(minecraftWorldPaths, fileSystem = base)
        try {
            setup.writeCompressedChunk(inlinePosition, concurrencyChunk(1))
            setup.writeCompressedChunk(
                externalPosition,
                CompressedChunk(
                    compression = Compression.NONE,
                    compressedBytes = externalPayload,
                ),
            )
        } finally {
            setup.close()
        }

        val regionPath = minecraftWorldPaths.regionFile(inlinePosition.regionPosition)
        val mcaGate = BlockingGate(expectedEntrants = 2)
        val mcaFileSystem = GatedFileSystem(base, regionPath, readGate = mcaGate)
        val mcaReader = LiveMinecraftWorldAccess.open(root, mcaFileSystem)
        val mcaRegion = mcaReader.openRegion(inlinePosition.regionPosition)
        val mcaFirst = async(Dispatchers.Default) { mcaRegion.readCompressedChunk(inlinePosition) }
        val mcaSecond = async(Dispatchers.Default) { mcaRegion.readCompressedChunk(inlinePosition) }
        try {
            mcaGate.awaitEntered()
            assertFalse(mcaFirst.isCompleted)
            assertFalse(mcaSecond.isCompleted)
            assertEquals(2, mcaFileSystem.activeReads.get())
            mcaGate.open()
            assertContentEquals(byteArrayOf(1), mcaFirst.await().bytesOrNull())
            assertContentEquals(byteArrayOf(1), mcaSecond.await().bytesOrNull())
        } finally {
            withContext(NonCancellable) {
                mcaGate.open()
                joinAll(mcaFirst, mcaSecond)
            }
        }

        val sidecar = minecraftWorldPaths.externalChunk(externalPosition)
        val mccGate = BlockingGate(expectedEntrants = 2)
        val mccFileSystem = GatedFileSystem(base, sidecar, readGate = mccGate)
        val mccReader = LiveMinecraftWorldAccess.open(root, mccFileSystem)
        val mccRegion = mccReader.openRegion(externalPosition.regionPosition)
        val mccFirst = async(Dispatchers.Default) { mccRegion.readCompressedChunk(externalPosition) }
        val mccSecond = async(Dispatchers.Default) { mccRegion.readCompressedChunk(externalPosition) }
        try {
            mccGate.awaitEntered()
            assertFalse(mccFirst.isCompleted)
            assertFalse(mccSecond.isCompleted)
            mccGate.open()
            assertContentEquals(externalPayload, mccFirst.await().bytesOrNull())
            assertContentEquals(externalPayload, mccSecond.await().bytesOrNull())
        } finally {
            withContext(NonCancellable) {
                mccGate.open()
                joinAll(mccFirst, mccSecond)
            }
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun blockedLiveReadDoesNotDelayDirectServerStyleWrite() = runTest {
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val player = "player"
        val target = minecraftWorldPaths.statistics(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("old") }
        val sourceGate = BlockingGate()
        val gatedFileSystem = GatedFileSystem(base, target, readGate = sourceGate)
        val reader = LiveMinecraftWorldAccess.open(root, gatedFileSystem)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            val reading = async(Dispatchers.Default) { reader.readStatisticsText(player) }
            jobs += reading
            sourceGate.awaitEntered()

            val writing = async(Dispatchers.Default) {
                Utf8JsonFileStore(gatedFileSystem).writeText(target, "replacement")
            }
            jobs += writing
            writing.await()
            assertFalse(reading.isCompleted)
            assertEquals("replacement", base.read(target) { readUtf8() })

            sourceGate.open()
            assertEquals("old", reading.await())
            base.checkNoOpenFiles()
        } finally {
            withContext(NonCancellable) {
                sourceGate.open()
                jobs.joinAll()
                base.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun repeatedAbsentRegionReadsRetainNoHandles() = runTest {
        val root = "/world".toPath()
        val base = concurrencyFakeFileSystem()
        base.createDirectories(root)
        val reader = LiveMinecraftWorldAccess.open(root, base)
        val firstLocalPosition = LocalChunkPosition(0, 0)
        repeat(2_048) { index ->
            val chunkPosition = RegionPosition(index, 0).chunk(firstLocalPosition)
            assertEquals(null, reader.openRegion(chunkPosition.regionPosition).readCompressedChunk(chunkPosition))
        }
        base.checkNoOpenFiles()
    }
}

private suspend fun <T> CoroutineScope.assertConcurrentSourceReads(
    root: Path,
    base: okio.fakefilesystem.FakeFileSystem,
    target: Path,
    expected: T,
    operation: LiveMinecraftWorldAccess.() -> T,
) {
    val blockingGate = BlockingGate(expectedEntrants = 2)
    val gatedFileSystem = GatedFileSystem(base, target, readGate = blockingGate)
    val reader = LiveMinecraftWorldAccess.open(root, gatedFileSystem)
    val first = async(Dispatchers.Default) { reader.operation() }
    val second = async(Dispatchers.Default) { reader.operation() }
    try {
        blockingGate.awaitEntered()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        blockingGate.open()
        assertEquals(expected, first.await())
        assertEquals(expected, second.await())
    } finally {
        withContext(NonCancellable) {
            blockingGate.open()
            joinAll(first, second)
        }
    }
}
