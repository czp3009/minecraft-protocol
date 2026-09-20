package com.hiczp.minecraft.world.format

/** Explicit copy semantics for an application-defined property type; the context copies any nested built-in values. */
class PropertyValueCopier<T : Any>(
    val propertyType: PropertyType<T>,
    private val copy: (T, PropertyCopyContext) -> T,
) {
    internal fun copy(propertyValue: PropertyValue<*>, context: PropertyCopyContext): PropertyValue<T> =
        PropertyValue(propertyType, copy(propertyValue.get(propertyType), context))
}

/**
 * Detached copies of editable property trees without serialization. Immutable values are shared; each occurrence of
 * a mutable value is copied independently. Cycles are rejected and unknown custom types require an explicit copier.
 * The context installs no tracking on the input graph. A copied tree can be freely changed or shared by its caller.
 */
class PropertyCopyContext private constructor(
    private val copiers: Map<PropertyType<*>, PropertyValueCopier<*>>,
    private val path: CopyVisit?,
) {
    constructor(copiers: List<PropertyValueCopier<*>> = emptyList()) : this(
        copiers.associateBy { it.propertyType },
        null
    )

    fun copyProperties(dataProperties: DataProperties): DataProperties {
        val nested = descend(dataProperties)
        return DataProperties(dataProperties.entries.mapValuesTo(linkedMapOf()) { nested.copyValue(it.value) })
    }

    fun copyItemStack(itemStack: ItemStack): ItemStack {
        val nested = descend(itemStack)
        return ItemStack(
            itemStack.itemId,
            itemStack.count,
            nested.copyPatch(itemStack.components),
            nested.copyProperties(itemStack.properties)
        )
    }

    fun copyValue(propertyValue: PropertyValue<*>): PropertyValue<*> {
        copiers[propertyValue.propertyType]?.let { return it.copy(propertyValue, descend(propertyValue.value)) }
        return when (propertyValue.propertyType) {
            PropertyTypes.Byte, PropertyTypes.Short, PropertyTypes.Int, PropertyTypes.Long,
            PropertyTypes.Float, PropertyTypes.Double, PropertyTypes.Boolean, PropertyTypes.String,
            PropertyTypes.Uuid, PropertyTypes.Nbt, PropertyTypes.BlockState -> duplicateCell(propertyValue)

            PropertyTypes.ByteArray -> PropertyValue(
                PropertyTypes.ByteArray,
                propertyValue.get(PropertyTypes.ByteArray).copyOf()
            )

            PropertyTypes.IntArray -> PropertyValue(
                PropertyTypes.IntArray,
                propertyValue.get(PropertyTypes.IntArray).copyOf()
            )

            PropertyTypes.LongArray -> PropertyValue(
                PropertyTypes.LongArray,
                propertyValue.get(PropertyTypes.LongArray).copyOf()
            )

            PropertyTypes.Properties -> PropertyValue(
                PropertyTypes.Properties,
                copyProperties(propertyValue.get(PropertyTypes.Properties))
            )

            PropertyTypes.List -> {
                val list = propertyValue.get(PropertyTypes.List)
                val nested = descend(list)
                PropertyValue(PropertyTypes.List, PropertyList(list.values.mapTo(mutableListOf(), nested::copyValue)))
            }

            PropertyTypes.ItemStack -> PropertyValue(
                PropertyTypes.ItemStack,
                copyItemStack(propertyValue.get(PropertyTypes.ItemStack))
            )

            PropertyTypes.ItemSlots -> {
                val slots = propertyValue.get(PropertyTypes.ItemSlots)
                val nested = descend(slots)
                PropertyValue(
                    PropertyTypes.ItemSlots,
                    ItemSlots(Array(slots.size) { slots[it]?.let(nested::copyItemStack) })
                )
            }

            PropertyTypes.Components -> {
                val components = propertyValue.get(PropertyTypes.Components)
                val nested = descend(components)
                PropertyValue(
                    PropertyTypes.Components,
                    DataComponentMap(components.entries.mapValuesTo(linkedMapOf()) { nested.copyValue(it.value) })
                )
            }

            PropertyTypes.ComponentPatch -> PropertyValue(
                PropertyTypes.ComponentPatch,
                copyPatch(propertyValue.get(PropertyTypes.ComponentPatch))
            )

            PropertyTypes.EntityAttributes -> {
                val attributes = propertyValue.get(PropertyTypes.EntityAttributes)
                val nested = descend(attributes)
                PropertyValue(
                    PropertyTypes.EntityAttributes,
                    EntityAttributes(attributes.entries.mapValuesTo(linkedMapOf()) { (_, instance) ->
                        val owner = nested.descend(instance)
                        AttributeInstance(
                            instance.baseValue,
                            instance.modifiers.mapValuesTo(linkedMapOf()) { (_, modifier) ->
                                modifier.copy(properties = owner.copyProperties(modifier.properties))
                            },
                            owner.copyProperties(instance.properties)
                        )
                    })
                )
            }

            PropertyTypes.EntityEffects -> {
                val effects = propertyValue.get(PropertyTypes.EntityEffects)
                val nested = descend(effects)
                PropertyValue(
                    PropertyTypes.EntityEffects,
                    EntityEffects(effects.entries.mapValuesTo(linkedMapOf()) { nested.copyEffect(it.value) })
                )
            }

            PropertyTypes.EntityEquipment -> {
                val equipment = propertyValue.get(PropertyTypes.EntityEquipment)
                val nested = descend(equipment)
                PropertyValue(
                    PropertyTypes.EntityEquipment,
                    EntityEquipment(equipment.slots.mapValuesTo(linkedMapOf()) { it.value?.let(nested::copyItemStack) })
                )
            }

            else -> error("No copier for property type ${propertyValue.propertyType.name}")
        }
    }

    private fun copyPatch(patch: DataComponentPatch): DataComponentPatch {
        val nested = descend(patch)
        return DataComponentPatch(patch.entries.mapValuesTo(linkedMapOf()) { (_, entry) ->
            when (entry) {
                ComponentPatchEntry.Removed -> ComponentPatchEntry.Removed
                is ComponentPatchEntry.SetValue -> ComponentPatchEntry.SetValue(nested.copyValue(entry.propertyValue))
            }
        })
    }

    private fun copyEffect(effect: EffectState): EffectState {
        val nested = descend(effect)
        return effect.copy(
            hiddenEffect = effect.hiddenEffect?.let(nested::copyEffect),
            properties = nested.copyProperties(effect.properties)
        )
    }

    private fun <T : Any> duplicateCell(propertyValue: PropertyValue<T>): PropertyValue<T> =
        PropertyValue(propertyValue.propertyType, propertyValue.value)

    private fun descend(value: Any): PropertyCopyContext {
        var current = path
        while (current != null) {
            require(current.value !== value) { "A copied property tree contains a cycle" }
            current = current.parent
        }
        return PropertyCopyContext(copiers, CopyVisit(value, path))
    }
}

private class CopyVisit(val value: Any, val parent: CopyVisit?)
