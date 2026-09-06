package com.hiczp.minecraft.world.format


/**
 * The vertical Section layout of one dimension.
 *
 * This value has no release-wide default: servers can synchronize different vanilla, datapack, or modded dimension
 * types with different minimum Y coordinates and heights.
 */
data class ChunkLayout(
    val minSectionY: Int,
    val sectionCount: Int,
) {
    init {
        require(sectionCount > 0) { "A Chunk layout must contain at least one Section" }
        val maximumSectionY = MinecraftCoordinates.offsetSectionCoordinate(minSectionY, sectionCount - 1)
        MinecraftCoordinates.sectionBlockCoordinate(minSectionY, 0)
        MinecraftCoordinates.sectionBlockCoordinate(maximumSectionY, SECTION_SIDE - 1)
        MinecraftCoordinates.blockCountForSections(sectionCount)
    }

    companion object {
        /** Creates a Section layout from block-aligned dimension bounds. */
        fun fromBlockBounds(minY: Int, height: Int): ChunkLayout {
            require(minY % SECTION_SIDE == 0) {
                "Chunk minimum block Y must be a multiple of $SECTION_SIDE"
            }
            require(height % SECTION_SIDE == 0) {
                "Chunk block height must be a multiple of $SECTION_SIDE"
            }
            return ChunkLayout(
                minSectionY = MinecraftCoordinates.sectionCoordinate(minY),
                sectionCount = height / SECTION_SIDE,
            )
        }
    }

    val maxSectionY: Int
        get() = MinecraftCoordinates.offsetSectionCoordinate(minSectionY, sectionCount - 1)

    val sectionYRange: IntRange
        get() = minSectionY..maxSectionY

    val minBlockY: Int
        get() = MinecraftCoordinates.sectionBlockCoordinate(minSectionY, 0)

    val height: Int
        get() = MinecraftCoordinates.blockCountForSections(sectionCount)

    val maxBlockY: Int
        get() = MinecraftCoordinates.sectionBlockCoordinate(maxSectionY, SECTION_SIDE - 1)

    val blockYRange: IntRange
        get() = minBlockY..maxBlockY

    operator fun contains(sectionY: Int): Boolean = sectionY in minSectionY..maxSectionY

    fun containsBlockY(y: Int): Boolean = y in blockYRange
}
