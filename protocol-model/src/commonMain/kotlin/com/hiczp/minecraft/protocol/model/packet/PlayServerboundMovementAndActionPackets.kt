package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.BlockFace
import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.model.type.PlayerInput
import com.hiczp.minecraft.protocol.model.type.Vector3d
import com.hiczp.minecraft.protocol.model.wire.EnumEncoding
import com.hiczp.minecraft.protocol.model.wire.EnumEncodingKind
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.WrappedEnum
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x22,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "move_vehicle",
)
data class ServerboundMoveVehiclePacket(
    val position: Vector3d,
    val yRot: Float,
    val xRot: Float,
    val onGround: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x23,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "paddle_boat",
)
data class ServerboundPaddleBoatPacket(
    val left: Boolean,
    val right: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x24,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "pick_item_from_block",
)
data class ServerboundPickItemFromBlockPacket(
    val pos: BlockPosition,
    val includeData: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x25,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "pick_item_from_entity",
)
data class ServerboundPickItemFromEntityPacket(
    @VarInt
    val id: Int,
    val includeData: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.STATUS, PacketDirection.SERVERBOUND, "ping_request")
@PacketInfo(
    0x26,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "ping_request",
)
data class ServerboundPingRequestPacket(
    val time: Long,
) : PlayStatePacket, StatusStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x27,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "place_recipe",
)
data class ServerboundPlaceRecipePacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val recipe: Int,
    val useMaxItems: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable(with = ServerboundPlayerAbilitiesPacketSerializer::class)
@PacketInfo(
    0x28,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_abilities",
)
data class ServerboundPlayerAbilitiesPacket(
    val isFlying: Boolean,
) : PlayStatePacket, ServerboundPacket


@Serializable(with = ServerboundPlayerActionPacketSerializer::class)
@PacketInfo(
    0x29,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_action",
)
data class ServerboundPlayerActionPacket(
    val pos: BlockPosition,
    @EnumEncoding(EnumEncodingKind.UNSIGNED_BYTE)
    @WrappedEnum
    val direction: BlockFace,
    val action: Action,
    @VarInt
    val sequence: Int,
) : PlayStatePacket, ServerboundPacket {
    @Serializable
    enum class Action {
        START_DESTROY_BLOCK,
        ABORT_DESTROY_BLOCK,
        STOP_DESTROY_BLOCK,
        DROP_ALL_ITEMS,
        DROP_ITEM,
        RELEASE_USE_ITEM,
        SWAP_ITEM_WITH_OFFHAND,
        STAB,
    }
}


@Serializable
@PacketInfo(
    0x2A,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_command",
)
data class ServerboundPlayerCommandPacket(
    @VarInt
    val id: Int,
    val action: Action,
    @VarInt
    val data: Int,
) : PlayStatePacket, ServerboundPacket {
    @Serializable
    enum class Action {
        STOP_SLEEPING,
        START_SPRINTING,
        STOP_SPRINTING,
        START_RIDING_JUMP,
        STOP_RIDING_JUMP,
        OPEN_INVENTORY,
        START_FALL_FLYING,
    }
}

@Serializable
@PacketInfo(
    0x2B,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_input",
)
data class ServerboundPlayerInputPacket(
    val input: PlayerInput,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x2C,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_loaded",
)
data object ServerboundPlayerLoadedPacket : PlayStatePacket, ServerboundPacket
