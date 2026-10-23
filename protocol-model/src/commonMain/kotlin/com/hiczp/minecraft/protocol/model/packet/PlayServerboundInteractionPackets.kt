package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.BlockHitResult
import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.model.type.InteractionHand
import com.hiczp.minecraft.protocol.model.type.TestInstanceData
import com.hiczp.minecraft.protocol.model.wire.OptionalVarInt
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(
    0x3F,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "spectator_action",
)
data class ServerboundSpectatorActionPacket(
    @OptionalVarInt
    val spectateEntityId: Int?,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x2E,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "punch",
)
data object ServerboundPunchPacket : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x40,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "teleport_to_entity",
)
data class ServerboundTeleportToEntityPacket(
    val uuid: Uuid,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x41,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "test_instance_block_action",
)
data class ServerboundTestInstanceBlockActionPacket(
    val pos: BlockPosition,
    @ZeroFallbackEnum
    val action: Action,
    val data: TestInstanceData,
) : PlayStatePacket, ServerboundPacket {
    @Serializable
    enum class Action {
        INIT,
        QUERY,
        SET,
        RESET,
        SAVE,
        EXPORT,
        RUN,
    }
}

@Serializable
@PacketInfo(
    0x42,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "use_item_on",
)
data class ServerboundUseItemOnPacket(
    @ZeroFallbackEnum
    val hand: InteractionHand,
    val hitResult: BlockHitResult,
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
data class ServerboundUseItemPacket(
    @ZeroFallbackEnum
    val hand: InteractionHand,
    @VarInt
    val sequence: Int,
    val yRot: Float,
    val xRot: Float,
) : PlayStatePacket, ServerboundPacket
