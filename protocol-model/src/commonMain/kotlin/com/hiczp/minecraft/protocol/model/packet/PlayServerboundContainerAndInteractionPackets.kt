package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.model.type.DebugSubscriptionType
import com.hiczp.minecraft.protocol.model.type.InteractionHand
import com.hiczp.minecraft.protocol.model.type.Vector3d
import com.hiczp.minecraft.protocol.model.wire.LowPrecisionVector
import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x11,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "container_button_click",
)
data class ServerboundContainerButtonClickPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val buttonId: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x13,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "container_close",
)
data class ServerboundContainerClosePacket(
    @VarInt
    val containerId: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x14,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "container_slot_state_changed",
)
data class ServerboundContainerSlotStateChangedPacket(
    @VarInt
    val slotId: Int,
    @VarInt
    val containerId: Int,
    val newState: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x17,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "debug_subscription_request",
)
data class ServerboundDebugSubscriptionRequestPacket(
    @MaxCollectionSize(32)
    val subscriptions: Set<DebugSubscriptionType>,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x18,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "edit_book",
)
data class ServerboundEditBookPacket(
    @VarInt
    val slot: Int,
    @MaxCollectionSize(100)
    @MaxLength(1_024)
    val pages: List<String>,
    @MaxLength(32)
    val title: String?,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x19,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "entity_tag_query",
)
data class ServerboundEntityTagQueryPacket(
    @VarInt
    val transactionId: Int,
    @VarInt
    val entityId: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x1A,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "interact",
)
data class ServerboundInteractPacket(
    @VarInt
    val entityId: Int,
    val hand: InteractionHand,
    @LowPrecisionVector
    val location: Vector3d,
    val usingSecondaryAction: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x1B,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "jigsaw_generate",
)
data class ServerboundJigsawGeneratePacket(
    val pos: BlockPosition,
    @VarInt
    val levels: Int,
    val keepJigsaws: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x1D,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "lock_difficulty",
)
data class ServerboundLockDifficultyPacket(
    val locked: Boolean,
) : PlayStatePacket, ServerboundPacket
