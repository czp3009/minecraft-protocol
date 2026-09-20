package com.hiczp.minecraft.world.format


data class DataComponentMap(var entries: MutableMap<ComponentId, PropertyValue<*>> = linkedMapOf())

/** An absent entry inherits the item's default; Removed and SetValue are explicit overrides. */
data class DataComponentPatch(var entries: MutableMap<ComponentId, ComponentPatchEntry> = linkedMapOf())

sealed interface ComponentPatchEntry {
    data class SetValue(val propertyValue: PropertyValue<*>) : ComponentPatchEntry
    data object Removed : ComponentPatchEntry
}

/**
 * Editable stack contents with no inventory owner. Constructors retain references; [copy] explicitly detaches mutable
 * built-in children. Equality compares current contents, so mutable stacks must not be used as stable hash keys.
 */
class ItemStack(
    var itemId: ItemId,
    var count: Int,
    var components: DataComponentPatch = DataComponentPatch(),
    var properties: DataProperties = DataProperties(),
) {
    /** Copies mutable built-in values directly; custom values need an explicit copier, never an NBT round trip. */
    fun copy(count: Int = this.count, propertyCopyContext: PropertyCopyContext = PropertyCopyContext()): ItemStack =
        propertyCopyContext.copyItemStack(this).also { it.count = count }

    override fun equals(other: Any?): Boolean = other is ItemStack && itemId == other.itemId && count == other.count &&
            components == other.components && properties == other.properties

    override fun hashCode(): Int =
        31 * (31 * (31 * itemId.hashCode() + count) + components.hashCode()) + properties.hashCode()
}

/** Fixed slot meaning belongs to the enclosing inventory. A null entry is an empty slot. */
data class ItemSlots(var items: Array<ItemStack?>) {
    constructor(slotCount: Int) : this(arrayOfNulls<ItemStack>(slotCount))

    override fun equals(other: Any?): Boolean = other is ItemSlots && items.contentEquals(other.items)
    override fun hashCode(): Int = items.contentHashCode()

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
