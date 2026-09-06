package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityProperties

/** Converts one current Entity to a finite pairing sequence; bundle selection and enqueueing belong to the endpoint. */
class EntityPacketEncoder(val entityPacketEncoderContext: EntityPacketEncoderContext) {
    fun encode(entity: Entity, entityPairingData: EntityPairingData): List<ClientboundPacket> {
        val packetCodecContext = entityPacketEncoderContext.packetCodecContext
        val mappings = entityPacketEncoderContext.entityPacketWriteMappings
        val required = entityPacketEncoderContext.entityPacketRequiredDataProvider
        val headYaw = entity.properties[EntityProperties.HeadYaw] ?: required.headYaw(entity)
        val passengers = entity.passengers ?: required.passengers(entity)
        return buildList {
            add(
                ClientboundAddEntityPacket(
                    id = entityPairingData.entityId,
                    uuid = entity.uuid,
                    type = packetCodecContext.requireRegistryEntry(
                        PacketCodecContext.ENTITY_TYPE_REGISTRY, Identifier(entity.entityTypeId.value),
                    ).rawId,
                    x = entity.position.x,
                    y = entity.position.y,
                    z = entity.position.z,
                    movement = Vector3d(entity.deltaMovement.x, entity.deltaMovement.y, entity.deltaMovement.z),
                    xRot = Angle.fromDegrees(entity.entityRotation.pitch),
                    yRot = Angle.fromDegrees(entity.entityRotation.yaw),
                    yHeadRot = Angle.fromDegrees(headYaw),
                    data = mappings.spawnData(entity, packetCodecContext),
                ),
            )
            mappings.metadata(entity, packetCodecContext)?.takeIf { it.entries.isNotEmpty() }?.let {
                add(ClientboundSetEntityDataPacket(entityPairingData.entityId, it))
            }
            mappings.attributes(entity, packetCodecContext).takeIf { it.isNotEmpty() }?.let {
                add(ClientboundUpdateAttributesPacket(entityPairingData.entityId, it))
            }
            mappings.equipment(entity, packetCodecContext).takeIf { it.isNotEmpty() }?.let {
                add(ClientboundSetEquipmentPacket(entityPairingData.entityId, EquipmentUpdates(it)))
            }
            if (passengers.isNotEmpty()) {
                add(ClientboundSetPassengersPacket(entityPairingData.entityId, passengers.map { passenger ->
                    requireNotNull(entityPairingData.passengerEntityIds[passenger.uuid]) {
                        "Passenger ${passenger.uuid} has no resolved connection entity ID"
                    }
                }))
            }
            entityPairingData.vehiclePassengerRelation?.let {
                add(ClientboundSetPassengersPacket(it.vehicleEntityId, it.passengerEntityIds))
            }
            entityPairingData.leashHolderEntityId?.let {
                add(ClientboundSetEntityLinkPacket(entityPairingData.entityId, it))
            }
        }
    }
}
