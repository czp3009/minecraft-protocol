package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink

/** Decodes compressed POI content while applying the Region entry's absolute Chunk position. */
fun CompressedChunk.toPoiChunk(
    chunkPosition: ChunkPosition,
    poiChunkNbtCodec: PoiChunkNbtCodec = PoiChunkNbtCodec(),
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(nbtFormat = poiChunkNbtCodec.nbtFormat),
): PoiChunk = toNbtDocument(compressedNbtFormat).toPoiChunk(chunkPosition, poiChunkNbtCodec)

/** Projects a generic NBT tree into a semantic POI Chunk at [chunkPosition]. */
fun NbtDocument.toPoiChunk(
    chunkPosition: ChunkPosition,
    poiChunkNbtCodec: PoiChunkNbtCodec = PoiChunkNbtCodec(),
): PoiChunk = poiChunkNbtCodec.decodeDocument(this, chunkPosition)

/** Converts this semantic POI Chunk to a generic NBT tree. */
fun PoiChunk.toNbtDocument(poiChunkNbtCodec: PoiChunkNbtCodec = PoiChunkNbtCodec()): NbtDocument =
    poiChunkNbtCodec.encodeDocument(this)

/** Converts this semantic POI Chunk directly to detached compressed content. */
fun PoiChunk.toCompressedChunk(
    poiChunkNbtCodec: PoiChunkNbtCodec = PoiChunkNbtCodec(),
    compression: Compression = Compression.ZLIB,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(nbtFormat = poiChunkNbtCodec.nbtFormat),
): CompressedChunk = toNbtDocument(poiChunkNbtCodec).toCompressedChunk(compression, compressedNbtFormat)

/** Writes this semantic POI Chunk as complete unnamed-root NBT without closing [sink]. */
fun PoiChunk.writeTo(sink: Sink, poiChunkNbtCodec: PoiChunkNbtCodec = PoiChunkNbtCodec()) {
    poiChunkNbtCodec.encodeToSink(this, sink)
}
