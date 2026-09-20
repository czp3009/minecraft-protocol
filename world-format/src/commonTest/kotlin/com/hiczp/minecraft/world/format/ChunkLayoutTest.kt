package com.hiczp.minecraft.world.format

import kotlin.test.*

class ChunkLayoutTest {
    @Test
    fun createsASectionLayoutFromBlockBounds() {
        val chunkLayout = ChunkLayout.fromBlockBounds(minY = -64, height = 384)

        assertEquals(ChunkLayout(minSectionY = -4, sectionCount = 24), chunkLayout)
        assertEquals(-64..319, chunkLayout.blockYRange)
    }

    @Test
    fun copiedLayoutsRecalculateBoundsAndMembershipAtCoordinateExtremes() {
        val original = ChunkLayout(-4, 24)
        val changed = original.copy(minSectionY = 0, sectionCount = 16)
        assertEquals(-64..319, original.blockYRange)
        assertEquals(0..255, changed.blockYRange)
        assertEquals(0..15, changed.sectionYRange)
        assertEquals(256, changed.height)
        assertFalse(changed.containsBlockY(-1))
        assertTrue(changed.containsBlockY(0))
        assertTrue(changed.containsBlockY(255))
        assertFalse(changed.containsBlockY(256))
        assertFalse(-1 in changed)
        assertTrue(15 in changed)
        assertFalse(16 in changed)
        val lowest = ChunkLayout.fromBlockBounds(Int.MIN_VALUE, 16)
        val highest = ChunkLayout.fromBlockBounds(Int.MAX_VALUE - 15, 16)
        assertEquals(Int.MIN_VALUE, lowest.minBlockY)
        assertEquals(Int.MAX_VALUE, highest.maxBlockY)
        assertTrue(lowest.containsBlockY(Int.MIN_VALUE))
        assertFalse(lowest.containsBlockY(Int.MAX_VALUE))
        assertTrue(highest.containsBlockY(Int.MAX_VALUE))
        assertFalse(highest.containsBlockY(Int.MIN_VALUE))
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
            ChunkLayout.fromBlockBounds(
                minY = Int.MAX_VALUE - (MinecraftCoordinates.SECTION_SIDE - 1),
                height = MinecraftCoordinates.SECTION_SIDE * 2
            )
        }
    }
}
