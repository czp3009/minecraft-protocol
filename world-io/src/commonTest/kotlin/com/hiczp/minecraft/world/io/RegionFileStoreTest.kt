package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.*
import okio.FileHandle
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class RegionFileStoreTest {
    @Test
    fun typedNbtOverloadsMirrorBothCoordinateFormsAndReadScopes() {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val regionPosition = RegionPosition(-2, 3)
        val localChunkPosition = LocalChunkPosition(5, 6)
        val chunkPosition = regionPosition.chunk(localChunkPosition)
        val levelDat = testLevelDat(levelName = "typed-region-file-store")
        val regionFileStore = RegionFileStore(
            directory,
            fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )

        regionFileStore.writeChunkNbt(
            regionPosition,
            localChunkPosition,
            levelDat,
            Compression.NONE,
            LevelDat.serializer(),
        )
        assertEquals(levelDat, regionFileStore.readChunkNbt(chunkPosition, LevelDat.serializer()))
        regionFileStore.writeChunkNbt(chunkPosition, levelDat, Compression.NONE, LevelDat.serializer())
        assertEquals(
            levelDat,
            regionFileStore.readChunkNbt(regionPosition, localChunkPosition, LevelDat.serializer()),
        )

        regionFileStore.writeChunkNbt(regionPosition, localChunkPosition, levelDat, Compression.NONE)
        assertEquals(levelDat, regionFileStore.readChunkNbt<LevelDat>(chunkPosition))
        regionFileStore.writeChunkNbt(chunkPosition, levelDat, Compression.NONE)
        assertEquals(levelDat, regionFileStore.readChunkNbt<LevelDat>(regionPosition, localChunkPosition))

        regionFileStore.withReadScope(regionPosition) {
            assertEquals(levelDat, readChunkNbt(localChunkPosition, LevelDat.serializer()))
            assertEquals(levelDat, readChunkNbt<LevelDat>(chunkPosition))
        }
        regionFileStore.withEntityReadScope(regionPosition) {
            assertEquals(levelDat, readChunkNbt(chunkPosition, LevelDat.serializer()))
            assertEquals(levelDat, readChunkNbt<LevelDat>(localChunkPosition))
        }
        regionFileStore.withPoiReadScope(regionPosition) {
            assertEquals(levelDat, readChunkNbt(localChunkPosition, LevelDat.serializer()))
            assertEquals(levelDat, readChunkNbt<LevelDat>(chunkPosition))
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun statelessStoreOwnsEachPhysicalOperationWithoutCoordinationState() {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val regionPosition = RegionPosition(-2, 3)
        val first = LocalChunkPosition(1, 2)
        val second = LocalChunkPosition(3, 4)
        val regionFileStore = RegionFileStore(
            directory,
            fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )

        assertFalse(regionFileStore.hasRegion(regionPosition))
        assertNull(regionFileStore.readCompressedChunk(regionPosition, first))
        assertFalse(fakeFileSystem.exists(directory))

        regionFileStore.writeCompressedChunk(
            regionPosition,
            first,
            CompressedChunk(Compression.NONE, byteArrayOf(1, 2, 3)),
        )
        regionFileStore.writeChunkNbtDocument(
            regionPosition,
            second,
            NbtDocument(NbtCompound(mapOf("value" to NbtInt(7)))),
            Compression.NONE,
        )

        assertEquals(listOf(regionPosition), regionFileStore.listRegionPositions())
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            regionFileStore.readCompressedChunk(regionPosition, first)?.toByteArray(),
        )
        assertEquals(2, regionFileStore.withReadScope(regionPosition) { chunkInfos.count() })
        assertTrue(regionFileStore.removeChunk(regionPosition, first))
        assertNull(regionFileStore.readCompressedChunk(regionPosition, first))
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun oneShotReadsOpenTheRegionReadOnly() {
        val fakeFileSystem = FakeFileSystem()
        val directory = "/world/region".toPath()
        val chunkPosition = RegionPosition(0, 0).chunk(LocalChunkPosition(1, 2))
        RegionFileStore(
            directory,
            fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        ).writeCompressedChunk(chunkPosition, CompressedChunk(Compression.NONE, byteArrayOf(1)))
        var writeOpenAttempts = 0
        val readOnlyFileSystem = object : ForwardingFileSystem(fakeFileSystem) {
            override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
                writeOpenAttempts++
                error("A one-shot Region read requested a read/write handle")
            }
        }

        val compressedChunk = RegionFileStore(directory, readOnlyFileSystem).readCompressedChunk(chunkPosition)

        assertContentEquals(byteArrayOf(1), compressedChunk?.toByteArray())
        assertEquals(0, writeOpenAttempts)
        fakeFileSystem.checkNoOpenFiles()
    }
}
