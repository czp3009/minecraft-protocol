package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.Angle
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x42,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_combat_end",
)
data class EndCombatPacket(
    @VarInt
    val durationTicks: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x43,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_combat_enter",
)
data object EnterCombatPacket : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x44,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_combat_kill",
)
data class CombatDeathPacket(
    @VarInt
    val playerId: Int,
    val message: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x45,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_info_remove",
)
data class PlayerInfoRemovePacket(
    val profileIds: List<Uuid>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x47,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_look_at",
)
data class LookAtPacket(
    val fromAnchor: EntityAnchor,
    val target: LookTarget,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x48,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_position",
)
data class SynchronizePlayerPositionPacket(
    @VarInt
    val teleportId: Int,
    val change: PositionMoveRotation,
    val relatives: RelativeMovements,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x49,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_rotation",
)
data class PlayerRotationPacket(
    val yaw: Float,
    val relativeYaw: Boolean,
    val pitch: Float,
    val relativePitch: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "recipe_book_remove",
)
data class RecipeBookRemovePacket(
    @VarIntElements
    val recipeDisplayIds: List<Int>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "recipe_book_settings",
)
data class RecipeBookSettingsPacket(
    val settings: RecipeBookSettings,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "remove_entities",
)
data class RemoveEntitiesPacket(
    @VarIntElements
    val entityIds: List<Int>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "remove_mob_effect",
)
data class RemoveEntityEffectPacket(
    @VarInt
    val entityId: Int,
    @VarInt
    val effectTypeId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "reset_score",
)
data class ResetScorePacket(
    @MaxLength(32_767)
    val owner: String,
    @MaxLength(32_767)
    val objectiveName: String?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x50,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "resource_pack_pop",
)
data class PlayRemoveResourcePackPacket(
    val id: Uuid?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x51,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "resource_pack_push",
)
data class PlayAddResourcePackPacket(
    val id: Uuid,
    @MaxLength(32_767)
    val url: String,
    @MaxLength(40)
    val hash: String,
    val required: Boolean,
    val prompt: TextComponent?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x53,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "rotate_head",
)
data class SetHeadRotationPacket(
    @VarInt
    val entityId: Int,
    val headYaw: Angle,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x54,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "section_blocks_update",
)
data class UpdateSectionBlocksPacket(
    val sectionPosition: SectionPosition,
    @VarLongElements
    val blocks: List<SectionBlockChange>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x55,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "select_advancements_tab",
)
data class SelectAdvancementsTabPacket(
    val tab: Identifier?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x56,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "server_data",
)
data class ServerDataPacket(
    val motd: TextComponent,
    @MaxByteLength(Int.MAX_VALUE)
    val iconPng: ByteString?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x57,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_action_bar_text",
)
data class SetActionBarTextPacket(
    val text: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x58,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_center",
)
data class SetBorderCenterPacket(
    val x: Double,
    val z: Double,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x59,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_lerp_size",
)
data class SetBorderLerpSizePacket(
    val oldDiameter: Double,
    val newDiameter: Double,
    @VarLong
    val speedMilliseconds: Long,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_size",
)
data class SetBorderSizePacket(
    val diameter: Double,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_warning_delay",
)
data class SetBorderWarningDelayPacket(
    @VarInt
    val warningTimeSeconds: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_warning_distance",
)
data class SetBorderWarningDistancePacket(
    @VarInt
    val warningBlocks: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_camera",
)
data class SetCameraPacket(
    @VarInt
    val cameraEntityId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_chunk_cache_center",
)
data class SetCenterChunkPacket(
    @VarInt
    val chunkX: Int,
    @VarInt
    val chunkZ: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_chunk_cache_radius",
)
data class SetRenderDistancePacket(
    @VarInt
    val viewDistance: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x61,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_default_spawn_position",
)
data class SetDefaultSpawnPositionPacket(
    val respawnData: RespawnData,
) : PlayStatePacket, ClientboundPacket
