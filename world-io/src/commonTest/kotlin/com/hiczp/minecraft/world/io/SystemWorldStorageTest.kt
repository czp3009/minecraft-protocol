package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlin.test.*

class SystemWorldStorageTest {
    @Test
    fun systemFilesystemSupportsLockingAndPositionalRegionMutation() = runTest {
        val fileSystem = systemFileSystem
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
                currentEpochSeconds = { 123 },
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
    fun worldLeaseComposesEveryOwnedStoreAndSurvivesReopen() = runTest {
        val fileSystem = systemFileSystem
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
                access.withRegionStore(storage) { store ->
                    store.writeChunk(position, inlineChunk(byteArrayOf(storage.ordinal.toByte())))
                }
            }
            access.flush()
            access.close()
            access.close()

            assertFalse(MinecraftWorldAccess.isLocked(root))
            assertTrue(fileSystem.exists(MinecraftWorldPaths(root).sessionLock))
            assertFails { access.readLevelData() }
            assertFails {
                access.withRegionStore { it.readChunk(position) }
            }

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
                    reopened.withRegionStore(storage) { store ->
                        assertContentEquals(
                            byteArrayOf(storage.ordinal.toByte()),
                            store.readChunk(position)?.payload?.compressedBytes,
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
    val sink = sink(path)
    val buffer = Buffer().apply { write(bytes) }
    var failure: Throwable? = null
    try {
        sink.write(buffer, bytes.size.toLong())
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        closeAllPreserving(failure, sink::close)
    }
}

private const val SYSTEM_TEMPORARY_DIRECTORY_ATTEMPTS = 256
