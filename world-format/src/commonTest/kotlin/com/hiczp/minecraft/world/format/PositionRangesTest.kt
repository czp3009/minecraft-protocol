package com.hiczp.minecraft.world.format

import kotlin.test.*

class PositionRangesTest {
    @Test
    fun chunkRangesUseInclusiveCornersAndZMajorIteration() {
        val chunkRange = ChunkPosition(-1, 2)..ChunkPosition(1, 3)

        assertEquals(-1..1, chunkRange.xRange)
        assertEquals(2..3, chunkRange.zRange)
        assertEquals(
            listOf(
                ChunkPosition(-1, 2),
                ChunkPosition(0, 2),
                ChunkPosition(1, 2),
                ChunkPosition(-1, 3),
                ChunkPosition(0, 3),
                ChunkPosition(1, 3),
            ),
            chunkRange.toList(),
        )
        assertTrue(ChunkPosition(0, 3) in chunkRange)
        assertFalse(ChunkPosition(2, 3) in chunkRange)
        assertTrue(ChunkPosition(0, 2)..ChunkPosition(1, 3) in chunkRange)
        assertFalse(ChunkPosition(-2, 2)..ChunkPosition(0, 3) in chunkRange)
    }

    @Test
    fun regionRangesUseInclusiveCornersAndZMajorIteration() {
        val regionRange = RegionPosition(-1, 2)..RegionPosition(0, 3)

        assertEquals(-1..0, regionRange.xRange)
        assertEquals(2..3, regionRange.zRange)
        assertEquals(
            listOf(
                RegionPosition(-1, 2),
                RegionPosition(0, 2),
                RegionPosition(-1, 3),
                RegionPosition(0, 3),
            ),
            regionRange.regionPositions().toList(),
        )
        assertTrue(RegionPosition(0, 3) in regionRange)
        assertFalse(RegionPosition(1, 3) in regionRange)
    }

    @Test
    fun halfOpenAndEnclosingFactoriesHaveExplicitEndpointSemantics() {
        val chunkRange = ChunkPosition(1, 1)..<ChunkPosition(5, 5)
        val regionRange = RegionPosition(-2, 3)..<RegionPosition(1, 5)

        assertEquals(1..4, chunkRange.xRange)
        assertEquals(1..4, chunkRange.zRange)
        assertEquals(-2..0, regionRange.xRange)
        assertEquals(3..4, regionRange.zRange)
        assertTrue((ChunkPosition(1, 1)..<ChunkPosition(1, 5)).isEmpty())
        assertTrue((RegionPosition(1, 1)..<RegionPosition(5, 1)).isEmpty())
        assertEquals(
            ChunkPosition(-3, 2)..ChunkPosition(4, 7),
            ChunkRange.enclosing(ChunkPosition(4, 2), ChunkPosition(-3, 7)),
        )
        assertEquals(
            RegionPosition(-3, 2)..RegionPosition(4, 7),
            RegionRange.enclosing(RegionPosition(4, 2), RegionPosition(-3, 7)),
        )
    }

    @Test
    fun reversedAndPartiallyEmptyAxesProduceOrRejectCanonicalEmptyRanges() {
        assertEquals(ChunkRange.EMPTY, ChunkPosition(2, 1)..ChunkPosition(1, 2))
        assertEquals(RegionRange.EMPTY, RegionPosition(1, 2)..RegionPosition(2, 1))
        assertTrue(ChunkRange.EMPTY.isEmpty())
        assertTrue(RegionRange.EMPTY.isEmpty())
        assertTrue(ChunkRange.EMPTY in ChunkPosition(-1, -1)..ChunkPosition(1, 1))
        assertTrue(RegionRange.EMPTY in RegionPosition(-1, -1)..RegionPosition(1, 1))
        assertFailsWith<IllegalArgumentException> { ChunkRange(IntRange.EMPTY, 0..1) }
        assertFailsWith<IllegalArgumentException> { RegionRange(0..1, IntRange.EMPTY) }
    }

    @Test
    fun intersectionsRetainOnlySharedRectangles() {
        val firstChunkRange = ChunkPosition(-2, -1)..ChunkPosition(2, 3)
        val secondChunkRange = ChunkPosition(1, -3)..ChunkPosition(4, 1)
        val firstRegionRange = RegionPosition(-2, -1)..RegionPosition(2, 3)
        val secondRegionRange = RegionPosition(1, -3)..RegionPosition(4, 1)

        assertTrue(firstChunkRange intersects secondChunkRange)
        assertEquals(ChunkPosition(1, -1)..ChunkPosition(2, 1), firstChunkRange intersect secondChunkRange)
        assertEquals(ChunkRange.EMPTY, firstChunkRange intersect (ChunkPosition(3, 4)..ChunkPosition(5, 6)))
        assertTrue(firstRegionRange intersects secondRegionRange)
        assertEquals(RegionPosition(1, -1)..RegionPosition(2, 1), firstRegionRange intersect secondRegionRange)
        assertEquals(RegionRange.EMPTY, firstRegionRange intersect (RegionPosition(3, 4)..RegionPosition(5, 6)))
    }

    @Test
    fun chunkAndRegionRangesConvertInBothDirectionsWithNegativeFloorSemantics() {
        val chunkRange = ChunkPosition(-33, -65)..ChunkPosition(32, 31)
        val coveringRegionRange = RegionPosition(-2, -3)..RegionPosition(1, 0)
        val expandedChunkRange = ChunkPosition(-64, -96)..ChunkPosition(63, 31)

        assertEquals(coveringRegionRange, chunkRange.coveringRegionRange)
        assertEquals(coveringRegionRange.regionPositions().toList(), chunkRange.regionPositions().toList())
        assertEquals(expandedChunkRange, coveringRegionRange.chunkRange)
        assertEquals(expandedChunkRange.chunkPositions().toList(), coveringRegionRange.chunkPositions().toList())
        assertTrue(chunkRange in coveringRegionRange.chunkRange)
        assertEquals(coveringRegionRange, coveringRegionRange.chunkRange.coveringRegionRange)
        assertEquals(
            ChunkPosition(-32, 64)..ChunkPosition(-1, 95),
            RegionPosition(-1, 2).chunkRange,
        )
        assertEquals(RegionRange.EMPTY, ChunkRange.EMPTY.coveringRegionRange)
        assertEquals(ChunkRange.EMPTY, RegionRange.EMPTY.chunkRange)
    }

    @Test
    fun maximumCoordinatesIterateWithoutWrappingAndUnrepresentableExpansionsFail() {
        val maximumChunkRange =
            ChunkPosition(Int.MAX_VALUE - 1, Int.MAX_VALUE)..ChunkPosition(Int.MAX_VALUE, Int.MAX_VALUE)
        val maximumRegionRange =
            RegionPosition(Int.MAX_VALUE - 1, Int.MAX_VALUE)..RegionPosition(Int.MAX_VALUE, Int.MAX_VALUE)

        assertEquals(
            listOf(
                ChunkPosition(Int.MAX_VALUE - 1, Int.MAX_VALUE),
                ChunkPosition(Int.MAX_VALUE, Int.MAX_VALUE),
            ),
            maximumChunkRange.chunkPositions().toList(),
        )
        assertEquals(
            listOf(
                RegionPosition(Int.MAX_VALUE - 1, Int.MAX_VALUE),
                RegionPosition(Int.MAX_VALUE, Int.MAX_VALUE),
            ),
            maximumRegionRange.toList(),
        )
        assertFailsWith<IllegalArgumentException> { RegionPosition(Int.MAX_VALUE, 0).chunkRange }
        assertFailsWith<IllegalArgumentException> {
            (RegionPosition(Int.MIN_VALUE, 0)..RegionPosition(Int.MIN_VALUE, 0)).chunkRange
        }
    }
}
