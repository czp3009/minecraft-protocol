package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

/** The official packet variants omit base-class fields fixed or absent in their own wire representation. */
object ServerboundMovePlayerPacket {
    @Serializable
    @PacketInfo(
        0x1E,
        ConnectionState.PLAY,
        PacketDirection.SERVERBOUND,
        officialName = "move_player_pos",
        shapeException = "PlayerMovementFlags is shared by all four movement variants; absent rotation and hasPos/hasRot constants are determined by the Pos packet type.",
    )
    data class Pos(
        val x: Double,
        val y: Double,
        val z: Double,
        val flags: PlayerMovementFlags,
    ) : PlayStatePacket, ServerboundPacket

    @Serializable
    @PacketInfo(
        0x1F,
        ConnectionState.PLAY,
        PacketDirection.SERVERBOUND,
        officialName = "move_player_pos_rot",
        shapeException = "PlayerMovementFlags is shared by all four movement variants; hasPos and hasRot are fixed by the PosRot packet type.",
    )
    data class PosRot(
        val x: Double,
        val y: Double,
        val z: Double,
        val yRot: Float,
        val xRot: Float,
        val flags: PlayerMovementFlags,
    ) : PlayStatePacket, ServerboundPacket

    @Serializable
    @PacketInfo(
        0x20,
        ConnectionState.PLAY,
        PacketDirection.SERVERBOUND,
        officialName = "move_player_rot",
        shapeException = "PlayerMovementFlags is shared by all four movement variants; absent position and hasPos/hasRot constants are determined by the Rot packet type.",
    )
    data class Rot(
        val yRot: Float,
        val xRot: Float,
        val flags: PlayerMovementFlags,
    ) : PlayStatePacket, ServerboundPacket

    @Serializable
    @PacketInfo(
        0x21,
        ConnectionState.PLAY,
        PacketDirection.SERVERBOUND,
        officialName = "move_player_status_only",
        shapeException = "PlayerMovementFlags is shared by all four movement variants; position, rotation and hasPos/hasRot constants are absent in StatusOnly.",
    )
    data class StatusOnly(
        val flags: PlayerMovementFlags,
    ) : PlayStatePacket, ServerboundPacket
}
