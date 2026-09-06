package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.ClientboundAddEntityPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundUpdateAttributesPacket
import com.hiczp.minecraft.protocol.model.type.EntityMetadata
import com.hiczp.minecraft.protocol.model.type.EquipmentUpdate
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.world.format.DataProperties
import com.hiczp.minecraft.world.format.Entity
import com.hiczp.minecraft.world.format.EntityTypeId
import kotlin.uuid.Uuid

/** Destination registries, semantic field projections and providers for required Entity facts absent from the input. */
data class EntityPacketEncoderContext(
    val packetCodecContext: PacketCodecContext,
    val entityPacketWriteMappings: EntityPacketWriteMappings,
    val entityPacketRequiredDataProvider: EntityPacketRequiredDataProvider,
)

/** Source registries, ordered semantic mappings and local values for Entity facts absent from pairing packets. */
data class EntityPacketDecoderContext(
    val packetCodecContext: PacketCodecContext,
    val entityPacketReadMappings: EntityPacketReadMappings,
    val entityPacketMissingDataProvider: EntityPacketMissingDataProvider,
)

/** Connection facts for this operation. The encoder selects passengers from the current Entity graph. */
data class EntityPairingData(
    val entityId: Int,
    val passengerEntityIds: Map<Uuid, Int>,
    val vehiclePassengerRelation: EntityPassengerRelation?,
    val leashHolderEntityId: Int?,
)

/** An already resolved relation owned by a different vehicle; no world object or semantic state is copied here. */
data class EntityPassengerRelation(val vehicleEntityId: Int, val passengerEntityIds: List<Int>)

data class EntityPacketRequiredDataProvider(
    val headYaw: (Entity) -> Float,
    val passengers: (Entity) -> List<Entity>,
) {
    companion object {
        val RequirePresent: EntityPacketRequiredDataProvider = EntityPacketRequiredDataProvider(
            { error("Entity ${it.uuid} has no head yaw") },
            { error("Entity ${it.uuid} has unknown passengers") },
        )
    }
}

fun interface EntityPacketMissingDataProvider {
    fun provide(
        clientboundAddEntityPacket: ClientboundAddEntityPacket,
        entityTypeId: EntityTypeId
    ): EntityPacketMissingData
}

/** Unsent data is explicit. Received fields are applied to these ordinary retained references. */
data class EntityPacketMissingData(val properties: DataProperties, val passengers: MutableList<Entity>?)

/** Projections read current Entity fields/properties; they do not retain a second set of mutable values. */
data class EntityPacketWriteMappings(
    /** Type-specific projection for ClientboundAddEntityPacket.data, including any registry or connection references. */
    val spawnData: (Entity, PacketCodecContext) -> Int,
    val metadata: (Entity, PacketCodecContext) -> EntityMetadata?,
    val attributes: (Entity, PacketCodecContext) -> List<ClientboundUpdateAttributesPacket.AttributeSnapshot>,
    val equipment: (Entity, PacketCodecContext) -> List<EquipmentUpdate>,
)

/** Every supported trailing data packet is delivered in received order to its explicit semantic mapping. */
data class EntityPacketReadMappings(
    /** Applies ClientboundAddEntityPacket.data to semantic state before trailing pairing packets are decoded. */
    val spawnData: (Entity, Int, PacketCodecContext) -> Unit,
    val metadata: (Entity, EntityMetadata, PacketCodecContext) -> Unit,
    val attributes: (Entity, List<ClientboundUpdateAttributesPacket.AttributeSnapshot>, PacketCodecContext) -> Unit,
    val equipment: (Entity, List<EquipmentUpdate>, PacketCodecContext) -> Unit,
)

/**
 * [pendingPackets] retains relation packets, messages for other IDs and unrecognized tails in received order.
 * The caller registers the Entity and resolves/applies those packets in its own protocol state.
 */
data class EntityPacketDecodeResult(
    val entity: Entity,
    val entityId: Int,
    val pendingPackets: List<ClientboundPacket>,
)
