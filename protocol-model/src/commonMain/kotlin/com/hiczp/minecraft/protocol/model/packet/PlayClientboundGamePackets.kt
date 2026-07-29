package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.PositionMoveRotation
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x22,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "entity_event",
)
data class EntityEventPacket(
    val entityId: Int,
    val eventId: Byte,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x23,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "entity_position_sync",
)
data class TeleportEntityPacket(
    @VarInt
    val entityId: Int,
    val values: PositionMoveRotation,
    val onGround: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x25,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "forget_level_chunk",
)
data class UnloadChunkPacket(
    val chunkZ: Int,
    val chunkX: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
enum class GameEventType {
    NO_RESPAWN_BLOCK_AVAILABLE,
    START_RAINING,
    STOP_RAINING,
    CHANGE_GAME_MODE,
    WIN_GAME,
    DEMO_EVENT,
    PLAY_ARROW_HIT_SOUND,
    RAIN_LEVEL_CHANGE,
    THUNDER_LEVEL_CHANGE,
    PUFFER_FISH_STING,
    GUARDIAN_ELDER_EFFECT,
    IMMEDIATE_RESPAWN,
    LIMITED_CRAFTING,
    LEVEL_CHUNKS_LOAD_START,
}

@Serializable
@PacketInfo(
    0x26,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "game_event",
)
data class GameEventPacket(
    @EnumEncoding(EnumEncodingKind.UNSIGNED_BYTE)
    val event: GameEventType,
    val value: Float,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x27,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "game_rule_values",
)
data class GameRuleValuesPacket(
    @MaxLength(32_767)
    val values: Map<Identifier, String>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x28,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "game_test_highlight_pos",
)
data class GameTestHighlightPositionPacket(
    val absolutePosition: BlockPosition,
    val relativePosition: BlockPosition,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x29,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "mount_screen_open",
)
data class OpenHorseScreenPacket(
    @VarInt
    val containerId: Int,
    @VarInt
    val inventoryColumns: Int,
    val entityId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x2A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "hurt_animation",
)
data class HurtAnimationPacket(
    @VarInt
    val entityId: Int,
    val yaw: Float,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x2B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "initialize_border",
)
data class InitializeWorldBorderPacket(
    val centerX: Double,
    val centerZ: Double,
    val oldDiameter: Double,
    val newDiameter: Double,
    @VarLong
    val speedMilliseconds: Long,
    @VarInt
    val portalTeleportBoundary: Int,
    @VarInt
    val warningBlocks: Int,
    @VarInt
    val warningTimeSeconds: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x2C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "keep_alive",
)
data class PlayClientboundKeepAlivePacket(
    val id: Long,
) : PlayStatePacket, ClientboundPacket
