package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityProperties
import com.hiczp.minecraft.world.format.EntityRotation
import com.hiczp.minecraft.world.format.EntityTypeId
import com.hiczp.minecraft.world.format.EntityVector3d

/** Converts exactly one finite pairing sequence without registering an Entity or looking up other world objects. */
class EntityPacketDecoder(val entityPacketDecoderContext: EntityPacketDecoderContext) {
    fun decode(clientboundAddEntityPacket: ClientboundAddEntityPacket): EntityPacketDecodeResult =
        decode(listOf(clientboundAddEntityPacket))

    fun decode(packets: Iterable<ClientboundPacket>): EntityPacketDecodeResult {
        val iterator = packets.iterator()
        val clientboundAddEntityPacket =
            (if (iterator.hasNext()) iterator.next() else null) as? ClientboundAddEntityPacket
        requireNotNull(clientboundAddEntityPacket) { "An Entity pairing sequence must begin with ClientboundAddEntityPacket" }
        val packetCodecContext = entityPacketDecoderContext.packetCodecContext
        val type =
            packetCodecContext.requireRegistry(PacketCodecContext.ENTITY_TYPE_REGISTRY)[clientboundAddEntityPacket.type]
                ?: error("Entity type raw ID ${clientboundAddEntityPacket.type} has no installed mapping")
        val entityTypeId = EntityTypeId(type.id.value)
        val missing =
            entityPacketDecoderContext.entityPacketMissingDataProvider.provide(clientboundAddEntityPacket, entityTypeId)
        val entity = Entity(
            entityTypeId = entityTypeId,
            uuid = clientboundAddEntityPacket.uuid,
            position = EntityVector3d(
                clientboundAddEntityPacket.x,
                clientboundAddEntityPacket.y,
                clientboundAddEntityPacket.z
            ),
            deltaMovement = EntityVector3d(
                clientboundAddEntityPacket.movement.x,
                clientboundAddEntityPacket.movement.y,
                clientboundAddEntityPacket.movement.z,
            ),
            entityRotation = EntityRotation(
                clientboundAddEntityPacket.yRot.degrees,
                clientboundAddEntityPacket.xRot.degrees
            ),
            passengers = missing.passengers,
            properties = missing.properties,
        )
        entity.properties[EntityProperties.HeadYaw] = clientboundAddEntityPacket.yHeadRot.degrees
        entityPacketDecoderContext.entityPacketReadMappings.spawnData(
            entity,
            clientboundAddEntityPacket.data,
            packetCodecContext
        )
        return decode(entity, clientboundAddEntityPacket.id, iterator.asSequence().asIterable())
    }

    /** Decodes finite trailing pairing data into an existing target, allowing the endpoint to register it first. */
    fun decode(entity: Entity, entityId: Int, packets: Iterable<ClientboundPacket>): EntityPacketDecodeResult {
        val packetCodecContext = entityPacketDecoderContext.packetCodecContext
        val mappings = entityPacketDecoderContext.entityPacketReadMappings
        val pendingPackets = mutableListOf<ClientboundPacket>()
        val iterator = packets.iterator()
        while (iterator.hasNext()) {
            when (val packet = iterator.next()) {
                is ClientboundAddEntityPacket -> error("EntityPacketDecoder accepts exactly one pairing sequence")
                is ClientboundSetEntityDataPacket -> if (packet.id == entityId) {
                    mappings.metadata(entity, packet.packedItems, packetCodecContext)
                } else pendingPackets.add(packet)

                is ClientboundUpdateAttributesPacket -> if (packet.entityId == entityId) {
                    mappings.attributes(entity, packet.attributes, packetCodecContext)
                } else pendingPackets.add(packet)

                is ClientboundSetEquipmentPacket -> if (packet.entity == entityId) {
                    mappings.equipment(entity, packet.slots.entries, packetCodecContext)
                } else pendingPackets.add(packet)

                else -> pendingPackets.add(packet)
            }
        }
        return EntityPacketDecodeResult(entity, entityId, pendingPackets)
    }
}
