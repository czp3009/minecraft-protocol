package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.uuid.Uuid

class EntityRegionHandleTest {
    @Test
    fun randomAccessReadsWritesAndExternalPayloadsUseOneEntityRegion() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val worldRoot = "/world".toPath()
        val directory = MinecraftWorldPaths(worldRoot).regionDirectory(RegionStorageDirectory.ENTITIES)
        val regionPosition = RegionPosition(-1, 2)
        val chunkPosition = regionPosition.chunk(LocalChunkPosition(4, 5))
        val externalPosition = regionPosition.chunk(LocalChunkPosition(6, 5))
        val removedPosition = regionPosition.chunk(LocalChunkPosition(7, 5))
        val typedLocal = LocalChunkPosition(8, 5)
        val typedNbt = testLevelDat(levelName = "entity-region-typed-nbt")
        val entityChunkNbtCodec = EntityChunkNbtCodec(NbtEntityDataRegistry())
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
        val entityChunk = EntityChunk(chunkPosition, EXPECTED_DATA_VERSION, listOf(entity))
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
        val externalEntityChunk = EntityChunk(externalPosition, EXPECTED_DATA_VERSION, listOf(externalEntity))
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
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val entityRegionHandle = EntityRegionHandle(regionStorage.openRegion(regionPosition))

        entityRegionHandle.writeChunk(entityChunk, entityChunkNbtCodec, Compression.NONE)
        entityRegionHandle.writeChunk(externalEntityChunk, entityChunkNbtCodec, Compression.NONE)
        entityRegionHandle.writeChunk(
            EntityChunk(removedPosition, EXPECTED_DATA_VERSION, listOf(removedEntity)),
            entityChunkNbtCodec,
            Compression.NONE,
        )
        entityRegionHandle.writeChunk(
            EntityChunk<NbtCompound>(removedPosition, EXPECTED_DATA_VERSION),
            entityChunkNbtCodec,
            Compression.NONE,
        )

        assertEquals(2, entityRegionHandle.readChunkCount())
        assertTrue(entityRegionHandle.hasChunk(chunkPosition))
        assertFalse(entityRegionHandle.hasChunk(removedPosition))
        val decodedChunk = assertNotNull(entityRegionHandle.readChunk(chunkPosition, entityChunkNbtCodec))
        assertEquals(chunkPosition, decodedChunk.chunkPosition)
        assertEquals(entity.uuid, decodedChunk.rootEntities.single().uuid)
        val compressedBuffer = Buffer()
        val streamedInfo = assertNotNull(entityRegionHandle.readCompressedChunkTo(chunkPosition, compressedBuffer))
        val streamedChunk = CompressedChunk(streamedInfo.compression, compressedBuffer.readByteArray())
            .toEntityChunk(chunkPosition, entityChunkNbtCodec)
        assertEquals(chunkPosition, streamedChunk.chunkPosition)
        assertEquals(entity.uuid, streamedChunk.rootEntities.single().uuid)
        assertEquals(
            setOf(chunkPosition.localChunkPosition, externalPosition.localChunkPosition),
            entityRegionHandle.readLocalChunkPositions().toSet(),
        )
        assertTrue(fakeFileSystem.exists(directory / "r.-1.2.mca"))
        assertTrue(fakeFileSystem.exists(directory / "c.${externalPosition.x}.${externalPosition.z}.mcc"))
        entityRegionHandle.writeChunkNbt(
            localChunkPosition = typedLocal,
            value = typedNbt,
            compression = Compression.NONE,
        )
        assertEquals(typedNbt, entityRegionHandle.readChunkNbt<LevelDat>(localChunkPosition = typedLocal))

        var escapedEntityRegionReadScope: EntityRegionReadScope? = null
        entityRegionHandle.withReadScope {
            escapedEntityRegionReadScope = this
            assertEquals(
                entity.uuid,
                assertNotNull(readChunk(chunkPosition, entityChunkNbtCodec)).rootEntities.single().uuid
            )
            assertEquals(
                externalPosition,
                assertNotNull(readChunk(externalPosition.localChunkPosition, entityChunkNbtCodec)).chunkPosition,
            )
            assertEquals(typedNbt, readChunkNbt<LevelDat>(typedLocal))
        }
        assertFailsWith<IllegalStateException> {
            checkNotNull(escapedEntityRegionReadScope).readChunk(chunkPosition, entityChunkNbtCodec)
        }

        entityRegionHandle.close()
        regionStorage.close()

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldRoot, fakeFileSystem)
        assertEquals(listOf(regionPosition), liveMinecraftWorldAccess.dimensions.overworld.listEntityRegionPositions())
        assertTrue(liveMinecraftWorldAccess.dimensions.overworld.hasEntityRegion(regionPosition))
        liveMinecraftWorldAccess.dimensions.overworld.openEntityRegion(regionPosition).use { liveEntityRegionHandle ->
            val liveChunk = assertNotNull(liveEntityRegionHandle.readChunk(chunkPosition, entityChunkNbtCodec))
            assertEquals(chunkPosition, liveChunk.chunkPosition)
            assertEquals(entity.uuid, liveChunk.rootEntities.single().uuid)
            val decodedExternalEntity =
                liveEntityRegionHandle.readChunk(externalPosition, entityChunkNbtCodec)?.rootEntities?.single()
            assertNotNull(decodedExternalEntity)
            assertEquals(NbtByteArray(externalBytes), decodedExternalEntity.data["test:payload"])
            assertEquals(typedNbt, liveEntityRegionHandle.readChunkNbt<LevelDat>(localChunkPosition = typedLocal))
            var escapedLiveEntityRegionReadScope: EntityRegionReadScope? = null
            assertEquals(
                setOf(chunkPosition, externalPosition, regionPosition.chunk(typedLocal)),
                liveEntityRegionHandle.withReadScope {
                    escapedLiveEntityRegionReadScope = this
                    assertEquals(
                        entity.uuid,
                        assertNotNull(readChunk(chunkPosition, entityChunkNbtCodec)).rootEntities.single().uuid,
                    )
                    assertEquals(typedNbt, readChunkNbt<LevelDat>(typedLocal))
                    chunkPositions.toSet()
                },
            )
            assertFailsWith<IllegalStateException> {
                checkNotNull(escapedLiveEntityRegionReadScope).readChunk(chunkPosition, entityChunkNbtCodec)
            }
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    private companion object {
        const val EXPECTED_DATA_VERSION: Int = 1
    }
}
