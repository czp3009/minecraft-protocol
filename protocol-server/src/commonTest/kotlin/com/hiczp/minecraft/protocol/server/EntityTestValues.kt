package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.type.EntityDataValue
import com.hiczp.minecraft.protocol.model.type.EntityMetadata
import com.hiczp.minecraft.protocol.model.type.EntityMetadataEntry
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.world.*
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityProperties
import com.hiczp.minecraft.world.format.EntityRotation
import com.hiczp.minecraft.world.format.EntityTypeId
import com.hiczp.minecraft.world.format.EntityVector3d
import kotlin.uuid.Uuid

internal fun testEntity(
    id: Long,
    type: String,
    position: EntityVector3d,
    deltaMovement: EntityVector3d = EntityVector3d.ZERO,
): Entity = Entity(
    EntityTypeId.parse(type), Uuid.fromLongs(0, id), position, deltaMovement, EntityRotation.ZERO, mutableListOf(),
).apply {
    properties[EntityProperties.HeadYaw] = 0f
}

internal fun testEntityBatch(
    packetCodecContext: PacketCodecContext,
    entities: List<Entity>,
    entityIds: Map<Uuid, Int>,
): MinecraftEntityBatch {
    val encoder = EntityPacketEncoder(
        EntityPacketEncoderContext(
            packetCodecContext,
            EntityPacketWriteMappings(
                { _, _ -> 0 },
                { entity, _ ->
                    entity.properties[EntityProperties.SharedFlags]?.let {
                        EntityMetadata(listOf(EntityMetadataEntry(0, EntityDataValue.ByteValue(it))))
                    }
                },
                { entity, _ -> check(entity.properties[EntityProperties.Attributes] == null); emptyList() },
                { entity, _ -> check(entity.properties[EntityProperties.Equipment] == null); emptyList() },
            ),
            EntityPacketRequiredDataProvider.RequirePresent,
        )
    )
    return MinecraftEntityBatch(entities, encoder) { entity ->
        EntityPairingData(entityIds.getValue(entity.uuid), entityIds, null, null)
    }
}
