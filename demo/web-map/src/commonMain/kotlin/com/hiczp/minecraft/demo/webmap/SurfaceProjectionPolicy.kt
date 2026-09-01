package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.world.format.*

fun interface SurfaceBlockTransparency {
    fun isTransparent(surfaceBlockState: SurfaceBlockState): Boolean
}

object SurfaceProjectionPolicy {
    fun <B : Any, M : Any> project(
        chunk: Chunk<B, M>,
        blockYRange: IntRange,
        blockStateRegistry: BlockStateRegistry<B>,
        surfaceBlockTransparency: SurfaceBlockTransparency,
    ): ChunkSurface {
        require(
            blockYRange.isEmpty() ||
                    chunk.chunkLayout.containsBlockY(blockYRange.first) &&
                    chunk.chunkLayout.containsBlockY(blockYRange.last),
        ) { "Surface Block Y range must be within the Chunk layout" }
        val surfaceBlockClassifier = SurfaceBlockClassifier(blockStateRegistry, surfaceBlockTransparency)
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
    private fun <B : Any, M : Any> scanSurfaceBlocks(
        chunk: Chunk<B, M>,
        blockYRange: IntRange,
        surfaceBlockClassifier: SurfaceBlockClassifier<B>,
    ): List<List<B>?> {
        val firstNonAirBlocks = MutableList<B?>(SURFACE_CELL_COUNT) { null }
        val visibleBlockLayers = MutableList<MutableList<B>?>(SURFACE_CELL_COUNT) { null }
        val encounteredAir = BooleanArray(SURFACE_CELL_COUNT)
        val resolved = BooleanArray(SURFACE_CELL_COUNT)
        var unresolvedColumnCount = SURFACE_CELL_COUNT
        for (blockY in blockYRange.reversed()) {
            if (unresolvedColumnCount == 0) break
            val sectionY = MinecraftCoordinates.sectionCoordinate(blockY)
            val localY = MinecraftCoordinates.blockCoordinateInSection(blockY)
            val chunkSection = chunk.section(sectionY)
            for (cellIndex in 0 until SURFACE_CELL_COUNT) {
                if (resolved[cellIndex]) continue
                val blockState = chunkSection.block(cellIndex, localY, chunk.defaultBlockState)
                if (surfaceBlockClassifier.isAir(blockState)) {
                    encounteredAir[cellIndex] = true
                    continue
                }
                if (firstNonAirBlocks[cellIndex] == null) firstNonAirBlocks[cellIndex] = blockState
                if (!encounteredAir[cellIndex]) continue
                val blockLayers = visibleBlockLayers[cellIndex]
                    ?: mutableListOf<B>().also { visibleBlockLayers[cellIndex] = it }
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

    private fun <B : Any, M : Any> ChunkSection<B, M>?.block(
        cellIndex: Int,
        localY: Int,
        defaultBlockState: B,
    ): B = this?.blockStates?.get(localY * SURFACE_CELL_COUNT + cellIndex) ?: defaultBlockState
}

private class SurfaceBlockClassifier<B : Any>(
    private val blockStateRegistry: BlockStateRegistry<B>,
    private val surfaceBlockTransparency: SurfaceBlockTransparency,
) {
    private val surfaceBlockStates = mutableMapOf<B, SurfaceBlockState>()

    fun describe(blockState: B): SurfaceBlockState = surfaceBlockStates.getOrPut(blockState) {
        val blockStateDescriptor = checkNotNull(blockStateRegistry.describe(blockState)) {
            "Decoded block state cannot be described by its active registry"
        }
        blockStateDescriptor.toSurfaceBlockState()
    }

    fun isAir(blockState: B): Boolean = describe(blockState).name in AIR_BLOCK_NAMES

    fun isTransparent(blockState: B): Boolean = surfaceBlockTransparency.isTransparent(describe(blockState))
}

private fun BlockStateDescriptor.toSurfaceBlockState(): SurfaceBlockState =
    SurfaceBlockState(Identifier(name), properties)

private val AIR_BLOCK_NAMES: Set<Identifier> = setOf(
    MinecraftBlockIds.AIR,
    Identifier("cave_air"),
    Identifier("void_air"),
)
