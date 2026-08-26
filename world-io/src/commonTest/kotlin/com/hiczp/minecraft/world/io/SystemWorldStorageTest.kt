package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlin.test.*

class SystemWorldStorageTest {
    @Test
    fun systemFilesystemMoveReplacingOverwritesItsTarget() {
        val fileSystem = systemFileSystem
        val root = createSystemTemporaryDirectory(fileSystem)
        val source = root / "source.tmp"
        val target = root / "target.mcc"
        try {
            fileSystem.write(source) { write(byteArrayOf(2)) }
            fileSystem.write(target) { write(byteArrayOf(1)) }

            fileSystem.moveReplacing(source, target)

            assertFalse(fileSystem.exists(source))
            assertContentEquals(
                byteArrayOf(2),
                fileSystem.read(target) { readByteArray() },
            )
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun systemFilesystemSupportsLockingAndPositionalRegionMutation() = runTest {
        val fileSystem = systemFileSystem
        val root = createSystemTemporaryDirectory(fileSystem)
        val lockPath = root / "session.lock"
        val originalLockBytes = "old-lock-tail".encodeToByteArray()
        try {
            fileSystem.write(lockPath) {
                write(originalLockBytes)
            }
            MinecraftWorldAccess.open(root).use { minecraftWorldAccess ->
                assertTrue(MinecraftWorldAccess.isLocked(root))
                assertFailsWith<WorldLockException> {
                    MinecraftWorldAccess.open(root)
                }
                assertEquals(root, minecraftWorldAccess.minecraftWorldPaths.root)
            }
            assertFalse(MinecraftWorldAccess.isLocked(root))
            val storedLockBytes = fileSystem.read(lockPath) {
                readByteArray()
            }
            assertContentEquals(
                SYSTEM_WORLD_LOCK_MARKER,
                storedLockBytes.copyOf(SYSTEM_WORLD_LOCK_MARKER.size),
            )
            assertContentEquals(
                originalLockBytes.copyOfRange(
                    SYSTEM_WORLD_LOCK_MARKER.size,
                    originalLockBytes.size,
                ),
                storedLockBytes.copyOfRange(
                    SYSTEM_WORLD_LOCK_MARKER.size,
                    storedLockBytes.size,
                ),
            )

            val directory = root / "region"
            val chunkPosition = ChunkPosition(0, 0)
            val regionStorage = RegionStorage(
                directory = directory,
                fileSystem = fileSystem,
                regionStorageConfiguration = RegionStorageConfiguration(
                    syncWrites = true,
                ),
            )
            try {
                regionStorage.writeCompressedChunk(chunkPosition, inlineChunk(byteArrayOf(1)))
                regionStorage.writeCompressedChunk(chunkPosition, inlineChunk(byteArrayOf(2)))
                regionStorage.flush()
                assertContentEquals(
                    byteArrayOf(2),
                    regionStorage.readCompressedChunk(chunkPosition).bytesOrNull(),
                )
            } finally {
                regionStorage.close()
            }

            val regionPath = directory / "r.0.0.mca"
            assertTrue(fileSystem.exists(regionPath))
            assertEquals(
                0L,
                checkNotNull(fileSystem.metadata(regionPath).size) %
                        REGION_SECTOR_BYTES,
            )
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun systemFilesystemReplacesExistingExternalChunkSidecar() = runTest {
        val fileSystem = systemFileSystem
        val root = createSystemTemporaryDirectory(fileSystem)
        val directory = root / "region"
        val sidecar = directory / "c.0.0.mcc"
        val chunkPosition = ChunkPosition(0, 0)
        val first = systemExternalPayload(1)
        val second = systemExternalPayload(2)
        try {
            val regionStorage = RegionStorage(directory, fileSystem)
            try {
                regionStorage.writeCompressedChunk(chunkPosition, zlibChunk(first))
                assertContentEquals(
                    first,
                    fileSystem.read(sidecar) { readByteArray() },
                )

                regionStorage.writeCompressedChunk(chunkPosition, zlibChunk(second))
                assertContentEquals(
                    second,
                    regionStorage.readCompressedChunk(chunkPosition).bytesOrNull(),
                )
                assertContentEquals(
                    second,
                    fileSystem.read(sidecar) { readByteArray() },
                )
            } finally {
                regionStorage.close()
            }

            val reopened = RegionStorage(directory, fileSystem)
            try {
                assertContentEquals(
                    second,
                    reopened.readCompressedChunk(chunkPosition).bytesOrNull(),
                )
            } finally {
                reopened.close()
            }
            assertTrue(
                fileSystem.list(directory)
                    .none { it.name.startsWith(".mcc-") },
            )
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun systemDirectNbtRewriteTruncatesExistingFile() {
        val fileSystem = systemFileSystem
        val root = createSystemTemporaryDirectory(fileSystem)
        val path = root / "value.dat"
        val nbtDocument = systemDocument(7)
        try {
            fileSystem.write(path) { write(ByteArray(4_096) { 1 }) }

            val nbtFileStore = NbtFileStore(fileSystem)
            nbtFileStore.writeDocument(path, nbtDocument, Compression.NONE)

            assertEquals(
                nbtDocument,
                nbtFileStore.readDocument(path, Compression.NONE),
            )
            assertTrue(checkNotNull(fileSystem.metadata(path).size) < 4_096L)
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun worldLeaseSharesCompressionAcrossStoresAndSurvivesReopen() = runTest {
        val fileSystem = systemFileSystem
        val parent = createSystemTemporaryDirectory(fileSystem)
        val root = parent / "world"
        val player = "00000000-0000-0000-0000-000000000000"
        val nbtDocument = systemDocument(9)
        val chunkPosition = ChunkPosition(-1, 32)
        val preservedPosition = ChunkPosition(0, 0)
        val preservedDocument = systemDocument(-1)
        val dimensions = listOf(
            DimensionDirectory.Overworld,
            DimensionDirectory.Nether,
        )
        try {
            val initialStore = RegionStorage(MinecraftWorldPaths(root))
            try {
                initialStore.writeChunkNbtDocument(
                    preservedPosition,
                    preservedDocument,
                    Compression.GZIP,
                )
            } finally {
                initialStore.close()
            }
            assertFalse(MinecraftWorldAccess.isLocked(root))
            val minecraftWorldAccess = MinecraftWorldAccess.open(
                root = root,
                minecraftWorldAccessConfiguration = MinecraftWorldAccessConfiguration(
                    regionStorageConfiguration =
                        RegionStorageConfiguration(
                            writeCompression = Compression.LZ4,
                        ),
                ),
            )
            minecraftWorldAccess.use { minecraftWorldAccess ->
                minecraftWorldAccess.writeLevelDataDocument(nbtDocument)
                minecraftWorldAccess.writePlayerDataDocument(player, nbtDocument)
                minecraftWorldAccess.writeSavedDataDocument("example:state/value", nbtDocument)
                minecraftWorldAccess.writeStatisticsText(player, "{}")
                minecraftWorldAccess.writeAdvancementsText(player, "{\"done\":true}")
                dimensions.forEachIndexed { dimensionIndex, dimensionDirectory ->
                    minecraftWorldAccess.openRegion(chunkPosition.regionPosition, dimensionDirectory).use { regionHandle ->
                        regionHandle.writeChunkNbtDocument(chunkPosition, systemDocument(dimensionIndex))
                    }
                }
                assertEquals(
                    listOf(chunkPosition.regionPosition, preservedPosition.regionPosition),
                    minecraftWorldAccess.listRegionPositions(),
                )
                assertEquals(
                    listOf(chunkPosition.regionPosition),
                    minecraftWorldAccess.listRegionPositions(DimensionDirectory.Nether),
                )
                minecraftWorldAccess.flush()
            }
            minecraftWorldAccess.close()

            assertFalse(MinecraftWorldAccess.isLocked(root))
            assertTrue(fileSystem.exists(MinecraftWorldPaths(root).sessionLock))
            assertFails { minecraftWorldAccess.readLevelDataDocument() }
            assertFails { minecraftWorldAccess.openRegion(chunkPosition.regionPosition) }

            MinecraftWorldAccess.open(root).use { minecraftWorldAccess ->
                assertEquals(nbtDocument, minecraftWorldAccess.readLevelDataDocument())
                assertEquals(nbtDocument, minecraftWorldAccess.readPlayerDataDocument(player))
                assertEquals(
                    nbtDocument,
                    minecraftWorldAccess.readSavedDataDocument("example:state/value"),
                )
                assertEquals("{}", minecraftWorldAccess.readStatisticsText(player))
                assertEquals(
                    "{\"done\":true}",
                    minecraftWorldAccess.readAdvancementsText(player),
                )
                minecraftWorldAccess.openRegion(preservedPosition.regionPosition).use { regionHandle ->
                    assertEquals(
                        Compression.GZIP,
                        regionHandle.readCompressedChunk(preservedPosition)?.compression,
                    )
                    assertEquals(preservedDocument, regionHandle.readChunkNbtDocument(preservedPosition))
                }
                dimensions.forEachIndexed { dimensionIndex, dimensionDirectory ->
                    minecraftWorldAccess.openRegion(chunkPosition.regionPosition, dimensionDirectory).use { regionHandle ->
                        assertEquals(
                            Compression.LZ4,
                            regionHandle.readCompressedChunk(chunkPosition)?.compression,
                        )
                        assertEquals(
                            systemDocument(dimensionIndex),
                            regionHandle.readChunkNbtDocument(chunkPosition),
                        )
                    }
                }
            }
        } finally {
            fileSystem.deleteRecursively(parent, mustExist = false)
        }
    }

    @Test
    fun worldLeasePersistsTypedStructuredFilesThroughPublicExplicitAndReifiedApis() = runTest {
        val fileSystem = systemFileSystem
        val parent = createSystemTemporaryDirectory(fileSystem)
        val root = parent / "typed-world"
        val player = "00000000-0000-0000-0000-000000000000"
        val levelDat = testLevelDat()
        val playerStatistics = PlayerStatistics(
            stats = mapOf("minecraft:mined" to mapOf("minecraft:stone" to 42)),
            dataVersion = 4_903,
        )
        val playerAdvancements = PlayerAdvancements(
            dataVersion = 4_903,
            advancements = mapOf(
                "minecraft:story/root" to PlayerAdvancements.Progress(
                    criteria = mapOf("crafting_table" to "2026-08-18 00:00:00 +0000"),
                    done = true,
                ),
            ),
        )
        try {
            MinecraftWorldAccess.open(root).use { minecraftWorldAccess ->
                minecraftWorldAccess.writeLevelData(levelDat)
                minecraftWorldAccess.writeStatistics(player, PlayerStatistics.serializer(), playerStatistics)
                minecraftWorldAccess.writeAdvancements(player, playerAdvancements)
            }

            MinecraftWorldAccess.open(root).use { minecraftWorldAccess ->
                assertEquals(levelDat, minecraftWorldAccess.readLevelData(LevelDat.serializer()))
                assertEquals(levelDat, minecraftWorldAccess.readLevelData<LevelDat>())
                assertEquals(playerStatistics, minecraftWorldAccess.readStatistics<PlayerStatistics>(player))
                assertEquals(
                    playerAdvancements,
                    minecraftWorldAccess.readAdvancements(player, PlayerAdvancements.serializer()),
                )
            }
        } finally {
            fileSystem.deleteRecursively(parent, mustExist = false)
        }
    }

    @Test
    fun systemPlayerFallbackPreservesCorruptedPrimary() = runTest {
        val fileSystem = systemFileSystem
        val parent = createSystemTemporaryDirectory(fileSystem)
        val root = parent /
                "00000000-0000-0000-0000-000000000000" /
                "run-12345678901234567890" /
                "world-storage-interop"
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val player = "00000000-0000-0000-0000-000000000000"
        val first = systemDocument(1)
        val second = systemDocument(2)
        try {
            val playerDataStore = PlayerDataStore(minecraftWorldPaths)
            playerDataStore.writeDocument(player, first)
            playerDataStore.writeDocument(player, second)
            fileSystem.write(minecraftWorldPaths.playerData(player)) { write(byteArrayOf(1, 2, 3)) }

            assertEquals(first, playerDataStore.readDocument(player))
            assertTrue(
                fileSystem.list(checkNotNull(minecraftWorldPaths.playerData(player).parent))
                    .any {
                        it.name.startsWith(
                            "${minecraftWorldPaths.playerData(player).name}_corrupted_",
                        )
                    },
            )
        } finally {
            fileSystem.deleteRecursively(parent, mustExist = false)
        }
    }
}

private fun inlineChunk(bytes: ByteArray): CompressedChunk = CompressedChunk(
    compression = Compression.NONE,
    compressedBytes = bytes,
)

private fun zlibChunk(bytes: ByteArray): CompressedChunk = CompressedChunk(
    compression = Compression.ZLIB,
    compressedBytes = bytes,
)

private fun systemExternalPayload(seed: Int): ByteArray = ByteArray(
    REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES -
            REGION_CHUNK_RECORD_HEADER_BYTES,
) { index -> (index * 31 + seed).toByte() }

private fun systemDocument(value: Int): NbtDocument = NbtDocument(
    NbtCompound(mapOf("value" to NbtInt(value))),
)

private fun createSystemTemporaryDirectory(fileSystem: FileSystem): Path {
    repeat(SYSTEM_TEMPORARY_DIRECTORY_ATTEMPTS) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                temporaryFileName(
                    Random.nextLong().toULong(),
                    prefix = "world-io-test-",
                )
        try {
            fileSystem.createDirectory(root, mustCreate = true)
            return root
        } catch (failure: IOException) {
            if (!fileSystem.exists(root)) throw failure
        }
    }
    throw WorldIOException("Could not create a system test directory")
}

private const val SYSTEM_TEMPORARY_DIRECTORY_ATTEMPTS = 256
private val SYSTEM_WORLD_LOCK_MARKER = "☃".encodeToByteArray()
