package com.hiczp.minecraft.world.format


data class DataComponentMap(var entries: MutableMap<ComponentId, PropertyValue<*>> = linkedMapOf())

/** An absent entry inherits the item's default; Removed and SetValue are explicit overrides. */
data class DataComponentPatch(var entries: MutableMap<ComponentId, ComponentPatchEntry> = linkedMapOf())

sealed interface ComponentPatchEntry {
    data class SetValue(val propertyValue: PropertyValue<*>) : ComponentPatchEntry
    data object Removed : ComponentPatchEntry
}

data class ItemStack(
    var itemId: ItemId,
    var count: Int,
    var components: DataComponentPatch = DataComponentPatch(),
    var properties: DataProperties = DataProperties(),
)

/** Fixed slot meaning belongs to the enclosing inventory. A null entry is an empty slot. */
data class ItemSlots(var items: MutableList<ItemStack?>) {
    constructor(slotCount: Int) : this(MutableList(slotCount) { null })

    val size: Int get() = items.size

    operator fun get(slot: Int): ItemStack? = items[slot]

    operator fun set(slot: Int, itemStack: ItemStack?) {
        items[slot] = itemStack
    }
}

data class BlockEntity(
    var blockEntityTypeId: BlockEntityTypeId,
    var components: DataComponentMap = DataComponentMap(),
    var properties: DataProperties = DataProperties(),
)
