package com.hiczp.minecraft.protocol.model.packet

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
data class DisplayObjectivePacket(
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
data class LinkEntitiesPacket(
    val attachedEntityId: Int,
    val holdingEntityId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x65,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_entity_motion",
)
data class SetEntityVelocityPacket(
    @VarInt
    val entityId: Int,
    @LowPrecisionVector
    val velocity: Vector3d,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x67,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_experience",
)
data class SetExperiencePacket(
    val experienceBar: Float,
    @VarInt
    val level: Int,
    @VarInt
    val totalExperience: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x68,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_health",
)
data class SetHealthPacket(
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
data class ClientboundSetHeldItemPacket(
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
data class SetPassengersPacket(
    @VarInt
    val vehicleEntityId: Int,
    @VarIntElements
    val passengerEntityIds: List<Int>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x6F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_simulation_distance",
)
data class SetSimulationDistancePacket(
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
data class SetSubtitleTextPacket(
    val text: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x71,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_time",
)
data class UpdateTimePacket(
    val gameTime: Long,
    @VarIntElements
    val clocks: Map<Int, ClockNetworkState>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x72,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_title_text",
)
data class SetTitleTextPacket(
    val text: TextComponent,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x73,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "set_titles_animation",
)
data class SetTitleAnimationTimesPacket(
    val fadeInTicks: Int,
    val stayTicks: Int,
    val fadeOutTicks: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x76,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "start_configuration",
)
data object StartConfigurationPacket :
    PlayStatePacket,
    ClientboundPacket

@Serializable
@PacketInfo(
    0x77,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "stop_sound",
)
data class StopSoundPacket(
    val value: StopSound,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x78,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "store_cookie",
)
data class PlayStoreCookiePacket(
    val key: Identifier,
    @MaxByteLength(5_120)
    val payload: ByteString,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x79,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "system_chat",
)
data class SystemChatMessagePacket(
    val content: TextComponent,
    val overlay: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x7A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "tab_list",
)
data class SetTabListHeaderAndFooterPacket(
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
data class TagQueryResponsePacket(
    @VarInt
    val transactionId: Int,
    @NbtEndOptional
    @NetworkNbt
    val data: NbtCompound?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x7C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "take_item_entity",
)
data class PickupItemPacket(
    @VarInt
    val collectedEntityId: Int,
    @VarInt
    val collectorEntityId: Int,
    @VarInt
    val itemCount: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x7D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "teleport_entity",
)
data class SynchronizeVehiclePositionPacket(
    @VarInt
    val entityId: Int,
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
data class TestInstanceBlockStatusPacket(
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
data class SetTickingStatePacket(
    val tickRate: Float,
    val frozen: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x80,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "ticking_step",
)
data class StepTickPacket(
    @VarInt
    val tickSteps: Int,
) : PlayStatePacket, ClientboundPacket
