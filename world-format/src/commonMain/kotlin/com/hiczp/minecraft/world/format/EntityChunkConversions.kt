package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink

/** Decodes this positionless compressed content as a semantic Entity Chunk. */
fun <E : Any> CompressedChunk.toEntityChunk(
    position: ChunkPosition,
    codec: EntityChunkNbtCodec<E>,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): EntityChunk<E> = toNbtDocument(format).toEntityChunk(position, codec)

/** Projects this generic NBT tree into a semantic Entity Chunk. */
fun <E : Any> NbtDocument.toEntityChunk(
    position: ChunkPosition,
    codec: EntityChunkNbtCodec<E>,
): EntityChunk<E> =
    codec.decodeDocument(this, position)

/** Converts this semantic Entity Chunk to a generic NBT tree at [position]. */
fun <E : Any> EntityChunk<E>.toNbtDocument(
    position: ChunkPosition,
    codec: EntityChunkNbtCodec<E>,
): NbtDocument =
    codec.encodeDocument(this, position)

/** Converts this semantic Entity Chunk directly to detached compressed content. */
fun <E : Any> EntityChunk<E>.toCompressedChunk(
    position: ChunkPosition,
    codec: EntityChunkNbtCodec<E>,
    compression: Compression = Compression.ZLIB,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): CompressedChunk = toNbtDocument(position, codec).toCompressedChunk(compression, format)

/** Writes this semantic Entity Chunk as complete unnamed-root NBT without closing [sink]. */
fun <E : Any> EntityChunk<E>.writeTo(sink: Sink, position: ChunkPosition, codec: EntityChunkNbtCodec<E>) {
    codec.encodeToSink(this, position, sink)
}
