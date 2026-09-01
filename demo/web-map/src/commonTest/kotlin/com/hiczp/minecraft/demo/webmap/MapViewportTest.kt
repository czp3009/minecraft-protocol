package com.hiczp.minecraft.demo.webmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MapViewportTest {
    @Test
    fun zoomLevelsMapOneBlockToOneThroughSixteenPixels() {
        assertEquals(listOf(1, 2, 4, 8, 16), (MIN_MAP_ZOOM..MAX_MAP_ZOOM).map(::pixelsPerBlock))
        assertFailsWith<IllegalArgumentException> { pixelsPerBlock(MIN_MAP_ZOOM - 1) }
        assertFailsWith<IllegalArgumentException> { pixelsPerBlock(MAX_MAP_ZOOM + 1) }
    }

    @Test
    fun visibleBoundsUseFloorForNegativeCoordinatesAndInclusiveEdges() {
        val chunkViewport = VisibleBlockBounds(
            minBlockX = -16.01,
            minBlockZ = -0.01,
            maxBlockX = 16.0,
            maxBlockZ = 31.99,
        ).toChunkViewport()

        assertEquals(ChunkViewport(-2, -1, 1, 1), chunkViewport)
    }
}
