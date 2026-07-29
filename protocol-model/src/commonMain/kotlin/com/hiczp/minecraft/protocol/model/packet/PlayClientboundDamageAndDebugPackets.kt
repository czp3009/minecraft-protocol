package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.OptionalVarInt
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x19,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "damage_event",
)
data class DamageEventPacket(
    @VarInt
    val entityId: Int,
    @VarInt
    val sourceTypeId: Int,
    @OptionalVarInt
    val sourceCauseEntityId: Int?,
    @OptionalVarInt
    val sourceDirectEntityId: Int?,
    val sourcePosition: Vector3d?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x1A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug/block_value",
)
data class DebugBlockValuePacket(
    val location: BlockPosition,
    val update: DebugSubscriptionUpdate,
) : PlayStatePacket, ClientboundPacket

/**
 * Vanilla writes a packed [net.minecraft.world.level.ChunkPos] long. On the
 * wire that is Z first and X second because Z occupies the high 32 bits.
 */
@Serializable
@PacketInfo(
    0x1B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug/chunk_value",
)
data class DebugChunkValuePacket(
    val chunkZ: Int,
    val chunkX: Int,
    val update: DebugSubscriptionUpdate,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x1C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug/entity_value",
)
data class DebugEntityValuePacket(
    @VarInt
    val entityId: Int,
    val update: DebugSubscriptionUpdate,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x1D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug/event",
)
data class DebugEventPacket(
    val event: DebugSubscriptionEvent,
) : PlayStatePacket, ClientboundPacket

@Serializable
enum class DebugSampleType {
    TICK_TIME,
}

@Serializable
@PacketInfo(
    0x1E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug_sample",
)
data class DebugSamplePacket(
    val sample: List<Long>,
    val type: DebugSampleType,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x1F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "delete_chat",
)
data class DeleteMessagePacket(
    val messageSignature: PackedMessageSignature,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x20,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "disconnect",
)
data class PlayDisconnectPacket(
    val reason: TextComponent,
) : PlayStatePacket, ClientboundPacket
