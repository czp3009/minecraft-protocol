package com.hiczp.minecraft.world.format


/** Value writers for shared data structures; applications bind any content-specific fields themselves. */
object NbtPropertyWriters {
    val blockState: NbtPropertyValueWriter<BlockState> =
        NbtPropertyValueWriter(PropertyTypes.BlockState) { blockState, _ -> encodeBlockState(blockState) }
    val itemStack: NbtPropertyValueWriter<ItemStack> = NbtPropertyValueWriter(PropertyTypes.ItemStack, ::writeItemStack)
    val itemSlots: NbtPropertyValueWriter<ItemSlots> = NbtPropertyValueWriter(PropertyTypes.ItemSlots, ::writeItemSlots)
    val components: NbtPropertyValueWriter<DataComponentMap> =
        NbtPropertyValueWriter(PropertyTypes.Components, ::encodeComponents)
    val componentPatch: NbtPropertyValueWriter<DataComponentPatch> =
        NbtPropertyValueWriter(PropertyTypes.ComponentPatch, ::writeComponentPatch)
    val entityAttributes: NbtPropertyValueWriter<EntityAttributes> =
        NbtPropertyValueWriter(PropertyTypes.EntityAttributes, ::writeEntityAttributes)
    val entityEffects: NbtPropertyValueWriter<EntityEffects> =
        NbtPropertyValueWriter(PropertyTypes.EntityEffects, ::writeEntityEffects)
    val entityEquipment: NbtPropertyValueWriter<EntityEquipment> =
        NbtPropertyValueWriter(PropertyTypes.EntityEquipment, ::writeEntityEquipment)

    val types: List<NbtPropertyValueWriter<*>> = listOf(
        blockState, itemStack, itemSlots, components, componentPatch, entityAttributes, entityEffects, entityEquipment,
    )
}
