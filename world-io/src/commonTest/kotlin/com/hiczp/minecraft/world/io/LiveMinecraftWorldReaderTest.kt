package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
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
        LevelDataStore(paths, nbtFiles).writeDocument(document)
        PlayerDataStore(paths, nbtFiles).writeDocument(player, document)
        SavedDataFileStore(paths, nbtFiles = nbtFiles)
            .writeDocument("example:renderer/state", document)
        val jsonFiles = Utf8JsonFileStore(fileSystem)
        jsonFiles.writeText(paths.statistics(player), "{\"blocks\":1}")
        jsonFiles.writeText(paths.advancement(player), "{\"done\":true}")
        RegionStorageDirectory.entries.forEach { storage ->
            val store = WorldRegionStore(
                paths = paths,
                storage = storage,
                fileSystem = fileSystem,
            )
            try {
                store.writeChunkNbtDocument(position, document)
                if (storage == RegionStorageDirectory.CHUNKS) {
                    store.writeChunk(
                        externalPosition,
                        RegionChunk(
                            compression = Compression.NONE,
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
        assertEquals(document, reader.readLevelDataDocument())
        assertEquals(document, reader.readPlayerDataDocument(player))
        assertEquals(
            document,
            reader.readSavedDataDocument("example:renderer/state"),
        )
        assertEquals(
            "{\"blocks\":1}",
            reader.readStatisticsText(player),
        )
        assertEquals(
            "{\"done\":true}",
            reader.readAdvancementsText(player),
        )
        RegionStorageDirectory.entries.forEach { storage ->
            assertTrue(reader.doesChunkExist(position, storage))
            assertTrue(reader.doesChunkExist(position.region, position.local, storage))
            assertEquals(
                document,
                reader.readChunkNbtDocument(position.region, position.local, storage),
            )
            assertNotNull(
                checkNotNull(reader.readRegion(position.region, storage))[
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
        levelData.writeDocument(previous)
        levelData.writeDocument(current)
        val playerData = PlayerDataStore(paths, nbtFiles)
        playerData.writeDocument(player, previous)
        playerData.writeDocument(player, current)
        fileSystem.write(paths.levelData) {
            write(byteArrayOf(1, 2, 3))
        }
        fileSystem.write(paths.playerData(player)) {
            write(byteArrayOf(4, 5, 6))
        }
        val before = fileSystem.snapshot(root)

        val reader = LiveMinecraftWorldReader.open(root, fileSystem)
        assertEquals(previous, reader.readLevelDataDocument())
        assertEquals(previous, reader.readPlayerDataDocument(player))

        fileSystem.assertSnapshotEquals(root, before)
        assertTrue(
            fileSystem.list(checkNotNull(paths.playerData(player).parent))
                .none { it.name.contains("_corrupted_") },
        )
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun missingFilesStayMissingAndReadOnlyFileAccessRejectsWrites() = runTest {
        val fileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val paths = MinecraftWorldPaths(root)
        val position = ChunkPosition(0, 0)
        fileSystem.createDirectories(root)

        val reader = LiveMinecraftWorldReader.open(root, fileSystem)
        assertNull(reader.readRegion(position.region))
        assertFalse(reader.doesRegionExist(position.region))
        assertNull(reader.readChunk(position))
        assertFalse(reader.doesChunkExist(position))
        assertFalse(
            fileSystem.exists(
                paths.regionDirectory(RegionStorageDirectory.CHUNKS),
            ),
        )

        val files = WorldFileAccess.liveReadOnly(fileSystem)
        assertFailsWith<IllegalStateException> {
            NbtFileStore(files).writeDocument(
                paths.levelData,
                liveDocument(1),
            )
        }
        assertFailsWith<IllegalStateException> {
            Utf8JsonFileStore(files).writeText(
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

    @Test
    fun liveRegionScopesAndDirectReadersReuseExactlyOneHandle() = runTest {
        val base = FakeFileSystem()
        val root = "/world".toPath()
        val paths = MinecraftWorldPaths(root)
        val regionPosition = RegionPosition(0, 0)
        val first = LocalChunkPosition(0, 0)
        val second = LocalChunkPosition(1, 0)
        val setup = WorldRegionStore(paths, fileSystem = base)
        try {
            setup.withRegion(regionPosition) {
                writeChunk(first, liveChunk(1))
                writeChunk(second, liveChunk(2))
            }
        } finally {
            setup.close()
        }

        val regionPath = paths.regionFile(regionPosition)
        val fileSystem = CountingRegionFileSystem(base, regionPath)
        val reader = LiveMinecraftWorldReader.open(root, fileSystem)
        var escapedRegion: LiveWorldRegion? = null
        var escapedRead: RegionReadScope? = null

        val result = reader.withRegion(regionPosition) {
            escapedRegion = this
            assertContentEquals(
                byteArrayOf(1),
                readChunk(regionPosition.chunk(first))?.payload?.compressedBytes,
            )
            assertContentEquals(byteArrayOf(2), readChunk(second)?.payload?.compressedBytes)
            assertFailsWith<IllegalArgumentException> { readChunk(ChunkPosition(32, 0)) }
            readRegion {
                escapedRead = this
                assertEquals(listOf(first, second), chunkPositions)
                readChunk(regionPosition.chunk(first)) { _, source -> source.readByteArray() }
            }
            42
        }

        assertEquals(42, result)
        assertEquals(1, fileSystem.liveOpens)
        assertEquals(1, fileSystem.closes)
        assertFailsWith<IllegalStateException> { checkNotNull(escapedRegion).readChunk(first) }
        assertFailsWith<IllegalStateException> { checkNotNull(escapedRead).chunkPositions }

        reader.readChunk(regionPosition, first)
        reader.readChunk(regionPosition.chunk(second))
        assertEquals(3, fileSystem.liveOpens)
        assertEquals(3, fileSystem.closes)

        val opened = checkNotNull(reader.openRegion(regionPosition))
        opened.readChunk(regionPosition.chunk(first))
        opened.readChunk(second)
        assertEquals(4, fileSystem.liveOpens)
        assertEquals(3, fileSystem.closes)
        opened.close()
        opened.close()
        assertEquals(4, fileSystem.closes)
        assertFailsWith<IllegalStateException> { opened.readChunk(first) }
        assertNull(reader.openRegion(RegionPosition(1, 0)))

        val direct = LiveRegionFileReader.open(regionPath, fileSystem)
        try {
            direct.readChunk(regionPosition.chunk(first))
            direct.readChunk(second)
            direct.readRegion()
            assertEquals(5, fileSystem.liveOpens)
            assertEquals(4, fileSystem.closes)
        } finally {
            direct.close()
        }
        assertEquals(5, fileSystem.closes)
        base.checkNoOpenFiles()
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
    compression = Compression.NONE,
    payload = RegionChunkPayload.Inline(byteArrayOf(value.toByte())),
)
