package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x2E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "level_event",
)
data class ClientboundLevelEventPacket(
    val type: Int,
    val pos: BlockPosition,
    val data: Int,
    val globalEvent: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x32,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "low_disk_space_warning",
)
data object ClientboundLowDiskSpaceWarningPacket :
    PlayStatePacket,
    ClientboundPacket

@Serializable
@PacketInfo(
    0x37,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "move_minecart_along_track",
)
data class ClientboundMoveMinecartPacket(
    @VarInt
    val entityId: Int,
    val lerpSteps: List<MinecartStep>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x39,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "move_vehicle",
)
data class ClientboundMoveVehiclePacket(
    val position: Vector3d,
    val yRot: Float,
    val xRot: Float,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "open_book",
)
data class ClientboundOpenBookPacket(
    val hand: InteractionHand,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "open_screen",
)
data class ClientboundOpenScreenPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val type: Int,
    val title: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "open_sign_editor",
)
data class ClientboundOpenSignEditorPacket(
    val pos: BlockPosition,
    val isFrontText: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.STATUS, PacketDirection.CLIENTBOUND, "pong_response")
@PacketInfo(
    0x3E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "pong_response",
)
data class ClientboundPongResponsePacket(
    val time: Long,
) : PlayStatePacket, StatusStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x40,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_abilities",
    shapeException = "PlayerAbilities is the shared logical abilities value used by initial world/bootstrap and other protocol-facing consumers; its flags and speeds remain one value.",
)
data class ClientboundPlayerAbilitiesPacket(
    val abilities: PlayerAbilities,
) : PlayStatePacket, ClientboundPacket
