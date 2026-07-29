package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x12,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "container_set_content",
)
data class SetContainerContentPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val stateId: Int,
    val items: List<ItemStack>,
    val carriedItem: ItemStack,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x14,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "container_set_slot",
)
data class SetContainerSlotPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val stateId: Int,
    val slot: Short,
    val item: ItemStack,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x34,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "merchant_offers",
)
data class MerchantOffersPacket(
    @VarInt
    val containerId: Int,
    val offers: List<MerchantOffer>,
    @VarInt
    val villagerLevel: Int,
    @VarInt
    val villagerExperience: Int,
    val showProgress: Boolean,
    val canRestock: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x60,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_cursor_item",
)
data class SetCursorItemPacket(
    val contents: ItemStack,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x66,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_equipment",
)
data class SetEquipmentPacket(
    @VarInt
    val entityId: Int,
    val updates: EquipmentUpdates,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_player_inventory",
)
data class SetPlayerInventorySlotPacket(
    @VarInt
    val slot: Int,
    val contents: ItemStack,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x12,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "container_click",
)
data class ClickContainerPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val stateId: Int,
    val slot: Short,
    val button: Byte,
    @ZeroFallbackEnum
    val input: ContainerInput,
    @MaxCollectionSize(128)
    val changedSlots: List<ChangedHashedSlot>,
    val carriedItem: HashedStack,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x38,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "set_creative_mode_slot",
)
data class SetCreativeModeSlotPacket(
    val slot: Short,
    @Serializable(with = UntrustedItemStackSerializer::class)
    val item: ItemStack,
) : PlayStatePacket, ServerboundPacket
