package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtIntArray
import com.hiczp.minecraft.nbt.NbtString
import kotlin.test.*
import kotlin.uuid.Uuid

class EntityChunkNbtCodecTest {
    @Test
    fun semanticEntityChunkRoundTripsTypeSpecificDataAndPassengers() {
        val codec = EntityChunkNbtCodec(EXPECTED_DATA_VERSION, NbtEntityDataRegistry())
        val position = ChunkPosition(-2, 3)
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
            rotation = EntityRotation(90.0f, -10.0f),
            passengers = listOf(passenger),
        )
        val entityChunk = EntityChunk(position, EXPECTED_DATA_VERSION, listOf(root))

        val document = entityChunk.toNbtDocument(codec)
        assertEquals(NbtIntArray(intArrayOf(position.x, position.z)), document.root["Position"])
        val decoded = document.toEntityChunk(codec)

        assertEquals(position, decoded.position)
        assertEquals(2, decoded.entityCount)
        val decodedRoot = decoded.entity(root.uuid)
        assertNotNull(decodedRoot)
        assertEquals(root.type, decodedRoot.type)
        assertEquals(root.position, decodedRoot.position)
        assertEquals(root.velocity, decodedRoot.velocity)
        assertEquals(root.rotation, decodedRoot.rotation)
        assertEquals(root.data, decodedRoot.data)
        assertEquals(passenger.uuid, decodedRoot.passengers.single().uuid)
        assertEquals(BlockPosition(-18, 64, 48), decodedRoot.blockPosition)
        assertEquals(position, decodedRoot.chunkPosition)

        val compressed = decoded.toCompressedChunk(codec, Compression.NONE)
        assertEquals(decoded.entityCount, compressed.toEntityChunk(codec).entityCount)
    }

    @Test
    fun codecRejectsWrongPositionVersionAndStructuralPersistentFields() {
        val entityData = NbtEntityDataRegistry()
        val codec = EntityChunkNbtCodec(EXPECTED_DATA_VERSION, entityData)
        val position = ChunkPosition(1, 2)
        val entityChunk = EntityChunk<NbtCompound>(position, EXPECTED_DATA_VERSION)
        val document = codec.encodeDocument(entityChunk)

        assertFailsWith<EntityChunkNbtFormatException> {
            codec.decodeDocument(document, ChunkPosition(2, 1))
        }
        assertFailsWith<EntityChunkNbtFormatException> {
            EntityChunkNbtCodec(EXPECTED_DATA_VERSION + 1, entityData).decodeDocument(document, position)
        }
        val invalid = Entity(
            type = "minecraft:pig",
            uuid = Uuid.NIL,
            data = NbtCompound(mapOf("Pos" to NbtString("collision"))),
            position = EntityVector3d(0.5, 64.0, 0.5),
        )
        val invalidChunk = EntityChunk(ChunkPosition(0, 0), EXPECTED_DATA_VERSION, listOf(invalid))
        invalid.position = EntityVector3d(16.5, 64.0, 0.5)
        assertFailsWith<EntityChunkNbtFormatException> {
            codec.encodeDocument(invalidChunk)
        }
    }

    @Test
    fun callerSuppliedEntityDataBecomesTheRuntimeAndPersistenceValue() {
        val registry = object : EntityDataRegistry<TestEntityData> {
            override fun resolve(type: String, persistentData: NbtCompound): TestEntityData? =
                (persistentData["Silent"] as? NbtByte)?.let { silent -> TestEntityData(silent.value != 0.toByte()) }

            override fun describe(type: String, value: TestEntityData): NbtCompound =
                NbtCompound(mapOf("Silent" to NbtByte(if (value.silent) 1.toByte() else 0.toByte())))
        }
        val codec = EntityChunkNbtCodec(EXPECTED_DATA_VERSION, registry)
        val position = ChunkPosition(0, 0)
        val entity = Entity(
            type = "minecraft:pig",
            uuid = Uuid.fromLongs(5, 6),
            data = TestEntityData(silent = true),
            position = EntityVector3d(1.0, 64.0, 2.0),
        )

        val decoded = codec.decodeDocument(
            codec.encodeDocument(EntityChunk(position, EXPECTED_DATA_VERSION, listOf(entity))),
            position,
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
