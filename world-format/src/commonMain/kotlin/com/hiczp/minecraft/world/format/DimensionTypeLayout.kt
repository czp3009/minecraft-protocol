package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt

class DimensionTypeFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Filesystem- and protocol-independent Chunk bounds and lighting facts from one dimension type. */
data class DimensionTypeLayout(
    val minY: Int,
    val height: Int,
    val logicalHeight: Int,
    val hasSkyLight: Boolean,
    val hasCeiling: Boolean,
) {
    val chunkLayout: ChunkLayout = ChunkLayout.fromBlockBounds(minY, height)

    /** Logical block-height interval used by dimension rules; it may be shorter than [chunkLayout]. */
    val logicalBlockYRange: IntRange = if (logicalHeight == 0) {
        IntRange.EMPTY
    } else {
        minY..MinecraftCoordinates.offsetBlockCoordinate(minY, logicalHeight - 1)
    }

    init {
        require(logicalHeight in 0..height) {
            "Dimension logical height must be between zero and its block height"
        }
    }

    companion object {
        /** Decodes the common layout fields from an official dimension-type NBT value. */
        fun fromNbt(dimensionTypeData: NbtCompound): DimensionTypeLayout = try {
            DimensionTypeLayout(
                minY = dimensionTypeData.requireInt(MIN_Y),
                height = dimensionTypeData.requireInt(HEIGHT),
                logicalHeight = dimensionTypeData.requireInt(LOGICAL_HEIGHT),
                hasSkyLight = dimensionTypeData.requireBoolean(HAS_SKY_LIGHT),
                hasCeiling = dimensionTypeData.requireBoolean(HAS_CEILING),
            )
        } catch (failure: DimensionTypeFormatException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw DimensionTypeFormatException("Invalid dimension-type layout", failure)
        }
    }
}

private fun NbtCompound.requireInt(name: String): Int =
    (this[name] as? NbtInt)?.value
        ?: throw DimensionTypeFormatException("Dimension type field '$name' must be TAG_Int")

private fun NbtCompound.requireBoolean(name: String): Boolean {
    val value = (this[name] as? NbtByte)?.value?.toInt()
        ?: throw DimensionTypeFormatException("Dimension type field '$name' must be a Boolean TAG_Byte")
    return when (value) {
        0 -> false
        1 -> true
        else -> throw DimensionTypeFormatException("Dimension type field '$name' must be zero or one")
    }
}

private const val MIN_Y = "min_y"
private const val HEIGHT = "height"
private const val LOGICAL_HEIGHT = "logical_height"
private const val HAS_SKY_LIGHT = "has_skylight"
private const val HAS_CEILING = "has_ceiling"
