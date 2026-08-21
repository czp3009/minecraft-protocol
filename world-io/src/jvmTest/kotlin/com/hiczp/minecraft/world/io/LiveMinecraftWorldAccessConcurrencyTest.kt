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
        val paths = MinecraftWorldPaths(root)
        val player = "player"
        val document = concurrencyDocument(7)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(root)
        val nbtFiles = NbtFileStore(base)
        LevelDataStore(paths, nbtFiles).writeDocument(document)
        PlayerDataStore(paths, nbtFiles).writeDocument(player, document)
        SavedDataFileStore(paths, nbtFiles = nbtFiles).writeDocument("example:data", document)
        val jsonFiles = Utf8JsonFileStore(base)
        jsonFiles.writeText(paths.statistics(player), "statistics")
        jsonFiles.writeText(paths.advancement(player), "advancements")

        assertConcurrentSourceReads(root, base, paths.levelData, document) {
            readLevelDataDocument()
        }
        assertConcurrentSourceReads(root, base, paths.playerData(player), document) {
            readPlayerDataDocument(player)
        }
        assertConcurrentSourceReads(root, base, paths.savedData("example:data"), document) {
            readSavedDataDocument("example:data")
        }
        assertConcurrentSourceReads(root, base, paths.statistics(player), "statistics") {
            readStatisticsText(player)
        }
        assertConcurrentSourceReads(root, base, paths.advancement(player), "advancements") {
            readAdvancementsText(player)
        }
        base.checkNoOpenFiles()
    }

    @Test
    fun mcaAndMccReadsOfTheSameFilesReachIoTogether() = runTest {
        val root = "/world".toPath()
        val paths = MinecraftWorldPaths(root)
        val inlinePosition = ChunkPosition(0, 0)
        val externalPosition = ChunkPosition(1, 0)
        val externalPayload = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> index.toByte() }
        val base = concurrencyFakeFileSystem()
        val setup = RegionStorage(paths, fileSystem = base)
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

        val regionPath = paths.regionFile(inlinePosition.region)
        val mcaGate = BlockingGate(expectedEntrants = 2)
        val mcaFileSystem = GatedFileSystem(base, regionPath, readGate = mcaGate)
        val mcaReader = LiveMinecraftWorldAccess.open(root, mcaFileSystem)
        val mcaRegion = mcaReader.openRegion(inlinePosition.region)
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

        val sidecar = paths.externalChunk(externalPosition)
        val mccGate = BlockingGate(expectedEntrants = 2)
        val mccFileSystem = GatedFileSystem(base, sidecar, readGate = mccGate)
        val mccReader = LiveMinecraftWorldAccess.open(root, mccFileSystem)
        val mccRegion = mccReader.openRegion(externalPosition.region)
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
        val paths = MinecraftWorldPaths(root)
        val player = "player"
        val target = paths.statistics(player)
        val base = concurrencyFakeFileSystem()
        base.createDirectories(checkNotNull(target.parent))
        base.write(target) { writeUtf8("old") }
        val sourceGate = BlockingGate()
        val fileSystem = GatedFileSystem(base, target, readGate = sourceGate)
        val reader = LiveMinecraftWorldAccess.open(root, fileSystem)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            val reading = async(Dispatchers.Default) { reader.readStatisticsText(player) }
            jobs += reading
            sourceGate.awaitEntered()

            val writing = async(Dispatchers.Default) {
                Utf8JsonFileStore(fileSystem).writeText(target, "replacement")
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
            val position = RegionPosition(index, 0).chunk(firstLocalPosition)
            assertEquals(null, reader.openRegion(position.region).readCompressedChunk(position))
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
    val gate = BlockingGate(expectedEntrants = 2)
    val fileSystem = GatedFileSystem(base, target, readGate = gate)
    val reader = LiveMinecraftWorldAccess.open(root, fileSystem)
    val first = async(Dispatchers.Default) { reader.operation() }
    val second = async(Dispatchers.Default) { reader.operation() }
    try {
        gate.awaitEntered()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        gate.open()
        assertEquals(expected, first.await())
        assertEquals(expected, second.await())
    } finally {
        withContext(NonCancellable) {
            gate.open()
            joinAll(first, second)
        }
    }
}
