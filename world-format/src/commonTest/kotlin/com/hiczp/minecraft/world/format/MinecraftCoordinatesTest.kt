package com.hiczp.minecraft.world.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinecraftCoordinatesTest {
    @Test
    fun floorsContinuousCoordinatesToContainingBlocks() {
        assertEquals(BlockPosition(12, -1, -13), MinecraftCoordinates.block(12.999, -0.001, -12.001))
        assertEquals(Int.MAX_VALUE, MinecraftCoordinates.blockCoordinate(Int.MAX_VALUE.toDouble() + 0.9))
        assertEquals(Int.MIN_VALUE, MinecraftCoordinates.blockCoordinate(Int.MIN_VALUE.toDouble()))

        assertFailsWith<IllegalArgumentException> { MinecraftCoordinates.blockCoordinate(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { MinecraftCoordinates.blockCoordinate(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.blockCoordinate(Int.MIN_VALUE.toDouble() - 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.blockCoordinate(Int.MAX_VALUE.toDouble() + 1.0)
        }
    }

    @Test
    fun convertsScalarCoordinatesWithFloorSemantics() {
        assertEquals(-2, MinecraftCoordinates.chunkCoordinate(-17))
        assertEquals(-1, MinecraftCoordinates.chunkCoordinate(-1))
        assertEquals(0, MinecraftCoordinates.chunkCoordinate(15))
        assertEquals(1, MinecraftCoordinates.chunkCoordinate(16))
        assertEquals(15, MinecraftCoordinates.blockCoordinateInChunk(-1))

        assertEquals(-2, MinecraftCoordinates.regionCoordinate(-33))
        assertEquals(-1, MinecraftCoordinates.regionCoordinate(-1))
        assertEquals(31, MinecraftCoordinates.chunkCoordinateInRegion(-1))

        assertEquals(-1, MinecraftCoordinates.blockCoordinate(-1, 15))
        assertEquals(-1, MinecraftCoordinates.sectionBlockCoordinate(-1, 15))
        assertEquals(-1, MinecraftCoordinates.chunkCoordinate(-1, 31))

        assertEquals(-1, MinecraftCoordinates.quartCoordinate(-1))
        assertEquals(3, MinecraftCoordinates.quartCoordinateInSection(-1))
        assertEquals(3, MinecraftCoordinates.blockCoordinateInQuart(-1))
        assertEquals(-1, MinecraftCoordinates.quartBlockCoordinate(-1, 3))
        assertEquals(-1, MinecraftCoordinates.quartCoordinate(-1, 3))
        assertEquals(-3, MinecraftCoordinates.offsetSectionCoordinate(-1, -2))
    }

    @Test
    fun typedConversionsRoundTripAcrossEveryBoundary() {
        val blockPosition = BlockPosition(-513, -17, 528)
        val chunkPosition = ChunkPosition(-33, 33)
        val sectionPosition = SectionPosition(-33, -2, 33)
        val regionPosition = RegionPosition(-2, 1)

        assertEquals(chunkPosition, MinecraftCoordinates.chunk(blockPosition))
        assertEquals(sectionPosition, MinecraftCoordinates.section(blockPosition))
        assertEquals(regionPosition, MinecraftCoordinates.region(blockPosition))
        assertEquals(regionPosition, MinecraftCoordinates.region(chunkPosition))

        val chunkBlockPosition = MinecraftCoordinates.local(blockPosition, chunkPosition)
        val localBlockPosition = MinecraftCoordinates.local(blockPosition, sectionPosition)
        val localChunkPosition = MinecraftCoordinates.local(chunkPosition, regionPosition)
        assertEquals(blockPosition, MinecraftCoordinates.block(chunkPosition, chunkBlockPosition))
        assertEquals(blockPosition, MinecraftCoordinates.block(sectionPosition, localBlockPosition))
        assertEquals(chunkPosition, MinecraftCoordinates.chunk(regionPosition, localChunkPosition))

        assertEquals(chunkPosition, blockPosition.chunkPosition)
        assertEquals(sectionPosition, blockPosition.sectionPosition)
        assertEquals(regionPosition, blockPosition.regionPosition)
        assertEquals(regionPosition, chunkPosition.regionPosition)
        assertEquals(chunkBlockPosition, blockPosition.localInChunk)
        assertEquals(localBlockPosition, blockPosition.localInSection)
        assertEquals(localChunkPosition, chunkPosition.localChunkPosition)
        assertEquals(chunkBlockPosition, chunkPosition.local(blockPosition))
        assertEquals(localBlockPosition, sectionPosition.local(blockPosition))
        assertEquals(localChunkPosition, regionPosition.local(chunkPosition))
        assertEquals(chunkPosition, regionPosition.chunk(localChunkPosition))
        assertEquals(blockPosition, chunkPosition.block(chunkBlockPosition))
        assertEquals(blockPosition, sectionPosition.block(localBlockPosition))
        assertEquals(sectionPosition, chunkPosition.section(chunkBlockPosition))
        assertTrue(chunkPosition in regionPosition)
        assertTrue(sectionPosition in chunkPosition)
        assertTrue(blockPosition in sectionPosition)
    }

    @Test
    fun reportsForwardCoverageRanges() {
        val regionPosition = RegionPosition(-2, 1)
        assertEquals(-64..-33, regionPosition.chunkXRange)
        assertEquals(32..63, regionPosition.chunkZRange)
        assertEquals(-1024..-513, regionPosition.blockXRange)
        assertEquals(512..1023, regionPosition.blockZRange)

        val chunkPosition = ChunkPosition(-2, 3)
        assertEquals(-32..-17, chunkPosition.blockXRange)
        assertEquals(48..63, chunkPosition.blockZRange)

        val sectionPosition = SectionPosition(-2, -4, 3)
        assertEquals(-32..-17, sectionPosition.blockXRange)
        assertEquals(-64..-49, sectionPosition.blockYRange)
        assertEquals(48..63, sectionPosition.blockZRange)

        val chunkLayout = ChunkLayout(minSectionY = -4, sectionCount = 8)
        assertEquals(-64..63, chunkLayout.blockYRange)
    }

    @Test
    fun enumeratesRegionChunksAndSectionBlocksWithoutMaterializingThem() {
        val regionPosition = RegionPosition(-2, 1)
        val chunkPositions = regionPosition.chunkPositions()
        val chunkPositionList = chunkPositions.toList()

        assertEquals(REGION_CHUNK_COUNT, chunkPositionList.size)
        assertEquals(ChunkPosition(-64, 32), chunkPositionList.first())
        assertEquals(ChunkPosition(-33, 63), chunkPositionList.last())
        assertEquals(
            MinecraftCoordinates.localChunkPositions().toList(),
            regionPosition.localChunkPositions().toList(),
        )
        chunkPositionList.forEachIndexed { index, chunkPosition ->
            assertEquals(LocalChunkPosition.fromIndex(index), regionPosition.local(chunkPosition))
        }

        val sectionPosition = SectionPosition(-1, 2, 3)
        val blockPositions = sectionPosition.blockPositions().toList()
        assertEquals(SECTION_BLOCK_COUNT, blockPositions.size)
        assertEquals(BlockPosition(-16, 32, 48), blockPositions.first())
        assertEquals(BlockPosition(-1, 47, 63), blockPositions.last())
        blockPositions.forEachIndexed { index, blockPosition ->
            assertEquals(index, sectionPosition.local(blockPosition).index)
        }
    }

    @Test
    fun offsetsCoordinatesAndEnumeratesCenteredChunkSquares() {
        val center = ChunkPosition(-1, 2)
        val positions = center.positionsAround(1).toList()

        assertEquals(9, positions.size)
        assertEquals(ChunkPosition(-2, 1), positions.first())
        assertEquals(center, positions[4])
        assertEquals(ChunkPosition(0, 3), positions.last())
        assertEquals(RegionPosition(-3, 5), RegionPosition(-2, 3).offset(-1, 2))
        assertEquals(SectionPosition(1, -3, 5), SectionPosition(0, -1, 2).offset(1, -2, 3))
        assertEquals(BlockPosition(-2, 1, 4), BlockPosition(-1, -1, 1).offset(-1, 2, 3))

        assertFailsWith<IllegalArgumentException> { center.positionsAround(-1) }
        assertFailsWith<IllegalArgumentException> { ChunkPosition(Int.MAX_VALUE, 0).positionsAround(1) }
        assertFailsWith<IllegalArgumentException> { RegionPosition(Int.MIN_VALUE, 0).offset(-1, 0) }
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.offsetChunkCoordinate(Int.MAX_VALUE, 1)
        }
    }

    @Test
    fun rejectsWrongOwnersAndOverflowingReverseConversions() {
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.local(BlockPosition(16, 0, 0), ChunkPosition(0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.local(ChunkPosition(32, 0), RegionPosition(0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.chunk(RegionPosition(Int.MAX_VALUE, 0), LocalChunkPosition(0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.block(ChunkPosition(Int.MIN_VALUE, 0), ChunkBlockPosition(0, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftCoordinates.chunkXRange(RegionPosition(Int.MAX_VALUE, 0))
        }

        assertTrue(BlockPosition(-1, 0, -1) in ChunkPosition(-1, -1))
    }
}
