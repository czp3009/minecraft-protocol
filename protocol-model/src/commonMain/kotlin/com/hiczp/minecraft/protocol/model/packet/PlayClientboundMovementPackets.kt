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
data class WorldEventPacket(
    val eventId: Int,
    val location: BlockPosition,
    val data: Int,
    val disableRelativeVolume: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x32,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "low_disk_space_warning",
)
data object LowDiskSpaceWarningPacket :
    PlayStatePacket,
    ClientboundPacket

@Serializable
@PacketInfo(
    0x35,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "move_entity_pos",
)
data class UpdateEntityPositionPacket(
    @VarInt
    val entityId: Int,
    val deltaX: Short,
    val deltaY: Short,
    val deltaZ: Short,
    val onGround: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x36,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "move_entity_pos_rot",
)
data class UpdateEntityPositionAndRotationPacket(
    @VarInt
    val entityId: Int,
    val deltaX: Short,
    val deltaY: Short,
    val deltaZ: Short,
    val yaw: Angle,
    val pitch: Angle,
    val onGround: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x37,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "move_minecart_along_track",
)
data class MoveMinecartAlongTrackPacket(
    @VarInt
    val entityId: Int,
    val steps: List<MinecartStep>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x38,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "move_entity_rot",
)
data class UpdateEntityRotationPacket(
    @VarInt
    val entityId: Int,
    val yaw: Angle,
    val pitch: Angle,
    val onGround: Boolean,
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
    val yaw: Float,
    val pitch: Float,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "open_book",
)
data class OpenBookPacket(
    val hand: InteractionHand,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "open_screen",
)
data class OpenScreenPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val menuTypeId: Int,
    val title: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "open_sign_editor",
)
data class OpenSignEditorPacket(
    val location: BlockPosition,
    val frontText: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "ping",
)
data class ClientboundPingPacket(
    val id: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x3E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "pong_response",
)
data class PongResponsePacket(
    val timestamp: Long,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x40,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_abilities",
)
data class ClientboundPlayerAbilitiesPacket(
    val abilities: PlayerAbilities,
) : PlayStatePacket, ClientboundPacket
