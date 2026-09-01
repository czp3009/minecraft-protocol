package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBiomeIds
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.world.format.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurfaceProjectionPolicyTest {
    private val blockStateRegistry = DescriptorBlockStateRegistry()
    private val chunkDataRegistries = ChunkDataRegistries(blockStateRegistry, NamedBiomeRegistry())

    @Test
    fun ordinaryDimensionsChooseHighestNonAirAcrossPhysicalLayout() {
        val dimensionTypeLayout = DimensionTypeLayout(
            minY = 0,
            height = 32,
            logicalHeight = 16,
            hasSkyLight = true,
            hasCeiling = false,
        )
        val chunk = emptyChunk(dimensionTypeLayout.chunkLayout)
        chunk.setBlock(3, 10, 4, STONE)
        chunk.setBlock(3, 31, 4, OAK_LOG)

        val surface = project(DimensionId.Overworld, chunk, dimensionTypeLayout)

        assertEquals(SurfaceBlockState(Identifier("oak_log"), mapOf("axis" to "y")), surface[3, 4])
        assertNull(surface[4, 4])
        assertEquals(surface.palette.indexOf(surface[3, 4]), surface.cells[4 * CHUNK_SIDE + 3])
    }

    @Test
    fun netherStartsAtLogicalTopAndChoosesTheFirstBlockOutsideAirBedrockAndNetherrack() {
        val dimensionTypeLayout = DimensionTypeLayout(
            minY = 0,
            height = 32,
            logicalHeight = 16,
            hasSkyLight = false,
            hasCeiling = true,
        )
        val chunk = emptyChunk(dimensionTypeLayout.chunkLayout)
        chunk.setBlock(0, 31, 0, STONE)
        chunk.setBlock(0, 15, 0, BEDROCK)
        chunk.setBlock(0, 14, 0, NETHERRACK)
        chunk.setBlock(0, 13, 0, OAK_LOG)
        chunk.setBlock(0, 11, 0, LAVA)
        chunk.setBlock(0, 10, 0, BEDROCK)

        val surface = project(DimensionId.Nether, chunk, dimensionTypeLayout)

        assertEquals(SurfaceBlockState(Identifier("oak_log"), mapOf("axis" to "y")), surface[0, 0])
    }

    @Test
    fun netherFallsBackToNetherrackWhenAColumnContainsNoCandidate() {
        val dimensionTypeLayout = DimensionTypeLayout(0, 16, 16, hasSkyLight = false, hasCeiling = true)
        val chunk = emptyChunk(dimensionTypeLayout.chunkLayout)
        chunk.setBlock(2, 14, 1, NETHERRACK)

        val surface = project(DimensionId.Nether, chunk, dimensionTypeLayout)

        assertEquals(SurfaceBlockState(Identifier("netherrack")), surface[2, 1])
    }

    @Test
    fun netherAirBedrockAndNetherrackOnlyColumnsAllFallBackToNetherrack() {
        val dimensionTypeLayout = DimensionTypeLayout(0, 16, 16, hasSkyLight = false, hasCeiling = true)
        val solidChunk = emptyChunk(dimensionTypeLayout.chunkLayout)
        for (y in dimensionTypeLayout.logicalBlockYRange) {
            solidChunk.setBlock(0, y, 0, NETHERRACK)
        }
        val airBelowCeilingChunk = emptyChunk(dimensionTypeLayout.chunkLayout)
        airBelowCeilingChunk.setBlock(0, 15, 0, BEDROCK)

        val expected = SurfaceBlockState(Identifier("netherrack"))
        assertEquals(expected, project(DimensionId.Nether, solidChunk, dimensionTypeLayout)[0, 0])
        assertEquals(expected, project(DimensionId.Nether, airBelowCeilingChunk, dimensionTypeLayout)[0, 0])
    }

    @Test
    fun allThreeAirStatesProduceEmptyColumns() {
        val dimensionTypeLayout = DimensionTypeLayout(0, 16, 16, hasSkyLight = true, hasCeiling = false)
        listOf(AIR, CAVE_AIR, VOID_AIR).forEachIndexed { localX, air ->
            val chunk = emptyChunk(dimensionTypeLayout.chunkLayout)
            chunk.setBlock(localX, 15, 0, air)
            assertNull(project(DimensionId.Overworld, chunk, dimensionTypeLayout)[localX, 0])
        }
    }

    private fun project(
        dimensionId: DimensionId,
        chunk: Chunk<BlockStateDescriptor, String>,
        dimensionTypeLayout: DimensionTypeLayout,
    ): ChunkSurface = SurfaceProjectionPolicy.project(
        dimensionId = dimensionId,
        chunk = chunk,
        dimensionTypeLayout = dimensionTypeLayout,
        blockStateRegistry = chunkDataRegistries.blockStates,
    )

    private fun emptyChunk(chunkLayout: ChunkLayout): Chunk<BlockStateDescriptor, String> = Chunk(
        chunkPosition = ChunkPosition(0, 0),
        chunkMetadata = ChunkMetadata(),
        chunkLayout = chunkLayout,
        defaultBlockState = AIR,
        defaultBiome = MinecraftBiomeIds.PLAINS.value,
    )

    companion object {
        private val AIR = BlockStateDescriptor(MinecraftBlockIds.AIR.value)
        private val CAVE_AIR = BlockStateDescriptor(Identifier("cave_air").value)
        private val VOID_AIR = BlockStateDescriptor(Identifier("void_air").value)
        private val STONE = BlockStateDescriptor(Identifier("stone").value)
        private val BEDROCK = BlockStateDescriptor(Identifier("bedrock").value)
        private val NETHERRACK = BlockStateDescriptor(Identifier("netherrack").value)
        private val LAVA = BlockStateDescriptor(Identifier("lava").value)
        private val OAK_LOG = BlockStateDescriptor(Identifier("oak_log").value, mapOf("axis" to "y"))
    }
}
