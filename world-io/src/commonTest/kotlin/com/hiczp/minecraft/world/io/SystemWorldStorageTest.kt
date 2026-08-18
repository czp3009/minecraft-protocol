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
            fileSystem.writeSystemBytes(source, byteArrayOf(2))
            fileSystem.writeSystemBytes(target, byteArrayOf(1))

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
            val access = MinecraftWorldAccess.open(root)
            try {
                assertTrue(MinecraftWorldAccess.isLocked(root))
                assertFailsWith<WorldLockException> {
                    MinecraftWorldAccess.open(root)
                }
            } finally {
                access.close()
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
            val position = ChunkPosition(0, 0)
            val store = WorldRegionStore(
                directory = directory,
                fileSystem = fileSystem,
                configuration = WorldRegionStoreConfiguration(
                    syncWrites = true,
                ),
            )
            try {
                store.writeChunk(position, inlineChunk(byteArrayOf(1)))
                store.writeChunk(position, inlineChunk(byteArrayOf(2)))
                store.flush()
                assertContentEquals(
                    byteArrayOf(2),
                    store.readChunk(position)?.payload?.compressedBytes,
                )
            } finally {
                store.close()
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
        val position = ChunkPosition(0, 0)
        val first = systemExternalPayload(1)
        val second = systemExternalPayload(2)
        try {
            val store = WorldRegionStore(directory, fileSystem)
            try {
                store.writeChunk(position, zlibChunk(first))
                assertContentEquals(
                    first,
                    fileSystem.read(sidecar) { readByteArray() },
                )

                store.writeChunk(position, zlibChunk(second))
                assertContentEquals(
                    second,
                    store.readChunk(position)?.payload?.compressedBytes,
                )
                assertContentEquals(
                    second,
                    fileSystem.read(sidecar) { readByteArray() },
                )
            } finally {
                store.close()
            }

            val reopened = WorldRegionStore(directory, fileSystem)
            try {
                assertContentEquals(
                    second,
                    reopened.readChunk(position)?.payload?.compressedBytes,
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
        val document = systemDocument(7)
        try {
            fileSystem.writeSystemBytes(path, ByteArray(4_096) { 1 })

            val store = NbtFileStore(fileSystem)
            store.writeDocument(path, document, Compression.NONE)

            assertEquals(
                document,
                store.readDocument(path, Compression.NONE),
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
        val document = systemDocument(9)
        val position = ChunkPosition(-1, 32)
        val preservedPosition = ChunkPosition(0, 0)
        val preservedDocument = systemDocument(-1)
        val dimensions = listOf(
            DimensionDirectory.Overworld,
            DimensionDirectory.Nether,
        )
        try {
            val initialStore = WorldRegionStore(MinecraftWorldPaths(root))
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
            val access = MinecraftWorldAccess.open(
                root = root,
                configuration = MinecraftWorldAccessConfiguration(
                    regionStoreConfiguration =
                        WorldRegionStoreConfiguration(
                            writeCompression = Compression.LZ4,
                        ),
                ),
            )
            try {
                access.writeLevelDataDocument(document)
                access.writePlayerDataDocument(player, document)
                access.writeSavedDataDocument("example:state/value", document)
                access.writeStatisticsText(player, "{}")
                access.writeAdvancementsText(player, "{\"done\":true}")
                dimensions.forEachIndexed { dimensionIndex, dimension ->
                    RegionStorageDirectory.entries.forEach { storage ->
                        val value = dimensionIndex * RegionStorageDirectory.entries.size + storage.ordinal
                        access.writeChunkNbtDocument(
                            position = position,
                            document = systemDocument(value),
                            storage = storage,
                            dimension = dimension,
                        )
                    }
                }
                access.flush()
            } finally {
                access.close()
            }
            access.close()

            assertFalse(MinecraftWorldAccess.isLocked(root))
            assertTrue(fileSystem.exists(MinecraftWorldPaths(root).sessionLock))
            assertFails { access.readLevelDataDocument() }
            assertFails { access.readChunk(position) }

            val reopened = MinecraftWorldAccess.open(root)
            try {
                assertEquals(document, reopened.readLevelDataDocument())
                assertEquals(document, reopened.readPlayerDataDocument(player))
                assertEquals(
                    document,
                    reopened.readSavedDataDocument("example:state/value"),
                )
                assertEquals("{}", reopened.readStatisticsText(player))
                assertEquals(
                    "{\"done\":true}",
                    reopened.readAdvancementsText(player),
                )
                assertEquals(
                    Compression.GZIP,
                    reopened.readChunk(preservedPosition)?.compression,
                )
                assertEquals(
                    preservedDocument,
                    reopened.readChunkNbtDocument(preservedPosition),
                )
                dimensions.forEachIndexed { dimensionIndex, dimension ->
                    RegionStorageDirectory.entries.forEach { storage ->
                        val value = dimensionIndex * RegionStorageDirectory.entries.size + storage.ordinal
                        assertEquals(
                            Compression.LZ4,
                            reopened.readChunk(
                                position,
                                storage,
                                dimension,
                            )?.compression,
                        )
                        assertEquals(
                            systemDocument(value),
                            reopened.readChunkNbtDocument(
                                position,
                                storage,
                                dimension,
                            ),
                        )
                    }
                }
            } finally {
                reopened.close()
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
        val level = testLevelDat()
        val statistics = PlayerStatistics(
            stats = mapOf("minecraft:mined" to mapOf("minecraft:stone" to 42)),
            dataVersion = 4_903,
        )
        val advancements = PlayerAdvancements(
            dataVersion = 4_903,
            advancements = mapOf(
                "minecraft:story/root" to PlayerAdvancements.Progress(
                    criteria = mapOf("crafting_table" to "2026-08-18 00:00:00 +0000"),
                    done = true,
                ),
            ),
        )
        try {
            val access = MinecraftWorldAccess.open(root)
            try {
                access.writeLevelData(level)
                access.writeStatistics(player, PlayerStatistics.serializer(), statistics)
                access.writeAdvancements(player, advancements)
            } finally {
                access.close()
            }

            val reopened = MinecraftWorldAccess.open(root)
            try {
                assertEquals(level, reopened.readLevelData(LevelDat.serializer()))
                assertEquals(level, reopened.readLevelData<LevelDat>())
                assertEquals(statistics, reopened.readStatistics<PlayerStatistics>(player))
                assertEquals(
                    advancements,
                    reopened.readAdvancements(player, PlayerAdvancements.serializer()),
                )
            } finally {
                reopened.close()
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
        val paths = MinecraftWorldPaths(root)
        val player = "00000000-0000-0000-0000-000000000000"
        val first = systemDocument(1)
        val second = systemDocument(2)
        try {
            val store = PlayerDataStore(paths)
            store.writeDocument(player, first)
            store.writeDocument(player, second)
            fileSystem.writeSystemBytes(
                paths.playerData(player),
                byteArrayOf(1, 2, 3),
            )

            assertEquals(first, store.readDocument(player))
            assertTrue(
                fileSystem.list(checkNotNull(paths.playerData(player).parent))
                    .any {
                        it.name.startsWith(
                            "${paths.playerData(player).name}_corrupted_",
                        )
                    },
            )
        } finally {
            fileSystem.deleteRecursively(parent, mustExist = false)
        }
    }
}

private fun inlineChunk(bytes: ByteArray): RegionChunk = RegionChunk(
    compression = Compression.NONE,
    payload = RegionChunkPayload.Inline(bytes),
)

private fun zlibChunk(bytes: ByteArray): RegionChunk = RegionChunk(
    compression = Compression.ZLIB,
    payload = RegionChunkPayload.Inline(bytes),
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

private fun FileSystem.writeSystemBytes(path: Path, bytes: ByteArray) {
    write(path) {
        write(bytes)
    }
}

private const val SYSTEM_TEMPORARY_DIRECTORY_ATTEMPTS = 256
private val SYSTEM_WORLD_LOCK_MARKER = "☃".encodeToByteArray()
