package com.hiczp.minecraft.world.format


/** Shared dimension facts used by domain operations, independent of persistence and packet configuration. */
data class ChunkContext(
    val dimensionId: DimensionId,
    val dimensionTypeLayout: DimensionTypeLayout,
    val defaultBlockState: BlockState,
    val defaultBiome: BiomeId,
)

/**
 * Mutable data for a fully generated Chunk. All nested references remain caller-accessible and replaceable.
 * Mutations do not update Block Entities, statistics, heightmaps, light, ticks or POI as a side effect.
 */
data class Chunk(
    var chunkPosition: ChunkPosition,
    var chunkContext: ChunkContext,
    var sections: MutableMap<Int, ChunkSection>,
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
) {
    /** Creates empty data; this does not run generation or calculate any derived state. */
    constructor(chunkPosition: ChunkPosition, chunkContext: ChunkContext) : this(
        chunkPosition, chunkContext, linkedMapOf(), linkedMapOf(), ChunkHeightmaps(), ChunkLighting(false, null),
        mutableListOf(), mutableListOf(), ChunkStructures(), ChunkPostProcessing(), "minecraft:full", 0, null, null,
        DataProperties(),
    )

    val isFullyGenerated: Boolean get() = status == "minecraft:full"

    fun getBlockState(blockPosition: BlockPosition): BlockState = getBlockState(local(blockPosition))

    fun getBlockState(chunkBlockPosition: ChunkBlockPosition): BlockState {
        requireBlockY(chunkBlockPosition.y)
        return sections[chunkBlockPosition.sectionY]?.terrain?.getBlockState(chunkBlockPosition.localInSection)
            ?: chunkContext.defaultBlockState
    }

    fun setBlockState(blockPosition: BlockPosition, blockState: BlockState): BlockState =
        setBlockState(local(blockPosition), blockState)

    fun setBlockState(chunkBlockPosition: ChunkBlockPosition, blockState: BlockState): BlockState {
        requireBlockY(chunkBlockPosition.y)
        return terrainForWrite(chunkBlockPosition.sectionY).setBlockState(chunkBlockPosition.localInSection, blockState)
    }

    fun getBiome(blockPosition: BlockPosition): BiomeId = getBiome(local(blockPosition))

    fun getBiome(chunkBlockPosition: ChunkBlockPosition): BiomeId {
        requireBlockY(chunkBlockPosition.y)
        return sections[chunkBlockPosition.sectionY]?.terrain?.getBiome(chunkBlockPosition.localInSection)
            ?: chunkContext.defaultBiome
    }

    fun setBiome(blockPosition: BlockPosition, biomeId: BiomeId): BiomeId =
        setBiome(local(blockPosition), biomeId)

    fun setBiome(chunkBlockPosition: ChunkBlockPosition, biomeId: BiomeId): BiomeId {
        requireBlockY(chunkBlockPosition.y)
        return terrainForWrite(chunkBlockPosition.sectionY).setBiome(chunkBlockPosition.localInSection, biomeId)
    }

    fun getBlockEntity(blockPosition: BlockPosition): BlockEntity? {
        local(blockPosition)
        requireBlockY(blockPosition.y)
        return blockEntities[blockPosition]
    }

    private fun terrainForWrite(sectionY: Int): SectionTerrain {
        val chunkSection = sections.getOrPut(sectionY) { ChunkSection(null, SectionLighting(), DataProperties()) }
        return chunkSection.terrain ?: SectionTerrain(chunkContext.defaultBlockState, chunkContext.defaultBiome)
            .also { chunkSection.terrain = it }
    }

    private fun local(blockPosition: BlockPosition): ChunkBlockPosition = chunkPosition.local(blockPosition)

    private fun requireBlockY(y: Int) {
        require(chunkContext.dimensionTypeLayout.chunkLayout.containsBlockY(y)) {
            "Block Y $y is outside ${chunkContext.dimensionTypeLayout.chunkLayout}"
        }
    }
}
