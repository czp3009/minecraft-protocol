package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.serialization.NbtFormat
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
        val entityChunkNbtDecoder =
            EntityChunkNbtDecoder(EntityChunkNbtDecoderContext(ENTITY_CONTEXT, NbtFormat, NbtPropertyReadMappings()))
        val entityChunkNbtEncoder = EntityChunkNbtEncoder(
            EntityChunkNbtEncoderContext(
                NbtFormat,
                NbtPropertyWriteMappings(),
                EntityChunkNbtMetadata(EXPECTED_DATA_VERSION)
            )
        )
        val entity = Entity(
            entityTypeId = EntityTypeId("minecraft:pig"),
            uuid = Uuid.fromLongs(1, 2),
            deltaMovement = EntityVector3d.ZERO, entityRotation = EntityRotation.ZERO, passengers = mutableListOf(),
            properties = DataProperties(linkedMapOf("OnGround" to PropertyValue(PropertyTypes.Byte, 1.toByte()))),
            position = EntityVector3d(
                MinecraftCoordinates.blockCoordinate(chunkPosition.x, 1) + 0.5,
                64.0,
                MinecraftCoordinates.blockCoordinate(chunkPosition.z, 1) + 0.5,
            ),
        )
        val entityChunk = EntityChunk(chunkPosition, ENTITY_CONTEXT, mutableListOf(entity), DataProperties())
        val externalBytes = ByteArray(
            AnvilRegionFormat.EXTERNAL_CHUNK_SECTOR_THRESHOLD * AnvilRegionFormat.SECTOR_BYTES - AnvilRegionFormat.CHUNK_RECORD_HEADER_BYTES,
        ) { index -> (index * 31).toByte() }
        val externalEntity = Entity(
            entityTypeId = EntityTypeId("minecraft:pig"),
            uuid = Uuid.fromLongs(3, 4),
            deltaMovement = EntityVector3d.ZERO, entityRotation = EntityRotation.ZERO, passengers = mutableListOf(),
            properties = DataProperties(
                linkedMapOf(
                    "test:payload" to PropertyValue(
                        PropertyTypes.Nbt,
                        NbtByteArray(externalBytes)
                    )
                )
            ),
            position = EntityVector3d(
                MinecraftCoordinates.blockCoordinate(externalPosition.x, 1) + 0.5,
                64.0,
                MinecraftCoordinates.blockCoordinate(externalPosition.z, 1) + 0.5,
            ),
        )
        val externalEntityChunk =
            EntityChunk(externalPosition, ENTITY_CONTEXT, mutableListOf(externalEntity), DataProperties())
        val removedEntity = Entity(
            entityTypeId = EntityTypeId("minecraft:pig"),
            uuid = Uuid.fromLongs(5, 6),
            deltaMovement = EntityVector3d.ZERO, entityRotation = EntityRotation.ZERO, passengers = mutableListOf(),
            properties = DataProperties(),
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

        entityRegionHandle.writeChunk(entityChunk, entityChunkNbtEncoder, Compression.NONE)
        entityRegionHandle.writeChunk(externalEntityChunk, entityChunkNbtEncoder, Compression.NONE)
        entityRegionHandle.writeChunk(
            EntityChunk(removedPosition, ENTITY_CONTEXT, mutableListOf(removedEntity), DataProperties()),
            entityChunkNbtEncoder,
            Compression.NONE,
        )
        entityRegionHandle.writeChunk(
            EntityChunk(removedPosition, ENTITY_CONTEXT),
            entityChunkNbtEncoder,
            Compression.NONE,
        )

        assertEquals(2, entityRegionHandle.readChunkCount())
        assertTrue(entityRegionHandle.hasChunk(chunkPosition))
        assertFalse(entityRegionHandle.hasChunk(removedPosition))
        val decodedChunk = assertNotNull(entityRegionHandle.readChunk(chunkPosition, entityChunkNbtDecoder))
        assertEquals(EXPECTED_DATA_VERSION, decodedChunk.entityChunkNbtMetadata.dataVersion)
        assertEquals(chunkPosition, decodedChunk.entityChunk.chunkPosition)
        assertEquals(entity.uuid, decodedChunk.entityChunk.rootEntities.single().uuid)
        assertEquals(
            entity.properties,
            assertNotNull(
                entityRegionHandle.readChunk(
                    chunkPosition,
                    entityChunkNbtDecoder
                )
            ).entityChunk.rootEntities.single().properties
        )
        val compressedBuffer = Buffer()
        val streamedInfo = assertNotNull(entityRegionHandle.readCompressedChunkTo(chunkPosition, compressedBuffer))
        val streamedChunk = CompressedChunk(streamedInfo.compression, compressedBuffer.readByteArray())
            .toEntityChunk(entityChunkNbtDecoder)
        assertEquals(chunkPosition, streamedChunk.entityChunk.chunkPosition)
        assertEquals(entity.uuid, streamedChunk.entityChunk.rootEntities.single().uuid)
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
                assertNotNull(readChunk(chunkPosition, entityChunkNbtDecoder)).entityChunk.rootEntities.single().uuid
            )
            assertEquals(
                externalPosition,
                assertNotNull(
                    readChunk(
                        externalPosition.localChunkPosition,
                        entityChunkNbtDecoder
                    )
                ).entityChunk.chunkPosition,
            )
            assertEquals(typedNbt, readChunkNbt<LevelDat>(typedLocal))
        }
        assertFailsWith<IllegalStateException> {
            checkNotNull(escapedEntityRegionReadScope).readChunk(chunkPosition, entityChunkNbtDecoder)
        }

        var escapedDecodedEntityRegionReadScope: DecodedEntityRegionReadScope? = null
        entityRegionHandle.withReadScope(entityChunkNbtDecoder) {
            escapedDecodedEntityRegionReadScope = this
            assertSame(entityChunkNbtDecoder, this.entityChunkNbtDecoder)
            assertEquals(entity.uuid, assertNotNull(readChunk(chunkPosition)).entityChunk.rootEntities.single().uuid)
        }
        assertFailsWith<IllegalStateException> {
            checkNotNull(escapedDecodedEntityRegionReadScope).readChunk(chunkPosition)
        }

        entityRegionHandle.close()
        regionStorage.close()

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldRoot, fakeFileSystem)
        assertEquals(listOf(regionPosition), liveMinecraftWorldAccess.dimensions.overworld.listEntityRegionPositions())
        assertTrue(liveMinecraftWorldAccess.dimensions.overworld.hasEntityRegion(regionPosition))
        liveMinecraftWorldAccess.dimensions.overworld.openEntityRegion(regionPosition).use { liveEntityRegionHandle ->
            val liveChunk = assertNotNull(liveEntityRegionHandle.readChunk(chunkPosition, entityChunkNbtDecoder))
            assertEquals(chunkPosition, liveChunk.entityChunk.chunkPosition)
            assertEquals(entity.uuid, liveChunk.entityChunk.rootEntities.single().uuid)
            assertEquals(
                entity.properties,
                assertNotNull(
                    liveEntityRegionHandle.readChunk(
                        chunkPosition,
                        entityChunkNbtDecoder
                    )
                ).entityChunk.rootEntities.single().properties,
            )
            val decodedExternalEntity =
                liveEntityRegionHandle.readChunk(
                    externalPosition,
                    entityChunkNbtDecoder
                )?.entityChunk?.rootEntities?.single()
            assertNotNull(decodedExternalEntity)
            assertEquals(
                NbtByteArray(externalBytes),
                decodedExternalEntity.properties["test:payload"]?.get(PropertyTypes.Nbt)
            )
            assertEquals(typedNbt, liveEntityRegionHandle.readChunkNbt<LevelDat>(localChunkPosition = typedLocal))
            var escapedLiveEntityRegionReadScope: DecodedEntityRegionReadScope? = null
            assertEquals(
                setOf(chunkPosition, externalPosition, regionPosition.chunk(typedLocal)),
                liveEntityRegionHandle.withReadScope(entityChunkNbtDecoder) {
                    escapedLiveEntityRegionReadScope = this
                    assertEquals(
                        entity.uuid,
                        assertNotNull(readChunk(chunkPosition)).entityChunk.rootEntities.single().uuid,
                    )
                    assertEquals(typedNbt, readChunkNbt<LevelDat>(typedLocal))
                    chunkPositions.toSet()
                },
            )
            assertFailsWith<IllegalStateException> {
                checkNotNull(escapedLiveEntityRegionReadScope).readChunk(chunkPosition)
            }
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun semanticReadsPreserveTheNbtPositionWhenItDiffersFromTheRegionSlot() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val worldRoot = "/world".toPath()
        val directory = MinecraftWorldPaths(worldRoot).regionDirectory(RegionStorageDirectory.ENTITIES)
        val regionStorage = CoordinatedRegionStore(
            directory = directory,
            fileSystem = fakeFileSystem,
            regionStorageConfiguration = RegionStorageConfiguration(syncWrites = false),
        )
        val slotPosition = ChunkPosition(1, 2)
        val storedPosition = ChunkPosition(40, -12)
        val entityChunkNbtDecoder =
            EntityChunkNbtDecoder(EntityChunkNbtDecoderContext(ENTITY_CONTEXT, NbtFormat, NbtPropertyReadMappings()))
        val entityChunkNbtEncoder = EntityChunkNbtEncoder(
            EntityChunkNbtEncoderContext(
                NbtFormat,
                NbtPropertyWriteMappings(),
                EntityChunkNbtMetadata(EXPECTED_DATA_VERSION)
            )
        )
        val entity = Entity(
            entityTypeId = EntityTypeId("minecraft:pig"),
            uuid = Uuid.fromLongs(9, 10),
            deltaMovement = EntityVector3d.ZERO,
            entityRotation = EntityRotation.ZERO,
            passengers = mutableListOf(),
            properties = DataProperties(),
            position = EntityVector3d(
                MinecraftCoordinates.blockCoordinate(storedPosition.x, 1) + 0.5,
                64.0,
                MinecraftCoordinates.blockCoordinate(storedPosition.z, 1) + 0.5,
            ),
        )
        val nbtDocument = entityChunkNbtEncoder.encodeDocument(
            EntityChunk(storedPosition, ENTITY_CONTEXT, mutableListOf(entity), DataProperties()),
        )
        val entityRegionHandle = EntityRegionHandle(regionStorage.openRegion(slotPosition.regionPosition))

        try {
            entityRegionHandle.writeChunkNbtDocument(slotPosition, nbtDocument, Compression.NONE)
            assertEquals(
                storedPosition,
                assertNotNull(
                    entityRegionHandle.readChunk(
                        slotPosition,
                        entityChunkNbtDecoder
                    )
                ).entityChunk.chunkPosition,
            )
            assertEquals(
                storedPosition,
                assertNotNull(
                    entityRegionHandle.readChunk(
                        slotPosition,
                        entityChunkNbtDecoder
                    )
                ).entityChunk.chunkPosition
            )
            entityRegionHandle.withReadScope(entityChunkNbtDecoder) {
                assertEquals(storedPosition, assertNotNull(readChunk(slotPosition)).entityChunk.chunkPosition)
            }
        } finally {
            entityRegionHandle.close()
            regionStorage.close()
        }

        LiveMinecraftWorldAccess.open(worldRoot, fakeFileSystem).dimensions.overworld
            .openEntityRegion(slotPosition.regionPosition)
            .use { liveEntityRegionHandle ->
                assertEquals(
                    storedPosition,
                    assertNotNull(
                        liveEntityRegionHandle.readChunk(
                            slotPosition,
                            entityChunkNbtDecoder
                        )
                    ).entityChunk.chunkPosition,
                )
            }
        fakeFileSystem.checkNoOpenFiles()
    }

    private companion object {
        const val EXPECTED_DATA_VERSION: Int = 1
        val ENTITY_CONTEXT = EntityChunkContext(DimensionId.Overworld)
    }
}
