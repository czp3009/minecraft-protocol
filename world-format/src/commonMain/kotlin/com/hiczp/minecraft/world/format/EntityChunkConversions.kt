package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink

/** Decodes this compressed content using the Entity Chunk position carried by its NBT root. */
fun <E : Any> CompressedChunk.toEntityChunk(
    codec: EntityChunkNbtCodec<E>,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): EntityChunk<E> = toNbtDocument(format).toEntityChunk(codec)

/** Decodes this compressed content while validating its NBT position against [position]. */
fun <E : Any> CompressedChunk.toEntityChunk(
    position: ChunkPosition,
    codec: EntityChunkNbtCodec<E>,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): EntityChunk<E> = toNbtDocument(format).toEntityChunk(position, codec)

/** Projects this generic NBT tree into a semantic Entity Chunk using its stored position. */
fun <E : Any> NbtDocument.toEntityChunk(codec: EntityChunkNbtCodec<E>): EntityChunk<E> =
    codec.decodeDocument(this)

/** Projects this generic NBT tree into a semantic Entity Chunk while validating [position]. */
fun <E : Any> NbtDocument.toEntityChunk(
    position: ChunkPosition,
    codec: EntityChunkNbtCodec<E>,
): EntityChunk<E> =
    codec.decodeDocument(this, position)

/** Converts this semantic Entity Chunk to a generic NBT tree at its retained position. */
fun <E : Any> EntityChunk<E>.toNbtDocument(
    codec: EntityChunkNbtCodec<E>,
): NbtDocument =
    codec.encodeDocument(this)

/** Converts this semantic Entity Chunk directly to detached compressed content. */
fun <E : Any> EntityChunk<E>.toCompressedChunk(
    codec: EntityChunkNbtCodec<E>,
    compression: Compression = Compression.ZLIB,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): CompressedChunk = toNbtDocument(codec).toCompressedChunk(compression, format)

/** Writes this semantic Entity Chunk as complete unnamed-root NBT without closing [sink]. */
fun <E : Any> EntityChunk<E>.writeTo(sink: Sink, codec: EntityChunkNbtCodec<E>) {
    codec.encodeToSink(this, sink)
}
