package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtTag
import kotlin.uuid.Uuid

/** A caller-owned type token. Equal names do not make two tokens interchangeable. */
class PropertyType<T : Any>(val name: String) {
    init {
        require(name.isNotBlank()) { "A property type name must not be blank" }
    }
}

/** A property name paired with the exact type token used by typed access to the shared dynamic store. */
data class PropertyKey<T : Any>(val name: String, val propertyType: PropertyType<T>)

data class PropertyValue<T : Any>(val propertyType: PropertyType<T>, val value: T) {
    /** The identity check establishes the association erased by a heterogeneous collection. */
    fun <V : Any> get(propertyType: PropertyType<V>): V {
        require(this.propertyType === propertyType) {
            "Property type ${this.propertyType.name} does not match ${propertyType.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return value as V
    }
}

/** One mutable store shared by dynamic access, typed keys and caller-written views. */
data class DataProperties(var entries: MutableMap<String, PropertyValue<*>> = linkedMapOf()) {
    operator fun get(name: String): PropertyValue<*>? = entries[name]

    operator fun set(name: String, propertyValue: PropertyValue<*>) {
        entries[name] = propertyValue
    }

    operator fun <T : Any> get(propertyKey: PropertyKey<T>): T? =
        entries[propertyKey.name]?.get(propertyKey.propertyType)

    operator fun <T : Any> set(propertyKey: PropertyKey<T>, value: T) {
        entries[propertyKey.name] = PropertyValue(propertyKey.propertyType, value)
    }

    fun <T : Any> require(propertyKey: PropertyKey<T>): T =
        get(propertyKey) ?: throw NoSuchElementException("Missing property ${propertyKey.name}")

    fun remove(name: String): PropertyValue<*>? = entries.remove(name)
}

/** An explicit empty value differs from an absent property. */
data class OptionalValue<T : Any>(val value: T?)

data class PropertyList(var values: MutableList<PropertyValue<*>> = mutableListOf())

/** Shared tokens for values whose identity is independent of any particular representation. */
object PropertyTypes {
    val Byte: PropertyType<Byte> = PropertyType("byte")
    val Short: PropertyType<Short> = PropertyType("short")
    val Int: PropertyType<Int> = PropertyType("int")
    val Long: PropertyType<Long> = PropertyType("long")
    val Float: PropertyType<Float> = PropertyType("float")
    val Double: PropertyType<Double> = PropertyType("double")
    val Boolean: PropertyType<Boolean> = PropertyType("boolean")
    val String: PropertyType<String> = PropertyType("string")
    val Uuid: PropertyType<Uuid> = PropertyType("uuid")
    val Properties: PropertyType<DataProperties> = PropertyType("properties")
    val List: PropertyType<PropertyList> = PropertyType("list")
    val Nbt: PropertyType<NbtTag> = PropertyType("nbt")
    val BlockState: PropertyType<BlockState> = PropertyType("block_state")
    val ItemStack: PropertyType<ItemStack> = PropertyType("item_stack")
    val ItemSlots: PropertyType<ItemSlots> = PropertyType("item_slots")
    val Components: PropertyType<DataComponentMap> = PropertyType("components")
    val ComponentPatch: PropertyType<DataComponentPatch> = PropertyType("component_patch")
    val EntityAttributes: PropertyType<EntityAttributes> = PropertyType("entity_attributes")
    val EntityEffects: PropertyType<EntityEffects> = PropertyType("entity_effects")
    val EntityEquipment: PropertyType<EntityEquipment> = PropertyType("entity_equipment")
}
