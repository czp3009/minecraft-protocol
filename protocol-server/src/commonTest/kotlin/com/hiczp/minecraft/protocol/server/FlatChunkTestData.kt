package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.world.format.*

/**
 * Creates the finite initial-view recipe: the context's empty block, one non-fluid surface layer, uniform biomes,
 * fixed heightmaps and explicit light. It is data construction; no later mutation recalculates those values.
 */
internal fun createFlatChunk(
    chunkPosition: ChunkPosition,
    chunkContext: ChunkContext,
    groundY: Int,
    surfaceBlockState: BlockState,
    fullBrightSky: Boolean = chunkContext.dimensionTypeLayout.hasSkyLight,
): Chunk {
    val chunkLayout = chunkContext.dimensionTypeLayout.chunkLayout
    require(groundY in chunkLayout.blockYRange) { "Ground Y $groundY is outside the Chunk layout" }
    val hasSurface = surfaceBlockState != chunkContext.defaultBlockState
    val groundSectionY = MinecraftCoordinates.sectionCoordinate(groundY)
    val localGroundY = MinecraftCoordinates.blockCoordinateInSection(groundY)
    val chunk = Chunk(chunkPosition, chunkContext)
    chunkLayout.sectionYRange.forEach { sectionY ->
        val terrain = SectionTerrain(chunkContext.defaultBlockState, chunkContext.defaultBiome)
        val surfaceHere = sectionY == groundSectionY && hasSurface
        if (surfaceHere) {
            repeat(MinecraftCoordinates.SECTION_SIDE * MinecraftCoordinates.SECTION_SIDE) { index ->
                terrain.blockStates[localGroundY * MinecraftCoordinates.SECTION_SIDE * MinecraftCoordinates.SECTION_SIDE + index] =
                    surfaceBlockState
            }
        }
        terrain.statistics = SectionStatistics(
            if (surfaceHere) MinecraftCoordinates.SECTION_SIDE * MinecraftCoordinates.SECTION_SIDE else 0,
            0,
            0,
            0
        )
        chunk.sections[sectionY] = ChunkSection(
            terrain,
            SectionLighting(
                LightLayer(0),
                if (chunkContext.dimensionTypeLayout.hasSkyLight) LightLayer(if (fullBrightSky) 15 else 0) else null,
            ),
            DataProperties(),
        )
    }
    val height = if (hasSurface) groundY + 1 else chunkLayout.minBlockY
    chunk.heightmaps.maps[HeightmapType.WorldSurface] = Heightmap(ColumnData<Int?>(height))
    chunk.heightmaps.maps[HeightmapType.MotionBlocking] = Heightmap(ColumnData<Int?>(height))
    chunk.lighting.isLightCorrect = true
    return chunk
}
