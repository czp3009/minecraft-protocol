package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.world.format.BlockState
import com.hiczp.minecraft.world.format.Chunk
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.world.format.MinecraftCoordinates

fun interface SurfaceBlockTransparency {
    fun isTransparent(surfaceBlockState: SurfaceBlockState): Boolean
}

object SurfaceProjectionPolicy {
    fun project(
        chunk: Chunk,
        blockYRange: IntRange,
        surfaceBlockTransparency: SurfaceBlockTransparency,
    ): ChunkSurface {
        require(
            blockYRange.isEmpty() ||
                    chunk.chunkContext.dimensionTypeLayout.chunkLayout.containsBlockY(blockYRange.first) &&
                    chunk.chunkContext.dimensionTypeLayout.chunkLayout.containsBlockY(blockYRange.last),
        ) { "Surface Block Y range must be within the Chunk layout" }
        val surfaceBlockClassifier = SurfaceBlockClassifier(surfaceBlockTransparency)
        val blockLayers = scanSurfaceBlocks(chunk, blockYRange, surfaceBlockClassifier)
        val paletteIndices = linkedMapOf<SurfaceColumn, Int>()
        val cells = blockLayers.map { blocks ->
            blocks?.map(surfaceBlockClassifier::describe)?.let(::SurfaceColumn)?.let { surfaceColumn ->
                paletteIndices.getOrPut(surfaceColumn) { paletteIndices.size }
            }
        }
        return ChunkSurface(
            palette = paletteIndices.keys.toList(),
            cells = cells,
        )
    }

    /**
     * Visits every inspected Block at most once. For each column, the first non-air Block after any air starts the
     * visible stack. Transparent Blocks keep the column open until the first opaque Block is included. If no Block
     * follows air, the highest non-air Block encountered during the same pass is the fallback.
     */
    private fun scanSurfaceBlocks(
        chunk: Chunk,
        blockYRange: IntRange,
        surfaceBlockClassifier: SurfaceBlockClassifier,
    ): List<List<BlockState>?> {
        val firstNonAirBlocks = MutableList<BlockState?>(SURFACE_CELL_COUNT) { null }
        val visibleBlockLayers = MutableList<MutableList<BlockState>?>(SURFACE_CELL_COUNT) { null }
        val encounteredAir = BooleanArray(SURFACE_CELL_COUNT)
        val resolved = BooleanArray(SURFACE_CELL_COUNT)
        var unresolvedColumnCount = SURFACE_CELL_COUNT
        for (blockY in blockYRange.reversed()) {
            if (unresolvedColumnCount == 0) break
            val sectionY = MinecraftCoordinates.sectionCoordinate(blockY)
            val localY = MinecraftCoordinates.blockCoordinateInSection(blockY)
            val chunkSection = chunk.sections[sectionY]
            for (cellIndex in 0 until SURFACE_CELL_COUNT) {
                if (resolved[cellIndex]) continue
                val blockState = chunkSection.block(cellIndex, localY, chunk.chunkContext.defaultBlockState)
                if (surfaceBlockClassifier.isAir(blockState)) {
                    encounteredAir[cellIndex] = true
                    continue
                }
                if (firstNonAirBlocks[cellIndex] == null) firstNonAirBlocks[cellIndex] = blockState
                if (!encounteredAir[cellIndex]) continue
                val blockLayers = visibleBlockLayers[cellIndex]
                    ?: mutableListOf<BlockState>().also { visibleBlockLayers[cellIndex] = it }
                blockLayers += blockState
                if (!surfaceBlockClassifier.isTransparent(blockState)) {
                    resolved[cellIndex] = true
                    unresolvedColumnCount--
                }
            }
        }
        return visibleBlockLayers.mapIndexed { cellIndex, blockLayers ->
            blockLayers ?: firstNonAirBlocks[cellIndex]?.let(::listOf)
        }
    }

    private fun ChunkSection?.block(
        cellIndex: Int,
        localY: Int,
        defaultBlockState: BlockState,
    ): BlockState = this?.terrain?.blockStates?.get(localY * SURFACE_CELL_COUNT + cellIndex) ?: defaultBlockState
}

private class SurfaceBlockClassifier(
    private val surfaceBlockTransparency: SurfaceBlockTransparency,
) {
    private val surfaceBlockStates = mutableMapOf<BlockState, SurfaceBlockState>()

    fun describe(blockState: BlockState): SurfaceBlockState = surfaceBlockStates.getOrPut(blockState) {
        SurfaceBlockState(Identifier(blockState.blockId.value), blockState.properties.toMap())
    }

    fun isAir(blockState: BlockState): Boolean = describe(blockState).name in AIR_BLOCK_NAMES

    fun isTransparent(blockState: BlockState): Boolean = surfaceBlockTransparency.isTransparent(describe(blockState))
}

private val AIR_BLOCK_NAMES: Set<Identifier> = setOf(
    MinecraftBlockIds.AIR,
    Identifier("cave_air"),
    Identifier("void_air"),
)
