package com.hiczp.minecraft.world.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkLayoutTest {
    @Test
    fun createsASectionLayoutFromBlockBounds() {
        val chunkLayout = ChunkLayout.fromBlockBounds(minY = -64, height = 384)

        assertEquals(ChunkLayout(minSectionY = -4, sectionCount = 24), chunkLayout)
        assertEquals(-64..319, chunkLayout.blockYRange)
    }

    @Test
    fun rejectsBoundsThatCannotRepresentWholeSections() {
        assertFailsWith<IllegalArgumentException> {
            ChunkLayout.fromBlockBounds(minY = -63, height = 384)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkLayout.fromBlockBounds(minY = -64, height = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkLayout.fromBlockBounds(minY = -64, height = -16)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkLayout.fromBlockBounds(minY = -64, height = 383)
        }
    }

    @Test
    fun rejectsBoundsWhoseMaximumBlockCoordinateOverflows() {
        assertFailsWith<IllegalArgumentException> {
            ChunkLayout.fromBlockBounds(minY = Int.MAX_VALUE - (SECTION_SIDE - 1), height = SECTION_SIDE * 2)
        }
    }
}
