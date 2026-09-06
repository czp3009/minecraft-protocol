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
data class ClientboundContainerSetContentPacket(
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
data class ClientboundContainerSetSlotPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val stateId: Int,
    val slot: Short,
    val itemStack: ItemStack,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x34,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "merchant_offers",
)
data class ClientboundMerchantOffersPacket(
    @VarInt
    val containerId: Int,
    val offers: List<MerchantOffer>,
    @VarInt
    val villagerLevel: Int,
    @VarInt
    val villagerXp: Int,
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
data class ClientboundSetCursorItemPacket(
    val contents: ItemStack,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x66,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_equipment",
)
data class ClientboundSetEquipmentPacket(
    @VarInt
    val entity: Int,
    val slots: EquipmentUpdates,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_player_inventory",
)
data class ClientboundSetPlayerInventoryPacket(
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
data class ServerboundContainerClickPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val stateId: Int,
    val slotNum: Short,
    val buttonNum: Byte,
    @ZeroFallbackEnum
    val containerInput: ContainerInput,
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
data class ServerboundSetCreativeModeSlotPacket(
    val slotNum: Short,
    @Serializable(with = UntrustedItemStackSerializer::class)
    val itemStack: ItemStack,
) : PlayStatePacket, ServerboundPacket
