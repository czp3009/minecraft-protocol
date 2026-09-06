package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*

/**
 * Borrows one dimension and one complete decoder for a caller-chosen batch or session.
 * Choose a new view when any input, including the tick base, changes. The view owns no files or lease.
 */
data class DimensionChunkReads(
    val minecraftWorldDimension: MinecraftWorldDimension,
    val chunkNbtDecoder: ChunkNbtDecoder,
) {
    suspend fun readChunk(chunkPosition: ChunkPosition): ChunkNbtDecodeResult? =
        minecraftWorldDimension.openRegion(chunkPosition.regionPosition).use { regionHandle ->
            regionHandle.readChunk(chunkPosition, chunkNbtDecoder)
        }

    suspend fun <R> withRegionReadScope(
        regionPosition: RegionPosition,
        block: DecodedChunkRegionReadScope.() -> R,
    ): R = minecraftWorldDimension.openRegion(regionPosition).use { regionHandle ->
        regionHandle.withReadScope(chunkNbtDecoder, block)
    }
}

/** Synchronous live counterpart of [DimensionChunkReads], retaining the same complete decoder across headers. */
data class LiveDimensionChunkReads(
    val liveMinecraftWorldDimension: LiveMinecraftWorldDimension,
    val chunkNbtDecoder: ChunkNbtDecoder,
) {
    fun readChunk(chunkPosition: ChunkPosition): ChunkNbtDecodeResult? =
        liveMinecraftWorldDimension.openRegion(chunkPosition.regionPosition).use { liveRegionHandle ->
            liveRegionHandle.readChunk(chunkPosition, chunkNbtDecoder)
        }

    fun <R> withRegionReadScope(
        regionPosition: RegionPosition,
        block: DecodedChunkRegionReadScope.() -> R,
    ): R = liveMinecraftWorldDimension.openRegion(regionPosition).use { liveRegionHandle ->
        liveRegionHandle.withReadScope(chunkNbtDecoder, block)
    }
}

/** Entity reads whose dimension and all NBT interpretation inputs remain stable over the caller's chosen scope. */
data class DimensionEntityChunkReads(
    val minecraftWorldDimension: MinecraftWorldDimension,
    val entityChunkNbtDecoder: EntityChunkNbtDecoder,
) {
    suspend fun readChunk(chunkPosition: ChunkPosition): EntityChunkNbtDecodeResult? =
        minecraftWorldDimension.openEntityRegion(chunkPosition.regionPosition).use { entityRegionHandle ->
            entityRegionHandle.readChunk(chunkPosition, entityChunkNbtDecoder)
        }

    suspend fun <R> withRegionReadScope(
        regionPosition: RegionPosition,
        block: DecodedEntityRegionReadScope.() -> R,
    ): R = minecraftWorldDimension.openEntityRegion(regionPosition).use { entityRegionHandle ->
        entityRegionHandle.withReadScope(entityChunkNbtDecoder, block)
    }
}

data class LiveDimensionEntityChunkReads(
    val liveMinecraftWorldDimension: LiveMinecraftWorldDimension,
    val entityChunkNbtDecoder: EntityChunkNbtDecoder,
) {
    fun readChunk(chunkPosition: ChunkPosition): EntityChunkNbtDecodeResult? =
        liveMinecraftWorldDimension.openEntityRegion(chunkPosition.regionPosition).use { liveEntityRegionHandle ->
            liveEntityRegionHandle.readChunk(chunkPosition, entityChunkNbtDecoder)
        }

    fun <R> withRegionReadScope(
        regionPosition: RegionPosition,
        block: DecodedEntityRegionReadScope.() -> R,
    ): R = liveMinecraftWorldDimension.openEntityRegion(regionPosition).use { liveEntityRegionHandle ->
        liveEntityRegionHandle.withReadScope(entityChunkNbtDecoder, block)
    }
}

fun MinecraftWorldDimension.chunks(chunkNbtDecoder: ChunkNbtDecoder): DimensionChunkReads =
    DimensionChunkReads(this, chunkNbtDecoder)

fun MinecraftWorldDimension.chunks(chunkNbtDecoderContext: ChunkNbtDecoderContext): DimensionChunkReads =
    chunks(ChunkNbtDecoder(chunkNbtDecoderContext))

fun LiveMinecraftWorldDimension.chunks(chunkNbtDecoder: ChunkNbtDecoder): LiveDimensionChunkReads =
    LiveDimensionChunkReads(this, chunkNbtDecoder)

fun LiveMinecraftWorldDimension.chunks(chunkNbtDecoderContext: ChunkNbtDecoderContext): LiveDimensionChunkReads =
    chunks(ChunkNbtDecoder(chunkNbtDecoderContext))

fun MinecraftWorldDimension.entities(entityChunkNbtDecoder: EntityChunkNbtDecoder): DimensionEntityChunkReads =
    DimensionEntityChunkReads(this, entityChunkNbtDecoder)

fun MinecraftWorldDimension.entities(entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext): DimensionEntityChunkReads =
    entities(EntityChunkNbtDecoder(entityChunkNbtDecoderContext))

fun LiveMinecraftWorldDimension.entities(entityChunkNbtDecoder: EntityChunkNbtDecoder): LiveDimensionEntityChunkReads =
    LiveDimensionEntityChunkReads(this, entityChunkNbtDecoder)

fun LiveMinecraftWorldDimension.entities(entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext): LiveDimensionEntityChunkReads =
    entities(EntityChunkNbtDecoder(entityChunkNbtDecoderContext))
