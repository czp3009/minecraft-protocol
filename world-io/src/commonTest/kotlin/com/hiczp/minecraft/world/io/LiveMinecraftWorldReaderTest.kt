package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class LiveMinecraftWorldReaderTest {
    @Test
    fun readsEveryWorldFileWithoutLockingOrMutation() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val paths = MinecraftWorldPaths(root)
        val player = "00000000-0000-0000-0000-000000000000"
        val document = liveDocument(7)
        val position = ChunkPosition(3, -2)
        val externalPosition = ChunkPosition(4, -2)
        val externalPayload = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
                    REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 31).toByte() }
        fileSystem.createDirectories(root)

        val nbtFiles = NbtFileStore(fileSystem)
        LevelDataStore(paths, nbtFiles).write(document)
        PlayerDataStore(paths, nbtFiles).write(player, document)
        SavedDataFileStore(paths, nbtFiles = nbtFiles)
            .write("example:renderer/state", document)
        val jsonFiles = Utf8JsonFileStore(fileSystem)
        jsonFiles.write(paths.statistics(player), "{\"blocks\":1}")
        jsonFiles.write(paths.advancement(player), "{\"done\":true}")
        RegionStorageDirectory.entries.forEach { storage ->
            val store = WorldRegionStore(
                paths = paths,
                storage = storage,
                fileSystem = fileSystem,
            )
            try {
                store.writeChunkNbt(position, document)
                if (storage == RegionStorageDirectory.CHUNKS) {
                    store.writeChunk(
                        externalPosition,
                        RegionChunk(
                            compression = RegionCompression.NONE,
                            payload = RegionChunkPayload.Inline(
                                externalPayload,
                            ),
                        ),
                    )
                }
            } finally {
                store.close()
            }
        }

        val before = fileSystem.snapshot(root)
        assertFalse(fileSystem.exists(paths.sessionLock))
        val reader = LiveMinecraftWorldReader.open(root, fileSystem)
        try {
            assertEquals(document, reader.readLevelData())
            assertEquals(document, reader.readPlayerData(player))
            assertEquals(
                document,
                reader.readSavedData("example:renderer/state"),
            )
            assertEquals(
                "{\"blocks\":1}",
                reader.readStatistics(player),
            )
            assertEquals(
                "{\"done\":true}",
                reader.readAdvancements(player),
            )
            RegionStorageDirectory.entries.forEach { storage ->
                assertTrue(reader.doesChunkExist(position, storage))
                assertEquals(
                    document,
                    reader.readChunkNbt(position, storage),
                )
                assertNotNull(
                    reader.readRegion(position.region, storage)[
                        position.local
                    ],
                )
            }
            assertContentEquals(
                externalPayload,
                reader.readChunk(externalPosition)
                    ?.payload
                    ?.compressedBytes,
            )
        } finally {
            reader.close()
        }
        reader.close()

        assertFailsWith<IllegalStateException> {
            reader.readLevelData()
        }
        assertFalse(fileSystem.exists(paths.sessionLock))
        fileSystem.assertSnapshotEquals(root, before)
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun fallbackReadsDoNotRepairOrCopyFiles() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val paths = MinecraftWorldPaths(root)
        val player = "00000000-0000-0000-0000-000000000000"
        val previous = liveDocument(1)
        val current = liveDocument(2)
        fileSystem.createDirectories(root)
        val nbtFiles = NbtFileStore(fileSystem)
        val levelData = LevelDataStore(paths, nbtFiles)
        levelData.write(previous)
        levelData.write(current)
        val playerData = PlayerDataStore(paths, nbtFiles)
        playerData.write(player, previous)
        playerData.write(player, current)
        fileSystem.write(paths.levelData) {
            write(byteArrayOf(1, 2, 3))
        }
        fileSystem.write(paths.playerData(player)) {
            write(byteArrayOf(4, 5, 6))
        }
        val before = fileSystem.snapshot(root)

        val reader = LiveMinecraftWorldReader.open(root, fileSystem)
        try {
            assertEquals(previous, reader.readLevelData())
            assertEquals(previous, reader.readPlayerData(player))
        } finally {
            reader.close()
        }

        fileSystem.assertSnapshotEquals(root, before)
        assertTrue(
            fileSystem.list(checkNotNull(paths.playerData(player).parent))
                .none { it.name.contains("_corrupted_") },
        )
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun missingFilesStayMissingAndReadOnlyStoresRejectWrites() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val paths = MinecraftWorldPaths(root)
        val position = ChunkPosition(0, 0)
        fileSystem.createDirectories(root)

        val reader = LiveMinecraftWorldReader.open(root, fileSystem)
        try {
            assertTrue(reader.readRegion(position.region).chunks.isEmpty())
            assertNull(reader.readChunk(position))
            assertFalse(reader.doesChunkExist(position))
        } finally {
            reader.close()
        }
        assertFalse(
            fileSystem.exists(
                paths.regionDirectory(RegionStorageDirectory.CHUNKS),
            ),
        )

        val files = WorldFileAccess.liveReadOnly(fileSystem)
        val regionStore = WorldRegionStore(
            paths = paths,
            storage = RegionStorageDirectory.CHUNKS,
            dimension = DimensionDirectory.Overworld,
            files = files,
        )
        try {
            assertFailsWith<IllegalStateException> {
                regionStore.writeChunk(position, liveChunk(1))
            }
        } finally {
            regionStore.close()
        }
        assertFailsWith<IllegalStateException> {
            NbtFileStore(files).writeDirect(
                paths.levelData,
                liveDocument(1),
            )
        }
        assertFailsWith<IllegalStateException> {
            Utf8JsonFileStore(files).write(
                paths.statistics("player"),
                "{}",
            )
        }
        assertFalse(fileSystem.exists(paths.sessionLock))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun openingMissingWorldDoesNotCreateIt() {
        val fileSystem = FakeFileSystem()
        val root = "/missing".toPath()

        assertFailsWith<WorldIOException> {
            LiveMinecraftWorldReader.open(root, fileSystem)
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

private fun liveChunk(value: Int): RegionChunk = RegionChunk(
    compression = RegionCompression.NONE,
    payload = RegionChunkPayload.Inline(byteArrayOf(value.toByte())),
)
