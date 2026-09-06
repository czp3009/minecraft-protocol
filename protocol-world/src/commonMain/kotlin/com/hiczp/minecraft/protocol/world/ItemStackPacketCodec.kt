@file:OptIn(InternalDataComponentRegistryApi::class)

package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.DataComponentPatch
import com.hiczp.minecraft.world.format.ItemStack
import com.hiczp.minecraft.protocol.model.type.DataComponentPatch as PacketDataComponentPatch
import com.hiczp.minecraft.protocol.model.type.ItemStack as PacketItemStack

/** Destination item registry and explicit semantic-to-packet component projections. */
data class ItemStackPacketEncoderContext(
    val packetCodecContext: PacketCodecContext,
    val itemStackPacketWriteMappings: ItemStackPacketWriteMappings,
)

/** Source item registry, component readers and a provider for properties absent from item packets. */
data class ItemStackPacketDecoderContext(
    val packetCodecContext: PacketCodecContext,
    val itemStackPacketReadMappings: ItemStackPacketReadMappings,
)

/** Null explicitly omits a component with no chosen client projection. */
data class ItemStackPacketWriteMappings(
    val component: (ComponentId, PropertyValue<*>, PacketCodecContext) -> DataComponent?,
)

/** Components become semantic property values; packet-only data is not installed as a parallel mutable copy. */
data class ItemStackPacketReadMappings(
    val component: (DataComponent, PacketCodecContext) -> PropertyValue<*>,
    val properties: (ItemId) -> DataProperties,
)

/** Projects a semantic stack to a protocol item value without mutating the input or managing a menu. */
class ItemStackPacketEncoder(val itemStackPacketEncoderContext: ItemStackPacketEncoderContext) {
    /** Maps null or a nonpositive count to the protocol empty stack; omits components only when the mapping returns null. */
    fun encode(itemStack: ItemStack?): PacketItemStack {
        if (itemStack == null || itemStack.count <= 0) return PacketItemStack.Empty
        val packetCodecContext = itemStackPacketEncoderContext.packetCodecContext
        val added = mutableListOf<DataComponent>()
        val removed = linkedSetOf<DataComponentType>()
        itemStack.components.entries.forEach { (componentId, entry) ->
            when (entry) {
                ComponentPatchEntry.Removed -> removed.add(
                    requireNotNull(
                        DataComponentType.entries.firstOrNull { it.wireName == componentId.value },
                    ) { "Removed component $componentId has no packet representation" })

                is ComponentPatchEntry.SetValue -> {
                    val value = itemStackPacketEncoderContext.itemStackPacketWriteMappings.component(
                        componentId, entry.propertyValue, packetCodecContext,
                    )
                    if (value != null) {
                        require(GeneratedDataComponentSerializers.type(value).wireName == componentId.value) {
                            "Component mapping for $componentId produced a different component type"
                        }
                        added.add(value)
                    }
                }
            }
        }
        return PacketItemStack.Present(
            itemStack.count,
            packetCodecContext.requireRegistryEntry(ITEM_REGISTRY, Identifier(itemStack.itemId.value)).rawId,
            PacketDataComponentPatch(added, removed),
        )
    }
}

/** Constructs a semantic stack from a protocol item value and caller-supplied component mappings. */
class ItemStackPacketDecoder(val itemStackPacketDecoderContext: ItemStackPacketDecoderContext) {
    /** Returns null for the protocol empty stack; otherwise constructs the item and its component patch. */
    fun decode(itemStack: PacketItemStack): ItemStack? {
        if (itemStack is PacketItemStack.Empty) return null
        itemStack as PacketItemStack.Present
        val packetCodecContext = itemStackPacketDecoderContext.packetCodecContext
        val itemId = ItemId(requireNotNull(packetCodecContext.requireRegistry(ITEM_REGISTRY)[itemStack.itemId]) {
            "Item raw ID ${itemStack.itemId} has no installed mapping"
        }.id.value)
        val components = DataComponentPatch()
        itemStack.components.added.forEach { component ->
            val componentId = ComponentId(GeneratedDataComponentSerializers.type(component).wireName)
            components.entries[componentId] = ComponentPatchEntry.SetValue(
                itemStackPacketDecoderContext.itemStackPacketReadMappings.component(component, packetCodecContext),
            )
        }
        itemStack.components.removed.forEach {
            components.entries[ComponentId(it.wireName)] = ComponentPatchEntry.Removed
        }
        return ItemStack(
            itemId, itemStack.count, components,
            itemStackPacketDecoderContext.itemStackPacketReadMappings.properties(itemId),
        )
    }
}

private val ITEM_REGISTRY = Identifier("item")
