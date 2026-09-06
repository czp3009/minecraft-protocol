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
data class ClientboundDamageEventPacket(
    @VarInt
    val entityId: Int,
    @VarInt
    val sourceType: Int,
    @OptionalVarInt
    val sourceCauseId: Int?,
    @OptionalVarInt
    val sourceDirectId: Int?,
    val sourcePosition: Vector3d?,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x1A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug/block_value",
)
data class ClientboundDebugBlockValuePacket(
    val blockPos: BlockPosition,
    val update: DebugSubscriptionUpdate,
) : PlayStatePacket, ClientboundPacket

/**
 * Vanilla writes a packed `net.minecraft.world.level.ChunkPos` long. On the
 * wire that is Z first and X second because Z occupies the high 32 bits.
 */
@Serializable
@PacketInfo(
    0x1B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug/chunk_value",
)
data class ClientboundDebugChunkValuePacket(
    val chunkPos: ChunkPos,
    val update: DebugSubscriptionUpdate,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x1C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "debug/entity_value",
)
data class ClientboundDebugEntityValuePacket(
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
data class ClientboundDebugEventPacket(
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
data class ClientboundDebugSamplePacket(
    val sample: List<Long>,
    val debugSampleType: DebugSampleType,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x1F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "delete_chat",
)
data class ClientboundDeleteChatPacket(
    val messageSignature: PackedMessageSignature,
) : PlayStatePacket, ClientboundPacket
