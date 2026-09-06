package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtTag

/** Value readers with no block/entity type selection or game-content field bindings. */
object NbtPropertyReaders {
    val blockState: NbtPropertyReader = reader(PropertyTypes.BlockState) { nbtTag, _ -> decodeBlockState(nbtTag) }
    val itemStack: NbtPropertyReader = reader(PropertyTypes.ItemStack) { nbtTag, mappings ->
        readItemStack(nbtTag.compound(), mappings)
    }
    val components: NbtPropertyReader = reader(PropertyTypes.Components) { nbtTag, mappings ->
        decodeComponents(nbtTag.compound(), mappings)
    }
    val componentPatch: NbtPropertyReader = reader(PropertyTypes.ComponentPatch) { nbtTag, mappings ->
        readComponentPatch(nbtTag.compound(), mappings)
    }
    val entityAttributes: NbtPropertyReader = reader(PropertyTypes.EntityAttributes, ::readEntityAttributes)
    val entityEffects: NbtPropertyReader = reader(PropertyTypes.EntityEffects, ::readEntityEffects)
    val entityEquipment: NbtPropertyReader = reader(PropertyTypes.EntityEquipment, ::readEntityEquipment)

    /** The enclosing inventory's size is a caller-supplied fact absent from a sparse saved slot list. */
    fun itemSlots(slotCount: Int): NbtPropertyReader {
        require(slotCount >= 0) { "An inventory slot count must not be negative" }
        return reader(PropertyTypes.ItemSlots) { nbtTag, mappings -> readItemSlots(nbtTag.list(), slotCount, mappings) }
    }

    private fun <T : Any> reader(
        propertyType: PropertyType<T>,
        read: (NbtTag, NbtPropertyReadMappings) -> T,
    ): NbtPropertyReader = NbtPropertyReader { nbtTag, mappings -> PropertyValue(propertyType, read(nbtTag, mappings)) }
}
