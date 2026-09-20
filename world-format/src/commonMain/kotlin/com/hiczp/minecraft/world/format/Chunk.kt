package com.hiczp.minecraft.world.format


/** Shared dimension facts used by domain operations, independent of persistence and packet configuration. */
data class ChunkContext(
    val dimensionId: DimensionId,
    val dimensionTypeLayout: DimensionTypeLayout,
    val defaultBlockState: BlockState,
    val defaultBiome: BiomeId,
    var blockStateDefinitions: MutableMap<BlockId, BlockStateDefinition> = hashMapOf(),
)

/**
 * Mutable identity graph for a fully generated Chunk. Arrays and all nested references are caller-owned and replaceable.
 * This data is not thread-safe. Replacing a context does not change the absolute origin of its Section array.
 * Mutations do not update Block Entities, statistics, heightmaps, light, ticks or POI as a side effect.
 */
class Chunk(
    var chunkPosition: ChunkPosition,
    var chunkContext: ChunkContext,
    var sections: Array<ChunkSection?>,
    var blockEntities: MutableMap<BlockPosition, BlockEntity>,
    var heightmaps: ChunkHeightmaps,
    var lighting: ChunkLighting,
    var blockTicks: MutableList<ScheduledTick<BlockId>>,
    var fluidTicks: MutableList<ScheduledTick<FluidId>>,
    var structures: ChunkStructures,
    var postProcessing: ChunkPostProcessing,
    var status: String,
    var inhabitedTime: Long,
    var upgradeData: UpgradeData?,
    var blendingData: BlendingData?,
    var properties: DataProperties,
    var sectionMinY: Int = chunkContext.dimensionTypeLayout.chunkLayout.minSectionY - 1,
) {
    /** Creates empty data; this does not run generation or calculate any derived state. */
    constructor(chunkPosition: ChunkPosition, chunkContext: ChunkContext) : this(
        chunkPosition, chunkContext, arrayOfNulls(chunkContext.dimensionTypeLayout.chunkLayout.sectionCount + 2),
        hashMapOf(), ChunkHeightmaps(), ChunkLighting(false, null), mutableListOf(), mutableListOf(), ChunkStructures(),
        ChunkPostProcessing(
            chunkContext.dimensionTypeLayout.chunkLayout.minSectionY,
            arrayOfNulls(chunkContext.dimensionTypeLayout.chunkLayout.sectionCount),
        ),
        "minecraft:full", 0, null, null, DataProperties(),
    )

    /** Absolute Y of slot zero is stored independently of the replaceable context. Null slots are absent Sections. */
    fun getSection(sectionY: Int): ChunkSection? = sections.getOrNull(sectionY - sectionMinY)

    /** Assigns by absolute Y. Extending the represented interval replaces the array; existing array aliases stay valid. */
    fun setSection(sectionY: Int, chunkSection: ChunkSection?) {
        val index = sectionY - sectionMinY
        if (index in sections.indices) {
            sections[index] = chunkSection
        } else if (chunkSection != null) {
            val min = minOf(sectionMinY, sectionY)
            val max = maxOf(sectionMinY.toLong() + sections.size, sectionY.toLong() + 1)
            require(max - min <= Int.MAX_VALUE) { "Section array exceeds the representable size" }
            val expanded = arrayOfNulls<ChunkSection>((max - min).toInt())
            sections.copyInto(expanded, sectionMinY - min)
            expanded[sectionY - min] = chunkSection
            sections = expanded
            sectionMinY = min
        }
    }

    /** Iterates present Sections in ascending absolute Y without sorting or materializing a map. */
    fun sectionEntries(): Sequence<Pair<Int, ChunkSection>> =
        sections.asSequence().mapIndexedNotNull { index, section ->
            section?.let { sectionMinY + index to it }
        }

    val isFullyGenerated: Boolean get() = status == "minecraft:full"

    fun getBlockState(blockPosition: BlockPosition): BlockState {
        requireMember(blockPosition)
        return getBlockState(blockPosition.x and 15, blockPosition.y, blockPosition.z and 15)
    }

    fun getBlockState(chunkBlockPosition: ChunkBlockPosition): BlockState =
        getBlockState(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z)

    /** Local X/Z in 0..15 and absolute Y; avoids constructing temporary position values during dense scans. */
    fun getBlockState(localX: Int, y: Int, localZ: Int): BlockState {
        requireBlockY(y)
        return getSection(y shr 4)?.terrain?.blockStates?.get(((y and 15) shl 8) or (localZ shl 4) or localX)
            ?: chunkContext.defaultBlockState
    }

    fun setBlockState(blockPosition: BlockPosition, blockState: BlockState): BlockState {
        requireMember(blockPosition)
        return setBlockState(blockPosition.x and 15, blockPosition.y, blockPosition.z and 15, blockState)
    }

    fun setBlockState(chunkBlockPosition: ChunkBlockPosition, blockState: BlockState): BlockState =
        setBlockState(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z, blockState)

    /** Writes a cell without constructing temporary coordinates or updating other Chunk data. */
    fun setBlockState(localX: Int, y: Int, localZ: Int, blockState: BlockState): BlockState {
        requireBlockY(y)
        return terrainForWrite(y shr 4).blockStates.replace(((y and 15) shl 8) or (localZ shl 4) or localX, blockState)
    }

    fun getBiome(blockPosition: BlockPosition): BiomeId {
        requireMember(blockPosition)
        return getBiome(blockPosition.x and 15, blockPosition.y, blockPosition.z and 15)
    }

    fun getBiome(chunkBlockPosition: ChunkBlockPosition): BiomeId =
        getBiome(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z)

    /** Local block X/Z in 0..15 and absolute block Y, sampled at quart-cell resolution. */
    fun getBiome(localX: Int, y: Int, localZ: Int): BiomeId {
        requireBlockY(y)
        return getSection(y shr 4)?.terrain?.biomes?.get(((y and 15) shr 2 shl 4) or (localZ shr 2 shl 2) or (localX shr 2))
            ?: chunkContext.defaultBiome
    }

    fun setBiome(blockPosition: BlockPosition, biomeId: BiomeId): BiomeId {
        requireMember(blockPosition)
        return setBiome(blockPosition.x and 15, blockPosition.y, blockPosition.z and 15, biomeId)
    }

    fun setBiome(chunkBlockPosition: ChunkBlockPosition, biomeId: BiomeId): BiomeId =
        setBiome(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z, biomeId)

    fun setBiome(localX: Int, y: Int, localZ: Int, biomeId: BiomeId): BiomeId {
        requireBlockY(y)
        return terrainForWrite(y shr 4).biomes.replace(
            ((y and 15) shr 2 shl 4) or (localZ shr 2 shl 2) or (localX shr 2),
            biomeId
        )
    }

    fun getBlockEntity(blockPosition: BlockPosition): BlockEntity? {
        requireMember(blockPosition)
        requireBlockY(blockPosition.y)
        return blockEntities[blockPosition]
    }

    private fun terrainForWrite(sectionY: Int): SectionTerrain {
        val chunkSection = getSection(sectionY) ?: ChunkSection(null, SectionLighting(), DataProperties()).also {
            setSection(sectionY, it)
        }
        return chunkSection.terrain ?: SectionTerrain(chunkContext.defaultBlockState, chunkContext.defaultBiome)
            .also { chunkSection.terrain = it }
    }

    private fun requireMember(blockPosition: BlockPosition) {
        require(blockPosition.x shr 4 == chunkPosition.x && blockPosition.z shr 4 == chunkPosition.z) {
            "Block $blockPosition does not belong to Chunk $chunkPosition"
        }
    }

    private fun requireBlockY(y: Int) {
        require(chunkContext.dimensionTypeLayout.chunkLayout.containsBlockY(y)) {
            "Block Y $y is outside ${chunkContext.dimensionTypeLayout.chunkLayout}"
        }
    }
}
