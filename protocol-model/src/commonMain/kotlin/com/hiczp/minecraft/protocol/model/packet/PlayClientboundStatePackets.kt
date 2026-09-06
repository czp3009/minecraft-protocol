package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x62,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_display_objective",
)
data class ClientboundSetDisplayObjectivePacket(
    @ZeroFallbackEnum
    val slot: DisplaySlot,
    @MaxLength(32_767)
    val objectiveName: String,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x64,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_entity_link",
)
data class ClientboundSetEntityLinkPacket(
    val sourceId: Int,
    val destId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x65,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_entity_motion",
)
data class ClientboundSetEntityMotionPacket(
    @VarInt
    val id: Int,
    @LowPrecisionVector
    val movement: Vector3d,
) : PlayStatePacket, ClientboundPacket

@Serializable(with = ClientboundSetExperiencePacketSerializer::class)
@PacketInfo(
    0x67,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_experience",
)
data class ClientboundSetExperiencePacket(
    val experienceProgress: Float,
    @VarInt
    val totalExperience: Int,
    @VarInt
    val experienceLevel: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x68,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_health",
)
data class ClientboundSetHealthPacket(
    val health: Float,
    @VarInt
    val food: Int,
    val saturation: Float,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x69,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_held_slot",
)
data class ClientboundSetHeldSlotPacket(
    @VarInt
    val slot: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_passengers",
)
data class ClientboundSetPassengersPacket(
    @VarInt
    val vehicle: Int,
    @VarIntElements
    val passengers: List<Int>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_simulation_distance",
)
data class ClientboundSetSimulationDistancePacket(
    @VarInt
    val simulationDistance: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x70,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_subtitle_text",
)
data class ClientboundSetSubtitleTextPacket(
    val text: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x71,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_time",
)
data class ClientboundSetTimePacket(
    val gameTime: Long,
    @VarIntElements
    val clockUpdates: Map<Int, ClockNetworkState>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x72,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_title_text",
)
data class ClientboundSetTitleTextPacket(
    val text: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x73,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_titles_animation",
)
data class ClientboundSetTitlesAnimationPacket(
    val fadeIn: Int,
    val stay: Int,
    val fadeOut: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x76,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "start_configuration",
)
data object ClientboundStartConfigurationPacket :
    PlayStatePacket,
    ClientboundPacket

@Serializable
@PacketInfo(
    0x77,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "stop_sound",
    shapeException = "StopSound is the logical sum of all, source, sound, and source-with-sound requests, with one serializer for their presence flags.",
)
data class ClientboundStopSoundPacket(
    val value: StopSound,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x79,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "system_chat",
)
data class ClientboundSystemChatPacket(
    val content: TextComponent,
    val overlay: Boolean,
) : PlayStatePacket, SkippableClientboundPacket

@Serializable
@PacketInfo(
    0x7A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "tab_list",
)
data class ClientboundTabListPacket(
    val header: TextComponent,
    val footer: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x7B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "tag_query",
)
data class ClientboundTagQueryPacket(
    @VarInt
    val transactionId: Int,
    @NbtEndOptional
    @NetworkNbt
    val tag: NbtCompound?,
) : PlayStatePacket, SkippableClientboundPacket

@Serializable
@PacketInfo(
    0x7C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "take_item_entity",
)
data class ClientboundTakeItemEntityPacket(
    @VarInt
    val itemId: Int,
    @VarInt
    val playerId: Int,
    @VarInt
    val amount: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x7D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "teleport_entity",
)
data class ClientboundTeleportEntityPacket(
    @VarInt
    val id: Int,
    val change: PositionMoveRotation,
    val relatives: RelativeMovements,
    val onGround: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x7E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "test_instance_block_status",
)
data class ClientboundTestInstanceBlockStatus(
    val status: TextComponent,
    val size: Vector3i?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x7F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "ticking_state",
)
data class ClientboundTickingStatePacket(
    val tickRate: Float,
    val isFrozen: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x80,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "ticking_step",
)
data class ClientboundTickingStepPacket(
    @VarInt
    val tickSteps: Int,
) : PlayStatePacket, ClientboundPacket
