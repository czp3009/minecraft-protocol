package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class LiveMinecraftWorldAccessTest {
    @Test
    fun readsWorldFilesAndLogicalChunkRegionsWithoutLockingOrMutation() = runTest {
        val fileSystem = FakeFileSystem()
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
        fileSystem.createDirectories(root)

        val nbtFileStore = NbtFileStore(fileSystem)
        LevelDataStore(minecraftWorldPaths, nbtFileStore).writeDocument(nbtDocument)
        PlayerDataStore(minecraftWorldPaths, nbtFileStore).writeDocument(playerUuid, nbtDocument)
        SavedDataFileStore(minecraftWorldPaths, nbtFiles = nbtFileStore)
            .writeDocument("example:renderer/state", nbtDocument)
        val utf8JsonFileStore = Utf8JsonFileStore(fileSystem)
        utf8JsonFileStore.writeText(minecraftWorldPaths.statistics(playerUuid), "{\"blocks\":1}")
        utf8JsonFileStore.writeText(minecraftWorldPaths.advancement(playerUuid), "{\"done\":true}")

        val regionStorage = RegionStorage(minecraftWorldPaths, fileSystem = fileSystem)
        try {
            regionStorage.writeChunkNbtDocument(chunkPosition, nbtDocument)
            regionStorage.writeCompressedChunk(
                externalChunkPosition,
                CompressedChunk(Compression.NONE, externalPayload),
            )
        } finally {
            regionStorage.close()
        }

        val before = fileSystem.snapshot(root)
        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, fileSystem)
        val liveRegionHandle = liveMinecraftWorldAccess.openRegion(chunkPosition.region)

        assertFalse(fileSystem.exists(minecraftWorldPaths.sessionLock))
        assertEquals(nbtDocument, liveMinecraftWorldAccess.readLevelDataDocument())
        assertEquals(nbtDocument, liveMinecraftWorldAccess.readPlayerDataDocument(playerUuid))
        assertEquals(
            nbtDocument,
            liveMinecraftWorldAccess.readSavedDataDocument("example:renderer/state"),
        )
        assertEquals("{\"blocks\":1}", liveMinecraftWorldAccess.readStatisticsText(playerUuid))
        assertEquals("{\"done\":true}", liveMinecraftWorldAccess.readAdvancementsText(playerUuid))
        assertEquals(listOf(chunkPosition.region), liveMinecraftWorldAccess.listRegionPositions())
        assertTrue(liveRegionHandle.hasRegion())
        assertTrue(liveRegionHandle.hasChunk(chunkPosition))
        assertTrue(liveRegionHandle.hasChunk(chunkPosition.local))
        assertEquals(2, liveRegionHandle.readChunkCount())
        assertEquals(
            listOf(chunkPosition.local, externalChunkPosition.local),
            liveRegionHandle.readLocalChunkPositions(),
        )
        assertEquals(
            listOf(chunkPosition, externalChunkPosition),
            liveRegionHandle.readChunkPositions(),
        )
        assertTrue(liveRegionHandle.hasChunk(chunkPosition.block(ChunkBlockPosition(0, 0, 0))))
        assertEquals(chunkPosition, liveRegionHandle.readChunkInfo(chunkPosition)?.position)
        assertEquals(
            setOf(chunkPosition, externalChunkPosition),
            liveRegionHandle.readChunkInfos().mapTo(mutableSetOf(), RegionChunkInfo::position),
        )
        assertEquals(nbtDocument, liveRegionHandle.readChunkNbtDocument(chunkPosition))
        assertContentEquals(
            externalPayload,
            liveRegionHandle.readCompressedChunk(externalChunkPosition).bytesOrNull(),
        )
        val compressedSink = Buffer()
        assertEquals(
            externalChunkPosition,
            liveRegionHandle.readCompressedChunkTo(externalChunkPosition, compressedSink)?.position,
        )
        assertContentEquals(externalPayload, compressedSink.readByteArray())
        val nbtSink = Buffer()
        assertEquals(chunkPosition, liveRegionHandle.readChunkNbtTo(chunkPosition, nbtSink)?.position)
        assertEquals(nbtDocument, liveRegionHandle.chunkNbtFormat.nbt.decodeDocumentFromSource(nbtSink))

        assertFalse(fileSystem.exists(minecraftWorldPaths.sessionLock))
        fileSystem.assertSnapshotEquals(root, before)
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun fallbackReadsDoNotRepairOrCopyFiles() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val playerUuid = "00000000-0000-0000-0000-000000000000"
        val previousDocument = liveDocument(1)
        val currentDocument = liveDocument(2)
        fileSystem.createDirectories(root)
        val nbtFileStore = NbtFileStore(fileSystem)
        val levelDataStore = LevelDataStore(minecraftWorldPaths, nbtFileStore)
        levelDataStore.writeDocument(previousDocument)
        levelDataStore.writeDocument(currentDocument)
        val playerDataStore = PlayerDataStore(minecraftWorldPaths, nbtFileStore)
        playerDataStore.writeDocument(playerUuid, previousDocument)
        playerDataStore.writeDocument(playerUuid, currentDocument)
        fileSystem.write(minecraftWorldPaths.levelData) {
            write(byteArrayOf(1, 2, 3))
        }
        fileSystem.write(minecraftWorldPaths.playerData(playerUuid)) {
            write(byteArrayOf(4, 5, 6))
        }
        val before = fileSystem.snapshot(root)

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, fileSystem)
        assertEquals(previousDocument, liveMinecraftWorldAccess.readLevelDataDocument())
        assertEquals(previousDocument, liveMinecraftWorldAccess.readPlayerDataDocument(playerUuid))

        fileSystem.assertSnapshotEquals(root, before)
        assertTrue(
            fileSystem.list(checkNotNull(minecraftWorldPaths.playerData(playerUuid).parent))
                .none { it.name.contains("_corrupted_") },
        )
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun openingMissingRegionIsPureAndEveryReadClosesItsOwnResource() = runTest {
        val baseFileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val regionPosition = RegionPosition(0, 0)
        val firstLocalChunkPosition = LocalChunkPosition(0, 0)
        val secondLocalChunkPosition = LocalChunkPosition(1, 0)
        baseFileSystem.createDirectories(root)

        val missingAccess = LiveMinecraftWorldAccess.open(root, baseFileSystem)
        val missingHandle = missingAccess.openRegion(RegionPosition(1, 0))
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

        val regionStorage = RegionStorage(minecraftWorldPaths, fileSystem = baseFileSystem)
        try {
            regionStorage.openRegion(regionPosition).use { regionHandle ->
                regionHandle.writeCompressedChunk(firstLocalChunkPosition, liveChunk(1))
                regionHandle.writeCompressedChunk(secondLocalChunkPosition, liveChunk(2))
            }
        } finally {
            regionStorage.close()
        }

        val regionPath = minecraftWorldPaths.regionFile(regionPosition)
        val fileSystem = CountingMutableRegionFileSystem(baseFileSystem, regionPath)
        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, fileSystem)
        val liveRegionHandle = liveMinecraftWorldAccess.openRegion(regionPosition)

        assertEquals(0, fileSystem.liveOpens)
        assertEquals(0, fileSystem.closes)
        assertContentEquals(byteArrayOf(1), liveRegionHandle.readCompressedChunk(firstLocalChunkPosition).bytesOrNull())
        assertEquals(1, fileSystem.liveOpens)
        assertEquals(1, fileSystem.closes)
        assertContentEquals(
            byteArrayOf(2),
            liveRegionHandle.readCompressedChunk(regionPosition.chunk(secondLocalChunkPosition)).bytesOrNull(),
        )
        assertEquals(2, fileSystem.liveOpens)
        assertEquals(2, fileSystem.closes)
        assertEquals(2, liveRegionHandle.readChunkInfos().size)
        assertEquals(3, fileSystem.liveOpens)
        assertEquals(3, fileSystem.closes)
        assertEquals(2, liveRegionHandle.readChunkCount())
        assertEquals(4, fileSystem.liveOpens)
        assertEquals(4, fileSystem.closes)
        assertEquals(
            listOf(firstLocalChunkPosition, secondLocalChunkPosition),
            liveRegionHandle.readLocalChunkPositions(),
        )
        assertEquals(5, fileSystem.liveOpens)
        assertEquals(5, fileSystem.closes)

        val secondLiveRegionHandle = liveMinecraftWorldAccess.openRegion(regionPosition)
        val value = secondLiveRegionHandle.readCompressedChunk(firstLocalChunkPosition)?.compressedByteCount
        assertEquals(1L, value)
        assertEquals(6, fileSystem.liveOpens)
        assertEquals(6, fileSystem.closes)
        baseFileSystem.checkNoOpenFiles()
    }

    @Test
    fun regionListingIsCanonicalSortedAndDetached() {
        val fileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val directory = minecraftWorldPaths.regionDirectory(RegionStorageDirectory.CHUNKS)
        fileSystem.createDirectories(directory)
        listOf(
            "r.2.-1.mca",
            "r.-3.4.mca",
            "r.01.0.mca",
            "r.0.0.mcc",
            "notes.txt",
        ).forEach { name ->
            fileSystem.write(directory / name) {}
        }

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(root, fileSystem)
        val positions = liveMinecraftWorldAccess.listRegionPositions()
        fileSystem.delete(directory / "r.2.-1.mca")

        assertEquals(listOf(RegionPosition(-3, 4), RegionPosition(2, -1)), positions)
    }

    @Test
    fun openingMissingWorldDoesNotCreateIt() {
        val fileSystem = FakeFileSystem()
        val root = "/missing".toPath()

        assertFailsWith<WorldIOException> {
            LiveMinecraftWorldAccess.open(root, fileSystem)
        }

        assertFalse(fileSystem.exists(root))
    }
}

private data class FilesystemSnapshot(
    val paths: Set<Path>,
    val files: Map<Path, ByteArray>,
)

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
    expected.files.forEach { (path, bytes) ->
        assertContentEquals(bytes, actual.files.getValue(path), path.toString())
    }
}

private fun liveDocument(value: Int): NbtDocument = NbtDocument(
    NbtCompound(mapOf("value" to NbtInt(value))),
)

private fun liveChunk(value: Int): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = byteArrayOf(value.toByte()),
)
