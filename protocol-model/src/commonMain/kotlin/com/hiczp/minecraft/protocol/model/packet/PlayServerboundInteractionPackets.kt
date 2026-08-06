package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(
    0x3E,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "spectator_action",
)
data class SpectatorActionPacket(
    @OptionalVarInt
    val spectateEntityId: Int?,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x3F,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "swing",
)
data class SwingArmPacket(
    val hand: InteractionHand,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x40,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "teleport_to_entity",
)
data class TeleportToEntityPacket(
    val target: Uuid,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x41,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "test_instance_block_action",
)
data class TestInstanceBlockActionPacket(
    val position: BlockPosition,
    @ZeroFallbackEnum
    val action: TestInstanceAction,
    val data: TestInstanceData,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x42,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "use_item_on",
)
data class UseItemOnPacket(
    val hand: InteractionHand,
    val hit: BlockHitResult,
    @VarInt
    val sequence: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x43,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "use_item",
)
data class UseItemPacket(
    val hand: InteractionHand,
    @VarInt
    val sequence: Int,
    val yaw: Float,
    val pitch: Float,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x44,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "custom_click_action",
)
data class PlayCustomClickActionPacket(
    val id: Identifier,
    @ByteLengthPrefixed(65_536)
    @NbtEndOptional
    val payload: NbtTag?,
) : PlayStatePacket, ServerboundPacket
