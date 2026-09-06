package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.Angle
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(
    0x00,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "bundle_delimiter",
)
data object ClientboundBundleDelimiterPacket : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x01,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "add_entity",
)
data class ClientboundAddEntityPacket(
    @VarInt
    val id: Int,
    val uuid: Uuid,
    @VarInt
    val type: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    @LowPrecisionVector
    val movement: Vector3d,
    val xRot: Angle,
    val yRot: Angle,
    val yHeadRot: Angle,
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
data class ClientboundAnimatePacket(
    @VarInt
    val id: Int,
    @UnsignedByte
    val action: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x03,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "award_stats",
)
data class ClientboundAwardStatsPacket(
    val stats: List<StatisticEntry>,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x04,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_changed_ack",
)
data class ClientboundBlockChangedAckPacket(
    @VarInt
    val sequence: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x05,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_destruction",
)
data class ClientboundBlockDestructionPacket(
    @VarInt
    val id: Int,
    val pos: BlockPosition,
    @UnsignedByte
    val progress: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x06,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_entity_data",
)
data class ClientboundBlockEntityDataPacket(
    val pos: BlockPosition,
    @VarInt
    val type: Int,
    @NetworkNbt
    val tag: NbtCompound,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x07,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_event",
)
data class ClientboundBlockEventPacket(
    val pos: BlockPosition,
    @UnsignedByte
    val b0: Int,
    @UnsignedByte
    val b1: Int,
    @VarInt
    val block: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x08,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "block_update",
)
data class ClientboundBlockUpdatePacket(
    val pos: BlockPosition,
    @VarInt
    val blockState: Int,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x09,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "boss_event",
)
data class ClientboundBossEventPacket(
    val id: Uuid,
    val operation: BossBarAction,
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
data class ClientboundChunkBatchFinishedPacket(
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
data object ClientboundChunkBatchStartPacket : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0D,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "chunks_biomes",
)
data class ClientboundChunksBiomesPacket(
    val chunkBiomeData: List<ChunkBiomeData>,
) : PlayStatePacket, ClientboundPacket {
    @Serializable
    data class ChunkBiomeData(
        val pos: ChunkPos,
        @MaxByteLength(2_097_152)
        val buffer: ByteString,
    )
}

@Serializable
@PacketInfo(
    0x0E,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "clear_titles",
)
data class ClientboundClearTitlesPacket(
    val resetTimes: Boolean,
) : PlayStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x0F,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "command_suggestions",
)
data class ClientboundCommandSuggestionsPacket(
    @VarInt
    val id: Int,
    @VarInt
    val start: Int,
    @VarInt
    val length: Int,
    val suggestions: List<Entry>,
) : PlayStatePacket, ClientboundPacket {
    @Serializable
    data class Entry(
        @MaxLength(32_767)
        val text: String,
        val tooltip: TextComponent?,
    )
}
