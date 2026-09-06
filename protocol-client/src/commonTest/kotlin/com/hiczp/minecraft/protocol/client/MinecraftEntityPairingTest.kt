package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.world.EntityPacketDecoder
import com.hiczp.minecraft.protocol.world.EntityPacketDecoderContext
import com.hiczp.minecraft.protocol.world.EntityPacketMissingData
import com.hiczp.minecraft.protocol.world.EntityPacketReadMappings
import com.hiczp.minecraft.world.format.DataProperties
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityProperties
import kotlin.test.*
import kotlin.uuid.Uuid

class MinecraftEntityPairingTest {
    @Test
    fun registersEachEntityBeforeApplyingItsDataAndPreservesEveryTailInOrder() {
        val events = mutableListOf<String>()
        val entities = mutableMapOf<Int, Entity>()
        val registry = PacketCodecContext(
            listOf(
                RegistryIdMap(
                    PacketCodecContext.ENTITY_TYPE_REGISTRY, listOf(RegistryIdMapping(Identifier("pig"), 0)),
                )
            ), emptyList()
        )
        val decoder = EntityPacketDecoder(
            EntityPacketDecoderContext(
                registry,
                EntityPacketReadMappings(
                    { entity, data, _ ->
                        assertFalse(entities.containsValue(entity))
                        events.add("spawn:$data")
                    },
                    { entity, metadata, _ ->
                        assertSame(entity, entities.values.single { it.uuid == entity.uuid })
                        events.add("metadata:${entity.uuid}")
                        entity.properties[EntityProperties.SharedFlags] = assertIs<EntityDataValue.ByteValue>(
                            metadata.entries.single().value,
                        ).value
                    },
                    { _, _, _ -> error("Unexpected attributes") },
                    { _, _, _ -> error("Unexpected equipment") },
                ),
                { _, _ -> EntityPacketMissingData(DataProperties(), null) },
            )
        )
        val first = spawn(1)
        val second = spawn(2)
        val firstRelation = ClientboundSetPassengersPacket(1, listOf(2))
        val foreignMetadata = ClientboundSetEntityDataPacket(3, EntityMetadata(emptyList()))
        val link = ClientboundSetEntityLinkPacket(2, 1)
        val bundle = ClientboundBundlePacket(
            listOf(
                first, metadata(1), firstRelation, foreignMetadata, second, metadata(2), link,
            )
        )
        val results = bundle.toEntities(
            decoder,
            registerEntity = { id, entity -> entities[id] = entity; events.add("register:$id") },
            pendingPacket = { events.add("pending:${it.entityId}") },
        )
        assertEquals(
            listOf(
                "spawn:1", "register:1", "metadata:${first.uuid}", "pending:1", "pending:1",
                "spawn:2", "register:2", "metadata:${second.uuid}", "pending:2",
            ), events
        )
        assertEquals(listOf(firstRelation, foreignMetadata), results[0].pendingPackets)
        assertEquals(listOf(link), results[1].pendingPackets)
        assertSame(entities[1], results[0].entity)
        assertEquals(2, results[1].entity.properties.require(EntityProperties.SharedFlags).toInt())
        assertTrue(bundle.isEntityPairingBundle)
        assertFailsWith<IllegalArgumentException> { listOf(link).toEntities(decoder) }
        assertFailsWith<IllegalStateException> { decoder.decode(listOf(first, second)) }
    }

    private fun spawn(id: Int) = ClientboundAddEntityPacket(
        id, Uuid.fromLongs(0, id.toLong()), 0, 0.0, 65.0, 0.0, Vector3d(0.0, 0.0, 0.0),
        Angle.fromDegrees(0f), Angle.fromDegrees(0f), Angle.fromDegrees(90f), id,
    )

    private fun metadata(id: Int) = ClientboundSetEntityDataPacket(
        id, EntityMetadata(
            listOf(
                EntityMetadataEntry(0, EntityDataValue.ByteValue(id.toByte())),
            )
        )
    )
}
