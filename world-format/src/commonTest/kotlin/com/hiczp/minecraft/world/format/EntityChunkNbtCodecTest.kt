package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import kotlin.test.*
import kotlin.uuid.Uuid

class EntityChunkNbtCodecTest {
    @Test
    fun semanticEntityChunkRoundTripsTypeSpecificDataAndPassengers() {
        val entityChunkNbtCodec = EntityChunkNbtCodec(NbtEntityDataRegistry())
        val chunkPosition = ChunkPosition(-2, 3)
        val passenger = Entity(
            type = "minecraft:chicken",
            uuid = Uuid.fromLongs(3, 4),
            data = NbtCompound(mapOf("Silent" to NbtByte(1))),
            position = EntityVector3d(-17.25, 65.0, 48.75),
        )
        val root = Entity(
            type = "example:vehicle",
            uuid = Uuid.fromLongs(1, 2),
            data = NbtCompound(
                mapOf(
                    "CustomName" to NbtString("vehicle"),
                    "example:mod_data" to NbtCompound(mapOf("enabled" to NbtByte(1))),
                ),
            ),
            position = EntityVector3d(-17.5, 64.0, 48.5),
            velocity = EntityVector3d(0.1, -0.25, 0.5),
            entityRotation = EntityRotation(90.0f, -10.0f),
            passengers = listOf(passenger),
        )
        val entityChunk = EntityChunk(chunkPosition, EXPECTED_DATA_VERSION, listOf(root))

        val nbtDocument = entityChunk.toNbtDocument(entityChunkNbtCodec)
        assertEquals(NbtIntArray(intArrayOf(chunkPosition.x, chunkPosition.z)), nbtDocument.root["Position"])
        val decoded = nbtDocument.toEntityChunk(entityChunkNbtCodec)

        assertEquals(chunkPosition, decoded.chunkPosition)
        assertEquals(2, decoded.entityCount)
        val decodedRoot = decoded.entity(root.uuid)
        assertNotNull(decodedRoot)
        assertEquals(root.type, decodedRoot.type)
        assertEquals(root.position, decodedRoot.position)
        assertEquals(root.velocity, decodedRoot.velocity)
        assertEquals(root.entityRotation, decodedRoot.entityRotation)
        assertEquals(root.data, decodedRoot.data)
        assertEquals(passenger.uuid, decodedRoot.passengers.single().uuid)
        assertEquals(BlockPosition(-18, 64, 48), decodedRoot.blockPosition)
        assertEquals(chunkPosition, decodedRoot.chunkPosition)

        val compressedChunk = decoded.toCompressedChunk(entityChunkNbtCodec, Compression.NONE)
        assertEquals(decoded.entityCount, compressedChunk.toEntityChunk(entityChunkNbtCodec).entityCount)
    }

    @Test
    fun codecUsesTypedStructureAndIgnoresUnknownRootFields() {
        val nbtEntityDataRegistry = NbtEntityDataRegistry()
        val entityChunkNbtCodec = EntityChunkNbtCodec(nbtEntityDataRegistry)
        val entity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.NIL,
            data = NbtCompound(mapOf("Pos" to NbtString("collision"))),
            position = EntityVector3d(0.5, 64.0, 0.5),
        )
        val entityChunk = EntityChunk(ChunkPosition(0, 0), EXPECTED_DATA_VERSION, listOf(entity))

        val encoded = entityChunkNbtCodec.encodeDocument(entityChunk)
        val documentWithUnknownRootField = NbtDocument(
            NbtCompound(encoded.root.value + ("FutureField" to NbtIntArray(intArrayOf(1)))),
        )
        val decoded = entityChunkNbtCodec.decodeDocument(documentWithUnknownRootField)
        val decodedEntity = assertNotNull(decoded.entity(entity.uuid))

        assertEquals(ChunkPosition(0, 0), decoded.chunkPosition)
        assertEquals(ChunkPosition(0, 0), decodedEntity.chunkPosition)
        assertEquals(entity.position, decodedEntity.position)
        assertNull(decodedEntity.data["Pos"])

        entity.position = EntityVector3d(16.5, 64.0, 0.5)
        assertFailsWith<EntityChunkNbtFormatException> {
            entityChunkNbtCodec.encodeDocument(entityChunk)
        }
    }

    @Test
    fun codecCarriesDataVersionWithoutACompatibilityGate() {
        val entityChunkNbtCodec = EntityChunkNbtCodec(NbtEntityDataRegistry())
        val chunkPosition = ChunkPosition(0, 0)
        val dataVersion = Int.MIN_VALUE

        val decoded = entityChunkNbtCodec.decodeDocument(
            entityChunkNbtCodec.encodeDocument(EntityChunk<NbtCompound>(chunkPosition, dataVersion)),
        )

        assertEquals(dataVersion, decoded.dataVersion)
    }

    @Test
    fun callerSuppliedEntityDataBecomesTheRuntimeAndPersistenceValue() {
        val entityDataRegistry = object : EntityDataRegistry<TestEntityData> {
            override fun resolve(type: String, persistentData: NbtCompound): TestEntityData? =
                (persistentData["Silent"] as? NbtByte)?.let { silent -> TestEntityData(silent.value != 0.toByte()) }

            override fun describe(type: String, value: TestEntityData): NbtCompound =
                NbtCompound(mapOf("Silent" to NbtByte(if (value.silent) 1.toByte() else 0.toByte())))
        }
        val entityChunkNbtCodec = EntityChunkNbtCodec(entityDataRegistry)
        val chunkPosition = ChunkPosition(0, 0)
        val entity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.fromLongs(5, 6),
            data = TestEntityData(silent = true),
            position = EntityVector3d(1.0, 64.0, 2.0),
        )

        val decoded = entityChunkNbtCodec.decodeDocument(
            entityChunkNbtCodec.encodeDocument(EntityChunk(chunkPosition, EXPECTED_DATA_VERSION, listOf(entity))),
        )

        assertEquals(TestEntityData(silent = true), decoded.entity(entity.uuid)?.data)
    }

    @Test
    fun runtimeQueriesFollowCurrentEntityCoordinatesWithoutASectionOwnershipLayer() {
        val entity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.fromLongs(7, 8),
            data = NbtCompound(emptyMap()),
            position = EntityVector3d(1.0, 64.0, 1.0),
        )
        val entityChunk = EntityChunk(ChunkPosition(0, 0), EXPECTED_DATA_VERSION, listOf(entity))

        assertEquals(listOf(entity), entityChunk.entitiesIn(ChunkPosition(0, 0)).toList())
        assertEquals(SectionPosition(0, 4, 0), entity.sectionPosition)

        entity.position = EntityVector3d(17.0, 80.0, 1.0)

        assertTrue(entityChunk.entitiesIn(ChunkPosition(0, 0)).none())
        assertEquals(listOf(entity), entityChunk.entitiesIn(ChunkPosition(1, 0)).toList())
        assertEquals(SectionPosition(1, 5, 0), entity.sectionPosition)
    }

    private companion object {
        const val EXPECTED_DATA_VERSION: Int = 1
    }

    private data class TestEntityData(val silent: Boolean)
}
