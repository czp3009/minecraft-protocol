package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.HeightmapType
import com.hiczp.minecraft.protocol.model.wire.MaxByteLength
import com.hiczp.minecraft.protocol.model.wire.NbtEndOptional
import com.hiczp.minecraft.protocol.model.wire.NetworkNbt
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

/** Official packet data. [buffer] contains contiguous Section bytes without the enclosing VarInt byte length. */
@Serializable
data class ClientboundLevelChunkPacketData(
    val heightmaps: Map<HeightmapType, LongArray>,
    @MaxByteLength(TWO_MEGABYTES)
    val buffer: ByteString,
    val blockEntitiesData: List<BlockEntityInfo>,
) {
    override fun equals(other: Any?): Boolean =
        other is ClientboundLevelChunkPacketData && heightmaps.size == other.heightmaps.size &&
                heightmaps.all { (heightmapType, values) -> other.heightmaps[heightmapType]?.contentEquals(values) == true } &&
                buffer == other.buffer && blockEntitiesData == other.blockEntitiesData

    override fun hashCode(): Int {
        val heightmapsHash = heightmaps.entries.sumOf { (heightmapType, values) ->
            heightmapType.hashCode() xor values.contentHashCode()
        }
        return 31 * (31 * heightmapsHash + buffer.hashCode()) + blockEntitiesData.hashCode()
    }

    /** Byte/Short retain the official wire widths; [type] carries the registry ID instead of a runtime BlockEntityType. */
    @Serializable
    data class BlockEntityInfo(
        val packedXZ: Byte,
        val y: Short,
        @VarInt
        val type: Int,
        @NbtEndOptional
        @NetworkNbt
        val tag: NbtCompound?,
    ) {
        init {
            require(type >= 0) { "A block-entity type ID must be non-negative" }
        }

        val localX: Int get() = packedXZ.toInt().ushr(4) and 15
        val localZ: Int get() = packedXZ.toInt() and 15

        companion object {
            fun fromLocalCoordinates(localX: Int, y: Int, localZ: Int, type: Int, tag: NbtCompound?): BlockEntityInfo {
                require(localX in 0..15 && localZ in 0..15) { "Local block-entity coordinates must be in 0..15" }
                require(y in Short.MIN_VALUE..Short.MAX_VALUE) { "Block-entity Y $y does not fit a Short" }
                return BlockEntityInfo(((localX shl 4) or localZ).toByte(), y.toShort(), type, tag)
            }
        }
    }

    companion object {
        const val TWO_MEGABYTES: Int = 2_097_152
    }
}
