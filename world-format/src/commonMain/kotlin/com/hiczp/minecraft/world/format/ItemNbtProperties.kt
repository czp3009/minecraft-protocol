package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

internal fun readItemStack(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): ItemStack {
    val itemId = ItemId.parse(nbtCompound.string("id"))
    val count = nbtCompound.int("count", 1)
    require(count in 1..99) { "A persisted ItemStack count must be in 1..99" }
    return ItemStack(
        itemId, count,
        readComponentPatch(nbtCompound.optionalTag<NbtCompound>("components") ?: NbtCompound(emptyMap()), mappings),
        mappings.readProperties(nbtCompound, NbtPropertyScope("item_stack", itemId.toString()), ITEM_NBT_FIELDS)
    )
}

internal fun writeItemStack(value: ItemStack, mappings: NbtPropertyWriteMappings): NbtCompound {
    require(value.count in 1..99) { "A persisted ItemStack count must be in 1..99" }
    val fields = mappings.writeProperties(
        value.properties,
        NbtPropertyScope("item_stack", value.itemId.toString()),
        ITEM_NBT_FIELDS
    )
    fields["id"] = NbtString(value.itemId.toString())
    fields["count"] = NbtInt(value.count)
    if (value.components.entries.isNotEmpty()) fields["components"] = writeComponentPatch(value.components, mappings)
    return NbtCompound(fields)
}

internal fun readItemSlots(nbtList: NbtList, slotCount: Int, mappings: NbtPropertyReadMappings): ItemSlots {
    val slots = MutableList<ItemStack?>(slotCount) { null }
    nbtList.forEach { tag ->
        val item = tag.compound()
        val slot = item.requiredTag<NbtByte>("Slot").value.toInt() and 255
        require(slot in slots.indices) { "Inventory slot $slot is outside the supplied $slotCount slots" }
        require(slots[slot] == null) { "Duplicate inventory slot $slot" }
        slots[slot] = readItemStack(NbtCompound(item.value - "Slot"), mappings)
    }
    return ItemSlots(slots)
}

internal fun writeItemSlots(value: ItemSlots, mappings: NbtPropertyWriteMappings): NbtList = NbtList(buildList {
    value.items.forEachIndexed { slot, item ->
        if (item != null) {
            require(slot in 0..255) { "Inventory slot $slot cannot be stored in an unsigned Byte" }
            require("Slot" !in item.properties.entries) { "An ItemStack property conflicts with its inventory Slot" }
            add(NbtCompound(writeItemStack(item, mappings).value + ("Slot" to NbtByte(slot.toByte()))))
        }
    }
})

internal fun readEntityEquipment(nbtTag: NbtTag, mappings: NbtPropertyReadMappings): EntityEquipment = EntityEquipment(
    nbtTag.compound().value.entries.associateTo(linkedMapOf()) { (name, tag) ->
        val item = tag.compound()
        EquipmentSlotId(name) to if (item.size == 0) null else readItemStack(item, mappings)
    },
)

internal fun writeEntityEquipment(value: EntityEquipment, mappings: NbtPropertyWriteMappings): NbtCompound =
    NbtCompound(
        value.slots.entries.associate { (slot, item) ->
            slot.toString() to (item?.let { writeItemStack(it, mappings) } ?: NbtCompound(emptyMap()))
        },
    )

private val ITEM_NBT_FIELDS = setOf("id", "count", "components")
