package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*

/** Convenience overloads construct a directional codec from complete caller-supplied input. */
suspend fun RegionHandle.readChunk(
    chunkPosition: ChunkPosition,
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
): ChunkNbtDecodeResult? = readChunk(chunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))

suspend fun RegionHandle.readChunk(
    localChunkPosition: LocalChunkPosition,
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
): ChunkNbtDecodeResult? = readChunk(localChunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))

fun LiveRegionHandle.readChunk(
    chunkPosition: ChunkPosition,
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
): ChunkNbtDecodeResult? = readChunk(chunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))

fun LiveRegionHandle.readChunk(
    localChunkPosition: LocalChunkPosition,
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
): ChunkNbtDecodeResult? = readChunk(localChunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))

suspend fun RegionHandle.writeChunk(
    chunk: Chunk,
    chunkNbtEncoderContext: ChunkNbtEncoderContext,
    compression: Compression = regionStorageConfiguration.writeCompression,
) = writeChunk(chunk, ChunkNbtEncoder(chunkNbtEncoderContext), compression)

suspend fun <R> RegionHandle.withReadScope(
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
    block: DecodedChunkRegionReadScope.() -> R,
): R = withReadScope(ChunkNbtDecoder(chunkNbtDecoderContext), block)

fun <R> LiveRegionHandle.withReadScope(
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
    block: DecodedChunkRegionReadScope.() -> R,
): R = withReadScope(ChunkNbtDecoder(chunkNbtDecoderContext), block)

suspend fun EntityRegionHandle.readChunk(
    chunkPosition: ChunkPosition,
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
): EntityChunkNbtDecodeResult? = readChunk(chunkPosition, EntityChunkNbtDecoder(entityChunkNbtDecoderContext))

suspend fun EntityRegionHandle.readChunk(
    localChunkPosition: LocalChunkPosition,
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
): EntityChunkNbtDecodeResult? = readChunk(localChunkPosition, EntityChunkNbtDecoder(entityChunkNbtDecoderContext))

fun LiveEntityRegionHandle.readChunk(
    chunkPosition: ChunkPosition,
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
): EntityChunkNbtDecodeResult? = readChunk(chunkPosition, EntityChunkNbtDecoder(entityChunkNbtDecoderContext))

fun LiveEntityRegionHandle.readChunk(
    localChunkPosition: LocalChunkPosition,
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
): EntityChunkNbtDecodeResult? = readChunk(localChunkPosition, EntityChunkNbtDecoder(entityChunkNbtDecoderContext))

suspend fun EntityRegionHandle.writeChunk(
    entityChunk: EntityChunk,
    entityChunkNbtEncoderContext: EntityChunkNbtEncoderContext,
    compression: Compression = regionStorageConfiguration.writeCompression,
) = writeChunk(entityChunk, EntityChunkNbtEncoder(entityChunkNbtEncoderContext), compression)

suspend fun <R> EntityRegionHandle.withReadScope(
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
    block: DecodedEntityRegionReadScope.() -> R,
): R = withReadScope(EntityChunkNbtDecoder(entityChunkNbtDecoderContext), block)

fun <R> LiveEntityRegionHandle.withReadScope(
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
    block: DecodedEntityRegionReadScope.() -> R,
): R = withReadScope(EntityChunkNbtDecoder(entityChunkNbtDecoderContext), block)

suspend fun PoiRegionHandle.writeChunk(
    poiChunk: PoiChunk,
    poiChunkNbtEncoderContext: PoiChunkNbtEncoderContext,
    compression: Compression = regionStorageConfiguration.writeCompression,
) = writeChunk(poiChunk, PoiChunkNbtEncoder(poiChunkNbtEncoderContext), compression)

fun RegionFileStore.readChunk(
    chunkPosition: ChunkPosition,
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
): ChunkNbtDecodeResult? = readChunk(chunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))

fun RegionFileStore.readChunk(
    regionPosition: RegionPosition,
    localChunkPosition: LocalChunkPosition,
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
): ChunkNbtDecodeResult? = readChunk(regionPosition, localChunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))

fun RegionFileStore.writeChunk(
    chunk: Chunk,
    chunkNbtEncoderContext: ChunkNbtEncoderContext,
    compression: Compression = regionStorageConfiguration.writeCompression,
) = writeChunk(chunk, ChunkNbtEncoder(chunkNbtEncoderContext), compression)

fun <R> RegionFileStore.withReadScope(
    regionPosition: RegionPosition,
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
    block: DecodedChunkRegionReadScope.() -> R,
): R = withReadScope(regionPosition, ChunkNbtDecoder(chunkNbtDecoderContext), block)

fun <R> RegionFileStore.withEntityReadScope(
    regionPosition: RegionPosition,
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
    block: DecodedEntityRegionReadScope.() -> R,
): R = withEntityReadScope(regionPosition, EntityChunkNbtDecoder(entityChunkNbtDecoderContext), block)
