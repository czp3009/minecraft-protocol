package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.MinecraftCoordinates

const val MIN_MAP_ZOOM: Int = 0
const val MAX_MAP_ZOOM: Int = 4

fun pixelsPerBlock(zoom: Int): Int {
    require(zoom in MIN_MAP_ZOOM..MAX_MAP_ZOOM) { "Map zoom must be between $MIN_MAP_ZOOM and $MAX_MAP_ZOOM" }
    return 1 shl zoom
}

data class VisibleBlockBounds(
    val minBlockX: Double,
    val minBlockZ: Double,
    val maxBlockX: Double,
    val maxBlockZ: Double,
) {
    init {
        require(minBlockX.isFinite() && minBlockZ.isFinite() && maxBlockX.isFinite() && maxBlockZ.isFinite()) {
            "Visible block bounds must be finite"
        }
    }

    fun toChunkViewport(): ChunkViewport = ChunkViewport(
        minChunkX = MinecraftCoordinates.chunkCoordinate(MinecraftCoordinates.blockCoordinate(minBlockX)),
        minChunkZ = MinecraftCoordinates.chunkCoordinate(MinecraftCoordinates.blockCoordinate(minBlockZ)),
        maxChunkX = MinecraftCoordinates.chunkCoordinate(MinecraftCoordinates.blockCoordinate(maxBlockX)),
        maxChunkZ = MinecraftCoordinates.chunkCoordinate(MinecraftCoordinates.blockCoordinate(maxBlockZ)),
    ).normalized
}
