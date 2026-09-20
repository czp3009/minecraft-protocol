package com.hiczp.minecraft.world.format


/**
 * The vertical Section layout of one dimension.
 *
 * This value has no release-wide default: servers can synchronize different vanilla, datapack, or modded dimension
 * types with different minimum Y coordinates and heights. Share layouts across Chunks; immutable bounds and ranges
 * are computed and validated once at construction, so membership checks need no temporary range or conversion.
 */
data class ChunkLayout(
    val minSectionY: Int,
    val sectionCount: Int,
) {
    init {
        require(sectionCount > 0) { "A Chunk layout must contain at least one Section" }
    }

    companion object {
        /** Creates a Section layout from block-aligned dimension bounds. */
        fun fromBlockBounds(minY: Int, height: Int): ChunkLayout {
            require(minY % MinecraftCoordinates.SECTION_SIDE == 0) {
                "Chunk minimum block Y must be a multiple of ${MinecraftCoordinates.SECTION_SIDE}"
            }
            require(height % MinecraftCoordinates.SECTION_SIDE == 0) {
                "Chunk block height must be a multiple of ${MinecraftCoordinates.SECTION_SIDE}"
            }
            return ChunkLayout(
                minSectionY = MinecraftCoordinates.sectionCoordinate(minY),
                sectionCount = height / MinecraftCoordinates.SECTION_SIDE,
            )
        }
    }

    val maxSectionY: Int = MinecraftCoordinates.offsetSectionCoordinate(minSectionY, sectionCount - 1)

    val sectionYRange: IntRange = minSectionY..maxSectionY

    val minBlockY: Int = MinecraftCoordinates.sectionBlockCoordinate(minSectionY, 0)

    val height: Int = MinecraftCoordinates.blockCountForSections(sectionCount)

    val maxBlockY: Int = MinecraftCoordinates.sectionBlockCoordinate(maxSectionY, MinecraftCoordinates.SECTION_SIDE - 1)

    val blockYRange: IntRange = minBlockY..maxBlockY

    operator fun contains(sectionY: Int): Boolean = sectionY >= minSectionY && sectionY <= maxSectionY

    fun containsBlockY(y: Int): Boolean = y >= minBlockY && y <= maxBlockY
}
