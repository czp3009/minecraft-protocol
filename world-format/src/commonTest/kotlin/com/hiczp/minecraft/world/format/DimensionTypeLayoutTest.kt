package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtString
import kotlin.test.*

class DimensionTypeLayoutTest {
    @Test
    fun decodesTheCommonDimensionLayoutFromNbt() {
        val dimensionTypeLayout = DimensionTypeLayout.fromNbt(
            dimensionTypeData(
                minY = -64,
                height = 384,
                logicalHeight = 384,
                hasSkyLight = true,
                hasCeiling = false,
            ),
        )

        assertEquals(-64, dimensionTypeLayout.minY)
        assertEquals(384, dimensionTypeLayout.height)
        assertEquals(384, dimensionTypeLayout.logicalHeight)
        assertTrue(dimensionTypeLayout.hasSkyLight)
        assertFalse(dimensionTypeLayout.hasCeiling)
        assertEquals(ChunkLayout.fromBlockBounds(-64, 384), dimensionTypeLayout.chunkLayout)
        assertEquals(-64..319, dimensionTypeLayout.logicalBlockYRange)
    }

    @Test
    fun logicalBlockRangeCanBeShorterThanThePhysicalChunkLayout() {
        val dimensionTypeLayout = DimensionTypeLayout(
            minY = 0,
            height = 256,
            logicalHeight = 128,
            hasSkyLight = false,
            hasCeiling = true,
        )

        assertEquals(0..127, dimensionTypeLayout.logicalBlockYRange)
        assertEquals(0..255, dimensionTypeLayout.chunkLayout.blockYRange)
    }

    @Test
    fun rejectsIncompleteWronglyTypedAndInconsistentLayouts() {
        val valid = dimensionTypeData(-64, 384, 384, hasSkyLight = true, hasCeiling = false)

        assertFailsWith<DimensionTypeFormatException> {
            DimensionTypeLayout.fromNbt(NbtCompound(valid.value - "logical_height"))
        }
        assertFailsWith<DimensionTypeFormatException> {
            DimensionTypeLayout.fromNbt(NbtCompound(valid.value + ("height" to NbtString("384"))))
        }
        assertFailsWith<DimensionTypeFormatException> {
            DimensionTypeLayout.fromNbt(dimensionTypeData(-64, 384, 385, true, false))
        }
        assertFailsWith<DimensionTypeFormatException> {
            DimensionTypeLayout.fromNbt(dimensionTypeData(-63, 384, 384, true, false))
        }
        assertFailsWith<DimensionTypeFormatException> {
            DimensionTypeLayout.fromNbt(
                NbtCompound(valid.value + ("has_skylight" to NbtByte(2))),
            )
        }
    }
}

private fun dimensionTypeData(
    minY: Int,
    height: Int,
    logicalHeight: Int,
    hasSkyLight: Boolean,
    hasCeiling: Boolean,
): NbtCompound = NbtCompound(
    mapOf(
        "min_y" to NbtInt(minY),
        "height" to NbtInt(height),
        "logical_height" to NbtInt(logicalHeight),
        "has_skylight" to NbtByte(if (hasSkyLight) 1 else 0),
        "has_ceiling" to NbtByte(if (hasCeiling) 1 else 0),
    ),
)
