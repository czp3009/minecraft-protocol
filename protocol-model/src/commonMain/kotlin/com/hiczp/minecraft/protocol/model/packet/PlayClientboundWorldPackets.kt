package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.Angle
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x00,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "bundle_delimiter",
)
data object BundleDelimiterPacket : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x01,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "add_entity",
)
data class SpawnEntityPacket(
    @VarInt
    val entityId: Int,
    val entityUuid: Uuid,
    @VarInt
    val typeId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    @LowPrecisionVector
    val velocity: Vector3d,
    val pitch: Angle,
    val yaw: Angle,
    val headYaw: Angle,
    @VarInt
    val data: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x02,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "animate",
)
data class EntityAnimationPacket(
    @VarInt
    val entityId: Int,
    @UnsignedByte
    val animationId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x03,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "award_stats",
)
data class AwardStatisticsPacket(
    val statistics: List<StatisticEntry>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x04,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_changed_ack",
)
data class AcknowledgeBlockChangePacket(
    @VarInt
    val sequenceId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x05,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_destruction",
)
data class SetBlockDestroyStagePacket(
    @VarInt
    val entityId: Int,
    val location: BlockPosition,
    @UnsignedByte
    val destroyStage: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x06,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_entity_data",
)
data class BlockEntityDataPacket(
    val location: BlockPosition,
    @VarInt
    val typeId: Int,
    @NetworkNbt
    val data: NbtCompound,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x07,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_event",
)
data class BlockActionPacket(
    val location: BlockPosition,
    @UnsignedByte
    val actionId: Int,
    @UnsignedByte
    val actionParameter: Int,
    @VarInt
    val blockTypeId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x08,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_update",
)
data class BlockUpdatePacket(
    val location: BlockPosition,
    @VarInt
    val blockStateId: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x09,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "boss_event",
)
data class BossBarPacket(
    val uuid: Uuid,
    val action: BossBarAction,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0A,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "change_difficulty",
)
data class ClientboundChangeDifficultyPacket(
    /*
     * The pinned Wiki says Unsigned Byte. The matching vanilla codec uses
     * Difficulty.STREAM_CODEC -> ByteBufCodecs.idMapper -> VarInt.
     */
    @WrappedEnum
    val difficulty: Difficulty,
    val locked: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0B,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "chunk_batch_finished",
)
data class ChunkBatchFinishedPacket(
    @VarInt
    val batchSize: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0C,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "chunk_batch_start",
)
data object ChunkBatchStartPacket : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "chunks_biomes",
)
data class ChunkBiomesPacket(
    val chunks: List<ChunkBiomeData>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "clear_titles",
)
data class ClearTitlesPacket(
    val reset: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "command_suggestions",
)
data class CommandSuggestionsResponsePacket(
    @VarInt
    val id: Int,
    @VarInt
    val start: Int,
    @VarInt
    val length: Int,
    val matches: List<CommandSuggestionMatch>,
) : PlayStatePacket, ClientboundPacket
