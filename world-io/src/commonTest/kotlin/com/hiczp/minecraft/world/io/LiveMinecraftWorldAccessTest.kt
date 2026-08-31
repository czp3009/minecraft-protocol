package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class LiveMinecraftWorldAccessTest {
    @Test
    fun readsWorldFilesAndLogicalChunkRegionsWithoutLockingOrMutation() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val playerUuid = "00000000-0000-0000-0000-000000000000"
        val nbtDocument = liveDocument(7)
        val chunkPosition = ChunkPosition(3, -2)
        val externalChunkPosition = ChunkPosition(4, -2)
        val externalPayload = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 31).toByte() }
        fakeFileSystem.createDirectories(root)

        val nbtFileStore = NbtFileStore(fakeFileSystem)
        LevelDataStore(minecraftWorldPaths, nbtFileStore).writeDocument(nbtDocument)
        PlayerDataStore(minecraftWorldPaths, nbtFileStore).writeDocument(playerUuid, nbtDocument)
        SavedDataStore(minecraftWorldPaths, SavedDataScope.WorldRoot, nbtFileStore)
            .writeDocument(SavedDataId("renderer/state", namespace = "example"), nbtDocument)
        val utf8JsonFileStore = Utf8JsonFileStore(fakeFileSystem)
        utf8JsonFileStore.writeJsonElement(
            minecraftWorldPaths.statistics(playerUuid),
            Json.parseToJsonElement("{\"blocks\":1}"),
        )
        utf8JsonFileStore.writeJsonElement(
            minecraftWorldPaths.advancements(playerUuid),
            Json.parseToJsonElement("{\"done\":true}"),
        )

        val regionStorage = CoordinatedRegionStore(minecraftWorldPaths, fileSystem = fakeFileSystem)
        try {
            regionStorage.writeChunkNbtDocument(chunkPosition, nbtDocument)
            regionStorage.writeCompressedChunk(
                externalChunkPosition,
                CompressedChunk(Compression.NONE, externalPayload),
            )
        } finally {
            regionStorage.close()
        }

        val before = fakeFileSystem.snapshot(root)
        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, fakeFileSystem)
        val liveRegionHandle = liveMinecraftWorldAccess.dimensions.overworld.openRegion(chunkPosition.regionPosition)

        assertFalse(fakeFileSystem.exists(minecraftWorldPaths.sessionLock))
        assertEquals(nbtDocument, liveMinecraftWorldAccess.readLevelDataDocument())
        assertEquals(nbtDocument, liveMinecraftWorldAccess.players.readDataDocument(playerUuid))
        assertEquals(
            nbtDocument,
            liveMinecraftWorldAccess.data.readDocument(SavedDataId("renderer/state", namespace = "example")),
        )
        assertEquals(
            Json.parseToJsonElement("{\"blocks\":1}"),
            liveMinecraftWorldAccess.players.readStatisticsJson(playerUuid),
        )
        assertEquals(
            Json.parseToJsonElement("{\"done\":true}"),
            liveMinecraftWorldAccess.players.readAdvancementsJson(playerUuid),
        )
        assertEquals(
            listOf(chunkPosition.regionPosition),
            liveMinecraftWorldAccess.dimensions.overworld.listRegionPositions(),
        )
        assertTrue(liveMinecraftWorldAccess.dimensions.overworld.hasRegion(chunkPosition.regionPosition))
        assertTrue(liveRegionHandle.hasRegion())
        assertTrue(liveRegionHandle.hasChunk(chunkPosition))
        assertTrue(liveRegionHandle.hasChunk(chunkPosition.localChunkPosition))
        assertEquals(2, liveRegionHandle.readChunkCount())
        assertEquals(
            listOf(chunkPosition.localChunkPosition, externalChunkPosition.localChunkPosition),
            liveRegionHandle.readLocalChunkPositions(),
        )
        assertEquals(
            listOf(chunkPosition, externalChunkPosition),
            liveRegionHandle.readChunkPositions(),
        )
        assertTrue(liveRegionHandle.hasChunk(chunkPosition))
        assertEquals(chunkPosition, liveRegionHandle.readChunkInfo(chunkPosition)?.chunkPosition)
        assertEquals(
            setOf(chunkPosition, externalChunkPosition),
            liveRegionHandle.readChunkInfos().mapTo(mutableSetOf(), RegionChunkInfo::chunkPosition),
        )
        assertEquals(nbtDocument, liveRegionHandle.readChunkNbtDocument(chunkPosition))
        assertContentEquals(
            externalPayload,
            liveRegionHandle.readCompressedChunk(externalChunkPosition).bytesOrNull(),
        )
        val compressedSink = Buffer()
        assertEquals(
            externalChunkPosition,
            liveRegionHandle.readCompressedChunkTo(externalChunkPosition, compressedSink)?.chunkPosition,
        )
        assertContentEquals(externalPayload, compressedSink.readByteArray())
        val nbtSink = Buffer()
        assertEquals(chunkPosition, liveRegionHandle.readChunkNbtTo(chunkPosition, nbtSink)?.chunkPosition)
        assertEquals(nbtDocument, liveRegionHandle.chunkNbtFormat.nbtFormat.decodeDocumentFromOkio(nbtSink))
        liveRegionHandle.close()

        assertFalse(fakeFileSystem.exists(minecraftWorldPaths.sessionLock))
        fakeFileSystem.assertSnapshotEquals(root, before)
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun fallbackReadsDoNotRepairOrCopyFiles() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val playerUuid = "00000000-0000-0000-0000-000000000000"
        val previousDocument = liveDocument(1)
        val currentDocument = liveDocument(2)
        fakeFileSystem.createDirectories(root)
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
        levelDataStore.writeDocument(previousDocument)
        levelDataStore.writeDocument(currentDocument)
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
        playerDataStore.writeDocument(playerUuid, previousDocument)
        playerDataStore.writeDocument(playerUuid, currentDocument)
        fakeFileSystem.write(minecraftWorldPaths.levelData) {
            write(byteArrayOf(1, 2, 3))
        }
        fakeFileSystem.write(minecraftWorldPaths.playerData(playerUuid)) {
            write(byteArrayOf(4, 5, 6))
        }
        val before = fakeFileSystem.snapshot(root)

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, fakeFileSystem)
        assertEquals(previousDocument, liveMinecraftWorldAccess.readLevelDataDocument())
        assertEquals(previousDocument, liveMinecraftWorldAccess.players.readDataDocument(playerUuid))

        fakeFileSystem.assertSnapshotEquals(root, before)
        assertTrue(
            fakeFileSystem.list(checkNotNull(minecraftWorldPaths.playerData(playerUuid).parent))
                .none { it.name.contains("_corrupted_") },
        )
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun liveRegionHandleRetainsOneMcaAndReadScopeCachesOneHeader() = runTest {
        val baseFileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val regionPosition = RegionPosition(0, 0)
        val firstLocalChunkPosition = LocalChunkPosition(0, 0)
        val secondLocalChunkPosition = LocalChunkPosition(1, 0)
        baseFileSystem.createDirectories(root)

        val missingAccess = LiveMinecraftWorldAccess.open(root, baseFileSystem)
        val missingHandle = missingAccess.dimensions.overworld.openRegion(RegionPosition(1, 0))
        assertFalse(missingHandle.hasRegion())
        assertFalse(missingHandle.hasChunk(firstLocalChunkPosition))
        assertEquals(0, missingHandle.readChunkCount())
        assertTrue(missingHandle.readLocalChunkPositions().isEmpty())
        assertNull(missingHandle.readChunkInfo(firstLocalChunkPosition))
        assertTrue(missingHandle.readChunkInfos().isEmpty())
        assertNull(missingHandle.readCompressedChunk(firstLocalChunkPosition))
        assertFalse(
            baseFileSystem.exists(
                minecraftWorldPaths.regionDirectory(RegionStorageDirectory.CHUNKS),
            ),
        )
        missingHandle.close()
        assertFailsWith<IllegalStateException> { missingHandle.hasRegion() }

        val regionStorage = CoordinatedRegionStore(minecraftWorldPaths, fileSystem = baseFileSystem)
        try {
            regionStorage.openRegion(regionPosition).use { regionHandle ->
                regionHandle.writeCompressedChunk(firstLocalChunkPosition, liveChunk(1))
                regionHandle.writeCompressedChunk(secondLocalChunkPosition, liveChunk(2))
            }
        } finally {
            regionStorage.close()
        }

        val regionPath = minecraftWorldPaths.regionFile(regionPosition)
        val countingMutableRegionFileSystem = CountingMutableRegionFileSystem(baseFileSystem, regionPath)
        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, countingMutableRegionFileSystem)
        val liveRegionHandle = liveMinecraftWorldAccess.dimensions.overworld.openRegion(regionPosition)

        assertEquals(1, countingMutableRegionFileSystem.liveOpens)
        assertEquals(0, countingMutableRegionFileSystem.closes)
        assertEquals(0, countingMutableRegionFileSystem.headerReads)
        assertContentEquals(byteArrayOf(1), liveRegionHandle.readCompressedChunk(firstLocalChunkPosition).bytesOrNull())
        assertEquals(1, countingMutableRegionFileSystem.liveOpens)
        assertEquals(0, countingMutableRegionFileSystem.closes)
        assertEquals(1, countingMutableRegionFileSystem.headerReads)
        assertContentEquals(
            byteArrayOf(2),
            liveRegionHandle.readCompressedChunk(regionPosition.chunk(secondLocalChunkPosition)).bytesOrNull(),
        )
        assertEquals(2, countingMutableRegionFileSystem.headerReads)

        var escapedReadScope: RegionReadScope? = null
        liveRegionHandle.withReadScope {
            escapedReadScope = this
            assertEquals(
                listOf(firstLocalChunkPosition, secondLocalChunkPosition),
                localChunkPositions.toList(),
            )
            assertContentEquals(byteArrayOf(1), readCompressedChunk(firstLocalChunkPosition).bytesOrNull())
            assertContentEquals(byteArrayOf(2), readCompressedChunk(secondLocalChunkPosition).bytesOrNull())
        }
        assertEquals(3, countingMutableRegionFileSystem.headerReads)
        assertEquals(1, countingMutableRegionFileSystem.liveOpens)
        assertEquals(0, countingMutableRegionFileSystem.closes)
        assertFailsWith<IllegalStateException> { checkNotNull(escapedReadScope).localChunkPositions }

        val secondLiveRegionHandle = liveMinecraftWorldAccess.dimensions.overworld.openRegion(regionPosition)
        assertEquals(2, countingMutableRegionFileSystem.liveOpens)
        secondLiveRegionHandle.use {
            val compressedByteCount = it.readCompressedChunk(firstLocalChunkPosition)?.compressedByteCount
            assertEquals(1L, compressedByteCount)
        }
        assertEquals(1, countingMutableRegionFileSystem.closes)

        liveRegionHandle.close()
        liveRegionHandle.close()
        assertEquals(2, countingMutableRegionFileSystem.closes)
        assertFailsWith<IllegalStateException> { liveRegionHandle.readChunkCount() }
        baseFileSystem.checkNoOpenFiles()
    }

    @Test
    fun regionListingIsCanonicalSortedAndDetached() {
        val fakeFileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val directory = minecraftWorldPaths.regionDirectory(RegionStorageDirectory.CHUNKS)
        fakeFileSystem.createDirectories(directory)
        listOf(
            "r.2.-1.mca",
            "r.-3.4.mca",
            "r.01.0.mca",
            "r.0.0.mcc",
            "notes.txt",
        ).forEach { name ->
            fakeFileSystem.write(directory / name) {}
        }

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, fakeFileSystem)
        val positions = liveMinecraftWorldAccess.dimensions.overworld.listRegionPositions()
        fakeFileSystem.delete(directory / "r.2.-1.mca")

        assertEquals(listOf(RegionPosition(-3, 4), RegionPosition(2, -1)), positions)
    }

    @Test
    fun openingMissingWorldDoesNotCreateIt() {
        val fakeFileSystem = FakeFileSystem()
        val root = "/missing".toPath()

        assertFailsWith<WorldIOException> {
            LiveMinecraftWorldAccess.open(root, fakeFileSystem)
        }

        assertFalse(fakeFileSystem.exists(root))
    }
}

private data class FilesystemSnapshot(
    val paths: Set<Path>,
    val files: Map<Path, ByteArray>,
) {
    override fun equals(other: Any?): Boolean =
        other is FilesystemSnapshot &&
                paths == other.paths &&
                files.size == other.files.size &&
                files.all { (path, bytes) -> other.files[path]?.contentEquals(bytes) == true }

    override fun hashCode(): Int {
        val filesHashCode = files.entries.sumOf { (path, bytes) -> path.hashCode() xor bytes.contentHashCode() }
        return 31 * paths.hashCode() + filesHashCode
    }
}

private fun FileSystem.snapshot(root: Path): FilesystemSnapshot {
    val paths = listRecursively(root).toSet()
    val files = buildMap {
        paths.forEach { path ->
            if (metadata(path).isRegularFile) {
                put(path, read(path) { readByteArray() })
            }
        }
    }
    return FilesystemSnapshot(paths, files)
}

private fun FileSystem.assertSnapshotEquals(
    root: Path,
    expected: FilesystemSnapshot,
) {
    val actual = snapshot(root)
    assertEquals(expected.paths, actual.paths)
    assertEquals(expected.files.keys, actual.files.keys)
    expected.files.forEach { (path, byteArray) ->
        assertContentEquals(byteArray, actual.files.getValue(path), path.toString())
    }
}

private fun liveDocument(value: Int): NbtDocument = NbtDocument(
    NbtCompound(mapOf("value" to NbtInt(value))),
)

private fun liveChunk(value: Int): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = byteArrayOf(value.toByte()),
)
