package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x11,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "container_button_click",
)
data class ClickContainerButtonPacket(
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
data class ServerboundCloseContainerPacket(
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
data class ChangeContainerSlotStatePacket(
    @VarInt
    val slotId: Int,
    @VarInt
    val containerId: Int,
    val enabled: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x15,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "cookie_response",
)
data class PlayCookieResponsePacket(
    val key: Identifier,
    @MaxByteLength(5_120)
    val payload: ByteString?,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x16,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "custom_payload",
)
data class PlayServerboundPluginMessagePacket(
    @Serializable(with = ServerboundCustomPayloadSerializer::class)
    val payload: CustomPayload,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x17,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "debug_subscription_request",
)
data class DebugSubscriptionRequestPacket(
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
data class EditBookPacket(
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
data class QueryEntityTagPacket(
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
data class InteractPacket(
    @VarInt
    val entityId: Int,
    val hand: InteractionHand,
    @LowPrecisionVector
    val targetOffset: Vector3d,
    val usingSecondaryAction: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x1B,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "jigsaw_generate",
)
data class JigsawGeneratePacket(
    val location: BlockPosition,
    @VarInt
    val levels: Int,
    val keepJigsaws: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x1C,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "keep_alive",
)
data class PlayServerboundKeepAlivePacket(
    val id: Long,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x1D,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "lock_difficulty",
)
data class LockDifficultyPacket(
    val locked: Boolean,
) : PlayStatePacket, ServerboundPacket
