package com.hiczp.minecraft.world.format

/** Terrain, lighting and open Section properties. Null terrain permits light-only boundary Sections. */
data class ChunkSection(
    var terrain: SectionTerrain?,
    var lighting: SectionLighting,
    var properties: DataProperties,
) {
    constructor(defaultBlockState: BlockState, defaultBiome: BiomeId) : this(
        SectionTerrain(defaultBlockState, defaultBiome), SectionLighting(), DataProperties(),
    )
}

/** Mutable block/biome palettes and independently maintained statistics for one Section. */
data class SectionTerrain(
    var blockStates: PalettedContainer<BlockState>,
    var biomes: PalettedContainer<BiomeId>,
    var statistics: SectionStatistics,
) {
    init {
        requireShape()
    }

    constructor(defaultBlockState: BlockState, defaultBiome: BiomeId) : this(
        PalettedContainer(MinecraftCoordinates.SECTION_BLOCK_COUNT, defaultBlockState),
        PalettedContainer(MinecraftCoordinates.SECTION_BIOME_COUNT, defaultBiome),
        SectionStatistics(null, null, null, null),
    )

    fun getBlockState(localBlockPosition: LocalBlockPosition): BlockState = blockStates[localBlockPosition.index]

    fun setBlockState(localBlockPosition: LocalBlockPosition, blockState: BlockState): BlockState =
        blockStates.replace(localBlockPosition.index, blockState)

    fun getBiome(quartX: Int, quartY: Int, quartZ: Int): BiomeId =
        biomes[MinecraftCoordinates.biomeIndex(quartX, quartY, quartZ)]

    fun getBiome(localBlockPosition: LocalBlockPosition): BiomeId = biomes[biomeIndex(localBlockPosition)]

    fun setBiome(quartX: Int, quartY: Int, quartZ: Int, biomeId: BiomeId): BiomeId =
        biomes.replace(MinecraftCoordinates.biomeIndex(quartX, quartY, quartZ), biomeId)

    fun setBiome(localBlockPosition: LocalBlockPosition, biomeId: BiomeId): BiomeId =
        biomes.replace(biomeIndex(localBlockPosition), biomeId)

    /** Operations that serialize the complete shape also check after callers replace a palette reference. */
    fun requireShape() {
        require(blockStates.size == MinecraftCoordinates.SECTION_BLOCK_COUNT) {
            "A Section needs ${MinecraftCoordinates.SECTION_BLOCK_COUNT} block states"
        }
        require(biomes.size == MinecraftCoordinates.SECTION_BIOME_COUNT) {
            "A Section needs ${MinecraftCoordinates.SECTION_BIOME_COUNT} biome samples"
        }
    }

    private fun biomeIndex(localBlockPosition: LocalBlockPosition): Int = MinecraftCoordinates.biomeIndex(
        MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.x),
        MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.y),
        MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.z),
    )
}

/** Null means not supplied. Editing blockStates never alters these independently writable values. */
data class SectionStatistics(
    var nonEmptyBlockCount: Int?,
    var fluidCount: Int?,
    var tickingBlockCount: Int?,
    var tickingFluidCount: Int?,
)

internal fun bitsForPaletteSize(size: Int): Int {
    require(size > 0)
    return if (size == 1) 0 else Int.SIZE_BITS - (size - 1).countLeadingZeroBits()
}
