package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.Angle
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

/** The official packet variants omit base-class fields fixed or absent in their own wire representation. */
object ClientboundMoveEntityPacket {
    @Serializable
    @PacketInfo(
        0x35,
        ConnectionState.PLAY,
        PacketDirection.CLIENTBOUND,
        officialName = "move_entity_pos",
        shapeException = "The Pos variant omits base-class rotation and discriminator fields fixed by its packet type; only transmitted members are constructor data.",
    )
    data class Pos(
        @VarInt
        val entityId: Int,
        val xa: Short,
        val ya: Short,
        val za: Short,
        val onGround: Boolean,
    ) : PlayStatePacket, ClientboundPacket

    @Serializable
    @PacketInfo(
        0x36,
        ConnectionState.PLAY,
        PacketDirection.CLIENTBOUND,
        officialName = "move_entity_pos_rot",
        shapeException = "The PosRot variant omits hasRot and hasPos because both are fixed by its packet type.",
    )
    data class PosRot(
        @VarInt
        val entityId: Int,
        val xa: Short,
        val ya: Short,
        val za: Short,
        val yRot: Angle,
        val xRot: Angle,
        val onGround: Boolean,
    ) : PlayStatePacket, ClientboundPacket

    @Serializable
    @PacketInfo(
        0x38,
        ConnectionState.PLAY,
        PacketDirection.CLIENTBOUND,
        officialName = "move_entity_rot",
        shapeException = "The Rot variant omits base-class position and discriminator fields fixed by its packet type; only transmitted members are constructor data.",
    )
    data class Rot(
        @VarInt
        val entityId: Int,
        val yRot: Angle,
        val xRot: Angle,
        val onGround: Boolean,
    ) : PlayStatePacket, ClientboundPacket
}
