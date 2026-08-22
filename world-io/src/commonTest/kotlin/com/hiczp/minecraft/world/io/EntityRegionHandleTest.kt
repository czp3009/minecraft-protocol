package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.uuid.Uuid

class EntityRegionHandleTest {
    @Test
    fun randomAccessReadsWritesAndExternalPayloadsUseOneEntityRegion() = runTest {
        val fileSystem = FakeFileSystem()
        val worldRoot = "/world".toPath()
        val directory = MinecraftWorldPaths(worldRoot).regionDirectory(RegionStorageDirectory.ENTITIES)
        val regionPosition = RegionPosition(-1, 2)
        val chunkPosition = regionPosition.chunk(LocalChunkPosition(4, 5))
        val externalPosition = regionPosition.chunk(LocalChunkPosition(6, 5))
        val removedPosition = regionPosition.chunk(LocalChunkPosition(7, 5))
        val codec = EntityChunkNbtCodec(EXPECTED_DATA_VERSION, NbtEntityDataRegistry())
        val entity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.fromLongs(1, 2),
            data = NbtCompound(mapOf("OnGround" to NbtByte(1))),
            position = EntityVector3d(
                MinecraftCoordinates.blockCoordinate(chunkPosition.x, 1) + 0.5,
                64.0,
                MinecraftCoordinates.blockCoordinate(chunkPosition.z, 1) + 0.5,
            ),
        )
        val entityChunk = EntityChunk(EXPECTED_DATA_VERSION, listOf(entity))
        val externalBytes = ByteArray(
            REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD * REGION_SECTOR_BYTES - REGION_CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 31).toByte() }
        val externalEntity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.fromLongs(3, 4),
            data = NbtCompound(mapOf("test:payload" to NbtByteArray(externalBytes))),
            position = EntityVector3d(
                MinecraftCoordinates.blockCoordinate(externalPosition.x, 1) + 0.5,
                64.0,
                MinecraftCoordinates.blockCoordinate(externalPosition.z, 1) + 0.5,
            ),
        )
        val externalEntityChunk = EntityChunk(EXPECTED_DATA_VERSION, listOf(externalEntity))
        val removedEntity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.fromLongs(5, 6),
            data = NbtCompound(emptyMap()),
            position = EntityVector3d(
                MinecraftCoordinates.blockCoordinate(removedPosition.x, 1) + 0.5,
                64.0,
                MinecraftCoordinates.blockCoordinate(removedPosition.z, 1) + 0.5,
            ),
        )
        val storage = RegionStorage(
            directory = directory,
            fileSystem = fileSystem,
            configuration = RegionStorageConfiguration(syncWrites = false),
        )
        val entityRegionHandle = EntityRegionHandle(storage.openRegion(regionPosition))

        entityRegionHandle.writeChunk(chunkPosition, entityChunk, codec, Compression.NONE)
        entityRegionHandle.writeChunk(externalPosition, externalEntityChunk, codec, Compression.NONE)
        entityRegionHandle.writeChunk(
            removedPosition,
            EntityChunk(EXPECTED_DATA_VERSION, listOf(removedEntity)),
            codec,
            Compression.NONE,
        )
        entityRegionHandle.writeChunk(
            removedPosition,
            EntityChunk<NbtCompound>(EXPECTED_DATA_VERSION),
            codec,
            Compression.NONE,
        )

        assertEquals(2, entityRegionHandle.readChunkCount())
        assertTrue(entityRegionHandle.hasChunk(chunkPosition))
        assertFalse(entityRegionHandle.hasChunk(removedPosition))
        assertEquals(entity.uuid, entityRegionHandle.readChunk(chunkPosition, codec)?.rootEntities?.single()?.uuid)
        val compressedBuffer = Buffer()
        val streamedInfo = assertNotNull(entityRegionHandle.readCompressedChunkTo(chunkPosition, compressedBuffer))
        val streamedChunk = CompressedChunk(streamedInfo.compression, compressedBuffer.readByteArray())
            .toEntityChunk(chunkPosition, codec)
        assertEquals(entity.uuid, streamedChunk.rootEntities.single().uuid)
        assertEquals(
            setOf(chunkPosition.local, externalPosition.local),
            entityRegionHandle.readLocalChunkPositions().toSet(),
        )
        assertTrue(fileSystem.exists(directory / "r.-1.2.mca"))
        assertTrue(fileSystem.exists(directory / "c.${externalPosition.x}.${externalPosition.z}.mcc"))

        entityRegionHandle.close()
        storage.close()

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldRoot, fileSystem)
        assertEquals(listOf(regionPosition), liveMinecraftWorldAccess.listEntityRegionPositions())
        val liveEntityRegionHandle = liveMinecraftWorldAccess.openEntityRegion(regionPosition)
        assertEquals(entity.uuid, liveEntityRegionHandle.readChunk(chunkPosition, codec)?.rootEntities?.single()?.uuid)
        val decodedExternalEntity = liveEntityRegionHandle.readChunk(externalPosition, codec)?.rootEntities?.single()
        assertNotNull(decodedExternalEntity)
        assertEquals(NbtByteArray(externalBytes), decodedExternalEntity.data["test:payload"])
        fileSystem.checkNoOpenFiles()
    }

    private companion object {
        const val EXPECTED_DATA_VERSION: Int = 1
    }
}
