package com.hiczp.minecraft.world.format


/** Instance overrides only. Missing entries do not imply that the entity type has no such attribute. */
data class EntityAttributes(var entries: MutableMap<AttributeId, AttributeInstance> = linkedMapOf())

/** Shared defaults remain separate; reading them never inserts an instance into EntityAttributes. */
data class AttributeSupplier(val instances: Map<AttributeId, AttributeInstance>)

data class AttributeInstance(
    var baseValue: Double,
    var modifiers: MutableMap<ModifierId, AttributeModifier> = linkedMapOf(),
    var properties: DataProperties = DataProperties(),
)

enum class AttributeOperation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }

data class AttributeModifier(
    var amount: Double,
    var operation: AttributeOperation,
    var permanent: Boolean,
    var properties: DataProperties = DataProperties(),
)

data class EntityEffects(var entries: MutableMap<MobEffectId, EffectState> = linkedMapOf())

data class EffectState(
    var amplifier: Int,
    var duration: Int,
    var ambient: Boolean,
    var visible: Boolean,
    var showIcon: Boolean,
    var hiddenEffect: EffectState?,
    var properties: DataProperties = DataProperties(),
)

data class EntityEquipment(var slots: MutableMap<EquipmentSlotId, ItemStack?> = linkedMapOf())

/** Optional typed access to the same entries exposed by Entity.properties. */
object EntityProperties {
    val HeadYaw: PropertyKey<Float> = PropertyKey("head_yaw", PropertyTypes.Float)
    val SharedFlags: PropertyKey<Byte> = PropertyKey("shared_flags", PropertyTypes.Byte)
    val Attributes: PropertyKey<EntityAttributes> = PropertyKey("attributes", PropertyTypes.EntityAttributes)
    val ActiveEffects: PropertyKey<EntityEffects> = PropertyKey("active_effects", PropertyTypes.EntityEffects)
    val Equipment: PropertyKey<EntityEquipment> = PropertyKey("equipment", PropertyTypes.EntityEquipment)
}
