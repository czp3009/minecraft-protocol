package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.Angle
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(
    0x42,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_combat_end",
)
data class ClientboundPlayerCombatEndPacket(
    @VarInt
    val duration: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x43,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_combat_enter",
)
data object ClientboundPlayerCombatEnterPacket : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x44,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_combat_kill",
)
data class ClientboundPlayerCombatKillPacket(
    @VarInt
    val playerId: Int,
    val message: TextComponent,
) : PlayStatePacket, SkippableClientboundPacket

@Serializable
@PacketInfo(
    0x45,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_info_remove",
)
data class ClientboundPlayerInfoRemovePacket(
    val profileIds: List<Uuid>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x47,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "player_look_at",
    shapeException = "LookAtTarget is a sealed position-or-entity value that excludes mismatched atEntity, entity and anchor combinations.",
)
data class ClientboundPlayerLookAtPacket(
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
data class ClientboundPlayerPositionPacket(
    @VarInt
    val id: Int,
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
data class ClientboundPlayerRotationPacket(
    val yRot: Float,
    val relativeY: Boolean,
    val xRot: Float,
    val relativeX: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "recipe_book_remove",
)
data class ClientboundRecipeBookRemovePacket(
    @VarIntElements
    val recipes: List<Int>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "recipe_book_settings",
)
data class ClientboundRecipeBookSettingsPacket(
    val bookSettings: RecipeBookSettings,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "remove_entities",
)
data class ClientboundRemoveEntitiesPacket(
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
data class ClientboundRemoveMobEffectPacket(
    @VarInt
    val entityId: Int,
    @VarInt
    val effect: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x4F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "reset_score",
)
data class ClientboundResetScorePacket(
    @MaxLength(32_767)
    val owner: String,
    @MaxLength(32_767)
    val objectiveName: String?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x08, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "resource_pack_pop")
@PacketInfo(
    0x50,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "resource_pack_pop",
)
data class ClientboundResourcePackPopPacket(
    val id: Uuid?,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x09, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND, "resource_pack_push")
@PacketInfo(
    0x51,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "resource_pack_push",
)
data class ClientboundResourcePackPushPacket(
    val id: Uuid,
    @MaxLength(32_767)
    val url: String,
    @MaxLength(40)
    val hash: String,
    val required: Boolean,
    val prompt: TextComponent?,
) : ConfigurationStatePacket, PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x53,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "rotate_head",
)
data class ClientboundRotateHeadPacket(
    @VarInt
    val entityId: Int,
    val yHeadRot: Angle,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x54,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "section_blocks_update",
    shapeException = "Each SectionBlockChange couples a local position and state; one list excludes mismatched lengths in the official parallel arrays.",
)
data class ClientboundSectionBlocksUpdatePacket(
    val sectionPos: SectionPosition,
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
data class ClientboundSelectAdvancementsTabPacket(
    val tab: Identifier?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x56,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "server_data",
)
data class ClientboundServerDataPacket(
    val motd: TextComponent,
    @MaxByteLength(Int.MAX_VALUE)
    val iconBytes: ByteString?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x57,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_action_bar_text",
)
data class ClientboundSetActionBarTextPacket(
    val text: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x58,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_center",
)
data class ClientboundSetBorderCenterPacket(
    val newCenterX: Double,
    val newCenterZ: Double,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x59,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_lerp_size",
)
data class ClientboundSetBorderLerpSizePacket(
    val oldSize: Double,
    val newSize: Double,
    @VarLong
    val lerpTime: Long,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_size",
)
data class ClientboundSetBorderSizePacket(
    val size: Double,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_warning_delay",
)
data class ClientboundSetBorderWarningDelayPacket(
    @VarInt
    val warningDelay: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_border_warning_distance",
)
data class ClientboundSetBorderWarningDistancePacket(
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
data class ClientboundSetCameraPacket(
    @VarInt
    val cameraId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_chunk_cache_center",
)
data class ClientboundSetChunkCacheCenterPacket(
    @VarInt
    val x: Int,
    @VarInt
    val z: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x5F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_chunk_cache_radius",
)
data class ClientboundSetChunkCacheRadiusPacket(
    @VarInt
    val radius: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x61,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_default_spawn_position",
)
data class ClientboundSetDefaultSpawnPositionPacket(
    val respawnData: RespawnData,
) : PlayStatePacket, ClientboundPacket
