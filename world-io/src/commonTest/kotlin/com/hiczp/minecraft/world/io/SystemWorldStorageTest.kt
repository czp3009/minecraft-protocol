package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.*

class SystemWorldStorageTest {
    @Test
    fun systemFilesystemMoveReplacingOverwritesItsTarget() {
        val fileSystem = FileSystem.SYSTEM
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
        val fileSystem = FileSystem.SYSTEM
        val root = createSystemTemporaryDirectory(fileSystem)
        try {
            val access = MinecraftWorldAccess.open(root)
            try {
                assertTrue(MinecraftWorldAccess.isLocked(root))
                assertFails { MinecraftWorldAccess.open(root) }
            } finally {
                access.close()
            }
            assertFalse(MinecraftWorldAccess.isLocked(root))

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
            assertTrue(
                checkNotNull(fileSystem.metadata(regionPath).size) %
                        REGION_SECTOR_BYTES == 0L,
            )
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun systemFilesystemReplacesExistingExternalChunkSidecar() = runTest {
        val fileSystem = FileSystem.SYSTEM
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
    fun worldLeaseComposesEveryOwnedStoreAndSurvivesReopen() = runTest {
        val fileSystem = FileSystem.SYSTEM
        val parent = createSystemTemporaryDirectory(fileSystem)
        val root = parent / "world"
        val player = "00000000-0000-0000-0000-000000000000"
        val document = systemDocument(9)
        val position = ChunkPosition(-1, 32)
        try {
            assertFalse(MinecraftWorldAccess.isLocked(root))
            val access = MinecraftWorldAccess.open(root)
            access.writeLevelData(document)
            access.writePlayerData(player, document)
            access.writeSavedData("example:state/value", document)
            access.writeStatistics(player, "{}")
            access.writeAdvancements(player, "{\"done\":true}")
            RegionStorageDirectory.entries.forEach { storage ->
                access.writeChunk(
                    position,
                    inlineChunk(byteArrayOf(storage.ordinal.toByte())),
                    storage,
                )
            }
            access.flush()
            access.close()
            access.close()

            assertFalse(MinecraftWorldAccess.isLocked(root))
            assertTrue(fileSystem.exists(MinecraftWorldPaths(root).sessionLock))
            assertFails { access.readLevelData() }
            assertFails { access.readChunk(position) }

            val reopened = MinecraftWorldAccess.open(root)
            try {
                assertEquals(document, reopened.readLevelData())
                assertEquals(document, reopened.readPlayerData(player))
                assertEquals(
                    document,
                    reopened.readSavedData("example:state/value"),
                )
                assertEquals("{}", reopened.readStatistics(player))
                assertEquals(
                    "{\"done\":true}",
                    reopened.readAdvancements(player),
                )
                RegionStorageDirectory.entries.forEach { storage ->
                    assertContentEquals(
                        byteArrayOf(storage.ordinal.toByte()),
                        reopened.readChunk(
                            position,
                            storage,
                        )?.payload?.compressedBytes,
                    )
                }
            } finally {
                reopened.close()
            }
        } finally {
            fileSystem.deleteRecursively(parent, mustExist = false)
        }
    }

    @Test
    fun systemPlayerFallbackPreservesCorruptedPrimary() = runTest {
        val fileSystem = FileSystem.SYSTEM
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
            store.write(player, first)
            store.write(player, second)
            fileSystem.writeSystemBytes(
                paths.playerData(player),
                byteArrayOf(1, 2, 3),
            )

            assertEquals(first, store.read(player))
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
    compression = RegionCompression.NONE,
    payload = RegionChunkPayload.Inline(bytes),
)

private fun zlibChunk(bytes: ByteArray): RegionChunk = RegionChunk(
    compression = RegionCompression.ZLIB,
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
