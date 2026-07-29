package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.EnumEncoding
import com.hiczp.minecraft.protocol.model.wire.EnumEncodingKind
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.WrappedEnum
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x1E,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "move_player_pos",
)
data class SetPlayerPositionPacket(
    val x: Double,
    val feetY: Double,
    val z: Double,
    val flags: PlayerMovementFlags,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x1F,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "move_player_pos_rot",
)
data class SetPlayerPositionAndRotationPacket(
    val x: Double,
    val feetY: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val flags: PlayerMovementFlags,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x20,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "move_player_rot",
)
data class SetPlayerRotationPacket(
    val yaw: Float,
    val pitch: Float,
    val flags: PlayerMovementFlags,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x21,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "move_player_status_only",
)
data class SetPlayerMovementFlagsPacket(
    val flags: PlayerMovementFlags,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x22,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "move_vehicle",
)
data class ServerboundMoveVehiclePacket(
    val position: Vector3d,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x23,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "paddle_boat",
)
data class PaddleBoatPacket(
    val leftPaddleTurning: Boolean,
    val rightPaddleTurning: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x24,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "pick_item_from_block",
)
data class PickItemFromBlockPacket(
    val location: BlockPosition,
    val includeData: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x25,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "pick_item_from_entity",
)
data class PickItemFromEntityPacket(
    @VarInt
    val entityId: Int,
    val includeData: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x26,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "ping_request",
)
data class PlayPingRequestPacket(
    val time: Long,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x27,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "place_recipe",
)
data class PlaceRecipePacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val recipeId: Int,
    val makeAll: Boolean,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x28,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_abilities",
)
data class ServerboundPlayerAbilitiesPacket(
    val abilities: ServerboundAbilities,
) : PlayStatePacket, ServerboundPacket

@Serializable
enum class PlayerAction {
    START_DESTROY_BLOCK,
    ABORT_DESTROY_BLOCK,
    STOP_DESTROY_BLOCK,
    DROP_ALL_ITEMS,
    DROP_ITEM,
    RELEASE_USE_ITEM,
    SWAP_ITEM_WITH_OFFHAND,
    STAB,
}

@Serializable
@PacketInfo(
    0x29,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_action",
)
data class PlayerActionPacket(
    val action: PlayerAction,
    val location: BlockPosition,
    @EnumEncoding(EnumEncodingKind.UNSIGNED_BYTE)
    @WrappedEnum
    val face: BlockFace,
    @VarInt
    val sequence: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
enum class PlayerCommandAction {
    STOP_SLEEPING,
    START_SPRINTING,
    STOP_SPRINTING,
    START_RIDING_JUMP,
    STOP_RIDING_JUMP,
    OPEN_INVENTORY,
    START_FALL_FLYING,
}

@Serializable
@PacketInfo(
    0x2A,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_command",
)
data class PlayerCommandPacket(
    @VarInt
    val entityId: Int,
    val action: PlayerCommandAction,
    @VarInt
    val jumpBoost: Int,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x2B,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_input",
)
data class PlayerInputPacket(
    val input: PlayerInput,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x2C,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "player_loaded",
)
data object PlayerLoadedPacket : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x2D,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "pong",
)
data class PlayPongPacket(
    val id: Int,
) : PlayStatePacket, ServerboundPacket
