package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.ClientboundUpdateAttributesPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.AttributeModifier
import com.hiczp.minecraft.protocol.model.type.AttributeModifier as PacketAttributeModifier

/** Projections read current Entity fields/properties; they do not retain a second set of mutable values. */
data class EntityPacketWriteMappings(
    /** Type-specific projection for ClientboundAddEntityPacket.data, including any registry or connection references. */
    val spawnData: (Entity, PacketCodecContext) -> Int,
    val metadata: (Entity, PacketCodecContext) -> EntityMetadata?,
    val attributes: (Entity, PacketCodecContext) -> List<ClientboundUpdateAttributesPacket.AttributeSnapshot>,
    val equipment: (Entity, PacketCodecContext) -> List<EquipmentUpdate>,
) {
    companion object {
        /**
         * Projects current semantic attributes and equipment. Defaults and the syncable-attribute selection are explicit
         * definition facts; instance overrides win. No effective attribute value or default instance is materialized
         * in Entity. Spawn data and metadata retain their caller-supplied projections.
         */
        fun fromProperties(
            spawnData: (Entity, PacketCodecContext) -> Int,
            metadata: (Entity, PacketCodecContext) -> EntityMetadata?,
            itemStackPacketEncoder: ItemStackPacketEncoder,
            attributeSupplier: (EntityTypeId) -> AttributeSupplier?,
            synchronizedAttribute: (EntityTypeId, AttributeId) -> Boolean,
        ): EntityPacketWriteMappings = EntityPacketWriteMappings(
            spawnData = spawnData,
            metadata = metadata,
            attributes = { entity, packetCodecContext ->
                val defaults = attributeSupplier(entity.entityTypeId)?.instances.orEmpty()
                val overrides = entity.properties[EntityProperties.Attributes]?.entries.orEmpty()
                val attributeIds = (defaults.keys + overrides.keys).filter {
                    synchronizedAttribute(entity.entityTypeId, it)
                }
                attributeIds.map { attributeId ->
                    val instance = overrides[attributeId] ?: defaults.getValue(attributeId)
                    ClientboundUpdateAttributesPacket.AttributeSnapshot(
                        packetCodecContext.requireRegistryEntry(
                            ATTRIBUTE_REGISTRY,
                            Identifier(attributeId.value)
                        ).rawId,
                        instance.baseValue,
                        instance.modifiers.map { (modifierId, attributeModifier) ->
                            PacketAttributeModifier(
                                Identifier(modifierId.value), attributeModifier.amount,
                                AttributeModifierOperation.valueOf(attributeModifier.operation.name),
                            )
                        },
                    )
                }
            },
            equipment = { entity, _ ->
                entity.properties[EntityProperties.Equipment]?.slots?.map { (equipmentSlotId, itemStack) ->
                    val slot =
                        requireNotNull(EquipmentSlot.entries.firstOrNull { it.name.lowercase() == equipmentSlotId.value }) {
                            "Equipment slot $equipmentSlotId has no packet representation"
                        }
                    EquipmentUpdate(slot, itemStackPacketEncoder.encode(itemStack))
                }.orEmpty()
            },
        )
    }
}

/** Every supported trailing data packet is delivered in received order to its explicit semantic mapping. */
data class EntityPacketReadMappings(
    /** Applies ClientboundAddEntityPacket.data to semantic state before trailing pairing packets are decoded. */
    val spawnData: (Entity, Int, PacketCodecContext) -> Unit,
    val metadata: (Entity, EntityMetadata, PacketCodecContext) -> Unit,
    val attributes: (Entity, List<ClientboundUpdateAttributesPacket.AttributeSnapshot>, PacketCodecContext) -> Unit,
    val equipment: (Entity, List<EquipmentUpdate>, PacketCodecContext) -> Unit,
) {
    companion object {
        /**
         * Applies received values to the semantic properties. Only mentioned attributes and slots change; modifier sets
         * for mentioned attributes are replaced. The wire has no persistence flag, so [modifierPermanent] supplies it.
         * Passing a function returning false matches the official client's transient modifier installation.
         */
        fun fromProperties(
            spawnData: (Entity, Int, PacketCodecContext) -> Unit,
            metadata: (Entity, EntityMetadata, PacketCodecContext) -> Unit,
            itemStackPacketDecoder: ItemStackPacketDecoder,
            modifierPermanent: (EntityTypeId, AttributeId, ModifierId) -> Boolean,
        ): EntityPacketReadMappings = EntityPacketReadMappings(
            spawnData = spawnData,
            metadata = metadata,
            attributes = { entity, snapshots, packetCodecContext ->
                val attributes = entity.properties[EntityProperties.Attributes] ?: EntityAttributes().also {
                    entity.properties[EntityProperties.Attributes] = it
                }
                snapshots.forEach { snapshot ->
                    val attributeId = AttributeId(
                        requireNotNull(
                            packetCodecContext.requireRegistry(ATTRIBUTE_REGISTRY)[snapshot.attribute],
                        ) { "Attribute raw ID ${snapshot.attribute} has no installed mapping" }.id.value,
                    )
                    val instance = attributes.entries.getOrPut(attributeId) { AttributeInstance(snapshot.base) }
                    instance.baseValue = snapshot.base
                    instance.modifiers.clear()
                    snapshot.modifiers.forEach { modifier ->
                        val modifierId = ModifierId(modifier.id.value)
                        instance.modifiers[modifierId] = AttributeModifier(
                            modifier.amount, AttributeOperation.valueOf(modifier.operation.name),
                            modifierPermanent(entity.entityTypeId, attributeId, modifierId),
                        )
                    }
                }
            },
            equipment = { entity, updates, _ ->
                val equipment = entity.properties[EntityProperties.Equipment] ?: EntityEquipment().also {
                    entity.properties[EntityProperties.Equipment] = it
                }
                updates.forEach { update ->
                    equipment.slots[EquipmentSlotId(update.slot.name.lowercase())] =
                        itemStackPacketDecoder.decode(update.item)
                }
            },
        )
    }
}

private val ATTRIBUTE_REGISTRY = Identifier("attribute")
