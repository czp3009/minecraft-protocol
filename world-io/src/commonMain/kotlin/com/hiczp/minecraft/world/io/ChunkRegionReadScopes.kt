package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.*

/** An unbound Chunk view: each read supplies that conversion's complete decoder. */
class RegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun readChunk(localChunkPosition: LocalChunkPosition, chunkNbtDecoder: ChunkNbtDecoder): ChunkNbtDecodeResult? =
        withChunkNbtSource(localChunkPosition) { _, source -> chunkNbtDecoder.decodeFromOkio(source) }

    fun readChunk(chunkPosition: ChunkPosition, chunkNbtDecoder: ChunkNbtDecoder): ChunkNbtDecodeResult? =
        readChunk(regionPosition.local(chunkPosition), chunkNbtDecoder)

    fun readChunk(
        localChunkPosition: LocalChunkPosition,
        chunkNbtDecoderContext: ChunkNbtDecoderContext
    ): ChunkNbtDecodeResult? =
        readChunk(localChunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))

    fun readChunk(chunkPosition: ChunkPosition, chunkNbtDecoderContext: ChunkNbtDecoderContext): ChunkNbtDecodeResult? =
        readChunk(chunkPosition, ChunkNbtDecoder(chunkNbtDecoderContext))
}

/** A callback-bound Header view borrowing a decoder already selected by its caller. */
class DecodedChunkRegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
    val chunkNbtDecoder: ChunkNbtDecoder,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun readChunk(localChunkPosition: LocalChunkPosition): ChunkNbtDecodeResult? =
        withChunkNbtSource(localChunkPosition) { _, source -> chunkNbtDecoder.decodeFromOkio(source) }

    fun readChunk(chunkPosition: ChunkPosition): ChunkNbtDecodeResult? = readChunk(regionPosition.local(chunkPosition))
}

class EntityRegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun readChunk(
        localChunkPosition: LocalChunkPosition,
        entityChunkNbtDecoder: EntityChunkNbtDecoder
    ): EntityChunkNbtDecodeResult? =
        withChunkNbtSource(localChunkPosition) { _, source -> entityChunkNbtDecoder.decodeFromOkio(source) }

    fun readChunk(
        chunkPosition: ChunkPosition,
        entityChunkNbtDecoder: EntityChunkNbtDecoder
    ): EntityChunkNbtDecodeResult? =
        readChunk(regionPosition.local(chunkPosition), entityChunkNbtDecoder)

    fun readChunk(
        localChunkPosition: LocalChunkPosition,
        entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext
    ): EntityChunkNbtDecodeResult? =
        readChunk(localChunkPosition, EntityChunkNbtDecoder(entityChunkNbtDecoderContext))

    fun readChunk(
        chunkPosition: ChunkPosition,
        entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext
    ): EntityChunkNbtDecodeResult? =
        readChunk(chunkPosition, EntityChunkNbtDecoder(entityChunkNbtDecoderContext))
}

class DecodedEntityRegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
    val entityChunkNbtDecoder: EntityChunkNbtDecoder,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun readChunk(localChunkPosition: LocalChunkPosition): EntityChunkNbtDecodeResult? =
        withChunkNbtSource(localChunkPosition) { _, source -> entityChunkNbtDecoder.decodeFromOkio(source) }

    fun readChunk(chunkPosition: ChunkPosition): EntityChunkNbtDecodeResult? =
        readChunk(regionPosition.local(chunkPosition))
}

/** The complete POI decoder context also selects the slot; no redundant expected position is accepted. */
class PoiRegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun readChunk(poiChunkNbtDecoder: PoiChunkNbtDecoder): PoiChunkNbtDecodeResult? =
        withChunkNbtSource(poiChunkNbtDecoder.poiChunkNbtDecoderContext.chunkPosition) { _, source ->
            poiChunkNbtDecoder.decodeFromOkio(source)
        }

    fun readChunk(poiChunkNbtDecoderContext: PoiChunkNbtDecoderContext): PoiChunkNbtDecodeResult? =
        readChunk(PoiChunkNbtDecoder(poiChunkNbtDecoderContext))
}

/** A factory supplies each selected position's complete POI decoder at the individual conversion. */
class DecodedPoiRegionReadScope internal constructor(
    regionReadScopeCore: RegionReadScopeCore,
    chunkNbtFormat: CompressedNbtFormat,
    private val poiChunkNbtDecoder: (ChunkPosition) -> PoiChunkNbtDecoder,
) : AnvilRegionReadScope(regionReadScopeCore, chunkNbtFormat) {
    fun readChunk(localChunkPosition: LocalChunkPosition): PoiChunkNbtDecodeResult? =
        withChunkNbtSource(localChunkPosition) { _, source ->
            poiChunkNbtDecoder(regionPosition.chunk(localChunkPosition)).decodeFromOkio(source)
        }

    fun readChunk(chunkPosition: ChunkPosition): PoiChunkNbtDecodeResult? =
        readChunk(regionPosition.local(chunkPosition))
}
