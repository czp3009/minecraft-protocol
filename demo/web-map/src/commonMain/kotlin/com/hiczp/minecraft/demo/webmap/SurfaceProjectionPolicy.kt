package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.world.format.*

object SurfaceProjectionPolicy {
    fun <B : Any, M : Any> project(
        dimensionId: DimensionId,
        chunk: Chunk<B, M>,
        dimensionTypeLayout: DimensionTypeLayout,
        blockStateRegistry: BlockStateRegistry<B>,
    ): ChunkSurface {
        val surfaceBlockClassifier = SurfaceBlockClassifier(blockStateRegistry)
        val blockStates = if (dimensionId == DimensionId.Nether) {
            netherSurfaceBlocks(
                chunk = chunk,
                logicalBlockYRange = dimensionTypeLayout.logicalBlockYRange,
                surfaceBlockClassifier = surfaceBlockClassifier,
                netherrackBlockState = checkNotNull(
                    blockStateRegistry.resolve(BlockStateDescriptor(NETHERRACK_BLOCK_NAME.value)),
                ) { "The active block-state registry does not contain the default netherrack state" },
            )
        } else {
            highestSurfaceBlocks(chunk, surfaceBlockClassifier)
        }
        val paletteIndices = linkedMapOf<SurfaceBlockState, Int>()
        val cells = blockStates.map { blockState ->
            val surfaceBlockState = blockState?.let(surfaceBlockClassifier::describe)
            surfaceBlockState?.let { value -> paletteIndices.getOrPut(value) { paletteIndices.size } }
        }
        return ChunkSurface(
            palette = paletteIndices.keys.toList(),
            cells = cells,
        )
    }

    private fun <B : Any, M : Any> highestSurfaceBlocks(
        chunk: Chunk<B, M>,
        surfaceBlockClassifier: SurfaceBlockClassifier<B>,
    ): List<B?> {
        val blockStates = MutableList<B?>(SURFACE_CELL_COUNT) { null }
        val unresolvedCells = IntArray(SURFACE_CELL_COUNT) { cellIndex -> cellIndex }
        var unresolvedColumnCount = SURFACE_CELL_COUNT
        for (sectionY in chunk.chunkLayout.maxSectionY downTo chunk.chunkLayout.minSectionY) {
            if (unresolvedColumnCount == 0) break
            val chunkSection = chunk.section(sectionY)
            val paletteClassification = chunkSection.paletteValuesOrDefault(chunk.defaultBlockState).classify(
                surfaceBlockClassifier::isAir,
            )
            when (paletteClassification) {
                PaletteClassification.ALL -> continue
                PaletteClassification.NONE -> {
                    fillLayer(
                        blockStates = blockStates,
                        unresolvedCells = unresolvedCells,
                        unresolvedColumnCount = unresolvedColumnCount,
                        chunkSection = chunkSection,
                        localY = SECTION_SIDE - 1,
                        defaultBlockState = chunk.defaultBlockState,
                    )
                    unresolvedColumnCount = 0
                }

                PaletteClassification.MIXED -> Unit
            }
            unresolvedColumnCount = scanSection(
                blockStates = blockStates,
                unresolvedCells = unresolvedCells,
                unresolvedColumnCount = unresolvedColumnCount,
                chunkSection = chunkSection,
                localYRange = SECTION_SIDE - 1 downTo 0,
                defaultBlockState = chunk.defaultBlockState,
                isSurface = { blockState -> !surfaceBlockClassifier.isAir(blockState) },
            )
        }
        return blockStates
    }

    private fun <B : Any, M : Any> netherSurfaceBlocks(
        chunk: Chunk<B, M>,
        logicalBlockYRange: IntRange,
        surfaceBlockClassifier: SurfaceBlockClassifier<B>,
        netherrackBlockState: B,
    ): List<B?> {
        val blockStates = MutableList<B?>(SURFACE_CELL_COUNT) { null }
        val unresolvedCells = IntArray(SURFACE_CELL_COUNT) { cellIndex -> cellIndex }
        if (logicalBlockYRange.isEmpty()) return List(SURFACE_CELL_COUNT) { netherrackBlockState }
        val maximumY = minOf(logicalBlockYRange.last, chunk.chunkLayout.maxBlockY)
        val minimumY = maxOf(logicalBlockYRange.first, chunk.chunkLayout.minBlockY)
        if (minimumY > maximumY) return List(SURFACE_CELL_COUNT) { netherrackBlockState }
        var unresolvedColumnCount = SURFACE_CELL_COUNT
        val maximumSectionY = MinecraftCoordinates.sectionCoordinate(maximumY)
        val minimumSectionY = MinecraftCoordinates.sectionCoordinate(minimumY)
        for (sectionY in maximumSectionY downTo minimumSectionY) {
            if (unresolvedColumnCount == 0) break
            val chunkSection = chunk.section(sectionY)
            val paletteClassification = chunkSection.paletteValuesOrDefault(chunk.defaultBlockState).classify(
                surfaceBlockClassifier::isNetherSurfaceCandidate,
            )
            val localMaximumY = if (sectionY == maximumSectionY) {
                MinecraftCoordinates.blockCoordinateInSection(maximumY)
            } else {
                SECTION_SIDE - 1
            }
            val localMinimumY = if (sectionY == minimumSectionY) {
                MinecraftCoordinates.blockCoordinateInSection(minimumY)
            } else {
                0
            }
            if (paletteClassification == PaletteClassification.NONE) continue
            if (paletteClassification == PaletteClassification.ALL) {
                fillLayer(
                    blockStates = blockStates,
                    unresolvedCells = unresolvedCells,
                    unresolvedColumnCount = unresolvedColumnCount,
                    chunkSection = chunkSection,
                    localY = localMaximumY,
                    defaultBlockState = chunk.defaultBlockState,
                )
                unresolvedColumnCount = 0
                continue
            }
            unresolvedColumnCount = scanSection(
                blockStates = blockStates,
                unresolvedCells = unresolvedCells,
                unresolvedColumnCount = unresolvedColumnCount,
                chunkSection = chunkSection,
                localYRange = localMaximumY downTo localMinimumY,
                defaultBlockState = chunk.defaultBlockState,
                isSurface = surfaceBlockClassifier::isNetherSurfaceCandidate,
            )
        }
        blockStates.indices.forEach { cellIndex ->
            if (blockStates[cellIndex] == null) blockStates[cellIndex] = netherrackBlockState
        }
        return blockStates
    }

    private fun <B : Any, M : Any> ChunkSection<B, M>?.paletteValuesOrDefault(defaultBlockState: B): List<B> =
        this?.blockStates?.paletteSnapshot()?.values ?: listOf(defaultBlockState)

    private fun <B : Any, M : Any> ChunkSection<B, M>?.block(cellIndex: Int, localY: Int, defaultBlockState: B): B {
        return this?.blockStates?.get(localY * SURFACE_CELL_COUNT + cellIndex) ?: defaultBlockState
    }

    private fun <B : Any, M : Any> fillLayer(
        blockStates: MutableList<B?>,
        unresolvedCells: IntArray,
        unresolvedColumnCount: Int,
        chunkSection: ChunkSection<B, M>?,
        localY: Int,
        defaultBlockState: B,
    ) {
        for (unresolvedIndex in 0 until unresolvedColumnCount) {
            val cellIndex = unresolvedCells[unresolvedIndex]
            blockStates[cellIndex] = chunkSection.block(cellIndex, localY, defaultBlockState)
        }
    }

    private fun <B : Any, M : Any> scanSection(
        blockStates: MutableList<B?>,
        unresolvedCells: IntArray,
        unresolvedColumnCount: Int,
        chunkSection: ChunkSection<B, M>?,
        localYRange: IntProgression,
        defaultBlockState: B,
        isSurface: (B) -> Boolean,
    ): Int {
        var remainingColumnCount = unresolvedColumnCount
        for (localY in localYRange) {
            var retainedColumnCount = 0
            for (unresolvedIndex in 0 until remainingColumnCount) {
                val cellIndex = unresolvedCells[unresolvedIndex]
                val blockState = chunkSection.block(cellIndex, localY, defaultBlockState)
                if (isSurface(blockState)) {
                    blockStates[cellIndex] = blockState
                } else {
                    unresolvedCells[retainedColumnCount] = cellIndex
                    retainedColumnCount++
                }
            }
            remainingColumnCount = retainedColumnCount
            if (remainingColumnCount == 0) break
        }
        return remainingColumnCount
    }
}

private enum class PaletteClassification {
    NONE,
    ALL,
    MIXED,
}

private fun <B : Any> List<B>.classify(predicate: (B) -> Boolean): PaletteClassification {
    var hasMatch = false
    var hasMismatch = false
    forEach { value ->
        if (predicate(value)) hasMatch = true else hasMismatch = true
        if (hasMatch && hasMismatch) return PaletteClassification.MIXED
    }
    return if (hasMatch) PaletteClassification.ALL else PaletteClassification.NONE
}

private class SurfaceBlockClassifier<B : Any>(
    private val blockStateRegistry: BlockStateRegistry<B>,
) {
    private val surfaceBlockStates = mutableMapOf<B, SurfaceBlockState>()

    fun describe(blockState: B): SurfaceBlockState = surfaceBlockStates.getOrPut(blockState) {
        val blockStateDescriptor = checkNotNull(blockStateRegistry.describe(blockState)) {
            "Decoded block state cannot be described by its active registry"
        }
        blockStateDescriptor.toSurfaceBlockState()
    }

    fun isAir(blockState: B): Boolean = describe(blockState).name in AIR_BLOCK_NAMES

    fun isNetherSurfaceCandidate(blockState: B): Boolean = describe(blockState).name !in NETHER_IGNORED_BLOCK_NAMES
}

private fun BlockStateDescriptor.toSurfaceBlockState(): SurfaceBlockState =
    SurfaceBlockState(Identifier(name), properties)

private val AIR_BLOCK_NAMES: Set<Identifier> = setOf(
    MinecraftBlockIds.AIR,
    Identifier("cave_air"),
    Identifier("void_air"),
)
private val BEDROCK_BLOCK_NAME: Identifier = Identifier("bedrock")
private val NETHERRACK_BLOCK_NAME: Identifier = Identifier("netherrack")
private val NETHER_IGNORED_BLOCK_NAMES: Set<Identifier> = AIR_BLOCK_NAMES + BEDROCK_BLOCK_NAME + NETHERRACK_BLOCK_NAME
