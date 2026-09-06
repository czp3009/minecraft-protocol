package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBiomeIds
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.world.format.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurfaceProjectionPolicyTest {

    @Test
    fun everyDimensionChoosesTheFirstNonAirBlockAfterAir() {
        listOf(
            DimensionTypeLayout(0, 32, 32, hasSkyLight = true, hasCeiling = false),
            DimensionTypeLayout(0, 32, 16, hasSkyLight = false, hasCeiling = true),
            DimensionTypeLayout(0, 32, 32, hasSkyLight = false, hasCeiling = false),
        ).forEach { dimensionTypeLayout ->
            val chunk = emptyChunk(dimensionTypeLayout.chunkLayout)
            val maximumLogicalBlockY = dimensionTypeLayout.logicalBlockYRange.last
            chunk.setBlockState(ChunkBlockPosition(3, maximumLogicalBlockY, 4), STONE)
            chunk.setBlockState(ChunkBlockPosition(3, maximumLogicalBlockY - 2, 4), OAK_LOG)
            chunk.setBlockState(ChunkBlockPosition(3, dimensionTypeLayout.logicalBlockYRange.first, 4), DIRT)

            val surface = project(chunk, blockYRange = dimensionTypeLayout.logicalBlockYRange)

            assertEquals(column(OAK_LOG), surface[3, 4])
            assertEquals(surface.palette.indexOf(surface[3, 4]), surface.cells[4 * MinecraftCoordinates.CHUNK_SIDE + 3])
        }
    }

    @Test
    fun aShorterLogicalHeightStartsInsideThePhysicalCeiling() {
        val dimensionTypeLayout = DimensionTypeLayout(0, 32, 16, hasSkyLight = false, hasCeiling = true)
        val chunk = emptyChunk(dimensionTypeLayout.chunkLayout)
        chunk.setBlockState(ChunkBlockPosition(4, 15, 7), BEDROCK)
        chunk.setBlockState(ChunkBlockPosition(4, 14, 7), BEDROCK)
        chunk.setBlockState(ChunkBlockPosition(4, 12, 7), NETHERRACK)

        val surface = project(chunk, blockYRange = dimensionTypeLayout.logicalBlockYRange)

        assertEquals(column(NETHERRACK), surface[4, 7])
    }

    @Test
    fun aColumnWithoutAirFallsBackToItsFirstNonAirBlockFromTheSamePass() {
        val dimensionTypeLayout = DimensionTypeLayout(0, 16, 16, hasSkyLight = false, hasCeiling = true)
        val chunk = emptyChunk(dimensionTypeLayout.chunkLayout)
        for (blockY in dimensionTypeLayout.chunkLayout.blockYRange) chunk.setBlockState(
            ChunkBlockPosition(
                2,
                blockY,
                1
            ), NETHERRACK
        )
        chunk.setBlockState(ChunkBlockPosition(2, dimensionTypeLayout.chunkLayout.blockYRange.last, 1), STONE)

        assertEquals(column(STONE), project(chunk)[2, 1])
    }

    @Test
    fun transparentBlocksRetainEveryVisibleLayerThroughTheFirstOpaqueBlock() {
        val chunk = emptyChunk(ChunkLayout(0, 2))
        chunk.setBlockState(ChunkBlockPosition(5, 30, 6), OAK_LEAVES)
        chunk.setBlockState(ChunkBlockPosition(5, 28, 6), SHORT_GRASS)
        chunk.setBlockState(ChunkBlockPosition(5, 27, 6), DIRT)
        chunk.setBlockState(ChunkBlockPosition(5, 26, 6), STONE)

        val surface = project(chunk, transparent = setOf(OAK_LEAVES, SHORT_GRASS))

        assertEquals(column(OAK_LEAVES, SHORT_GRASS, DIRT), surface[5, 6])
    }

    @Test
    fun anUnclosedTransparentStackIsStillReturned() {
        val chunk = emptyChunk(ChunkLayout(0, 1))
        chunk.setBlockState(ChunkBlockPosition(0, 14, 0), OAK_LEAVES)
        chunk.setBlockState(ChunkBlockPosition(0, 3, 0), SHORT_GRASS)

        assertEquals(
            column(OAK_LEAVES, SHORT_GRASS),
            project(chunk, transparent = setOf(OAK_LEAVES, SHORT_GRASS))[0, 0],
        )
    }

    @Test
    fun allThreeAirStatesProduceEmptyColumns() {
        listOf(AIR, CAVE_AIR, VOID_AIR).forEachIndexed { localX, air ->
            val chunk = emptyChunk(ChunkLayout(0, 1))
            chunk.setBlockState(ChunkBlockPosition(localX, 15, 0), air)

            assertNull(project(chunk)[localX, 0])
        }
    }

    private fun project(
        chunk: Chunk,
        transparent: Set<BlockState> = emptySet(),
        blockYRange: IntRange = chunk.chunkContext.dimensionTypeLayout.chunkLayout.blockYRange,
    ): ChunkSurface = SurfaceProjectionPolicy.project(
        chunk = chunk,
        blockYRange = blockYRange,
        surfaceBlockTransparency = { surfaceBlockState ->
            transparent.any { blockStateDescriptor ->
                surfaceBlockState == blockStateDescriptor.toSurfaceBlockState()
            }
        },
    )

    private fun emptyChunk(chunkLayout: ChunkLayout): Chunk = Chunk(
        ChunkPosition(0, 0),
        ChunkContext(
            DimensionId.Overworld,
            DimensionTypeLayout(chunkLayout.minBlockY, chunkLayout.height, chunkLayout.height, true, false),
            AIR, BiomeId(MinecraftBiomeIds.PLAINS.value),
        ),
    )

    private fun column(vararg blockStateDescriptors: BlockState): SurfaceColumn =
        SurfaceColumn(blockStateDescriptors.map { blockStateDescriptor -> blockStateDescriptor.toSurfaceBlockState() })

    private fun BlockState.toSurfaceBlockState(): SurfaceBlockState =
        SurfaceBlockState(Identifier(blockId.value), properties.toMap())

    companion object {
        private val AIR = BlockState(BlockId(MinecraftBlockIds.AIR.value))
        private val CAVE_AIR = BlockState(BlockId(Identifier("cave_air").value))
        private val VOID_AIR = BlockState(BlockId(Identifier("void_air").value))
        private val STONE = BlockState(BlockId(Identifier("stone").value))
        private val DIRT = BlockState(BlockId(Identifier("dirt").value))
        private val BEDROCK = BlockState(BlockId(Identifier("bedrock").value))
        private val NETHERRACK = BlockState(BlockId(Identifier("netherrack").value))
        private val OAK_LEAVES =
            BlockState(BlockId(Identifier("oak_leaves").value), StateProperties(mapOf("persistent" to "true")))
        private val SHORT_GRASS = BlockState(BlockId(Identifier("short_grass").value))
        private val OAK_LOG = BlockState(BlockId(Identifier("oak_log").value), StateProperties(mapOf("axis" to "y")))
    }
}
