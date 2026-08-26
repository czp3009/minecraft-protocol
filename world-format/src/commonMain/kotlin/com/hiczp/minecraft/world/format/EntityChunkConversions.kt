package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink

/** Decodes this compressed content using the Entity Chunk position carried by its NBT root. */
fun <E : Any> CompressedChunk.toEntityChunk(
    entityChunkNbtCodec: EntityChunkNbtCodec<E>,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(nbtFormat = entityChunkNbtCodec.nbtFormat),
): EntityChunk<E> = toNbtDocument(compressedNbtFormat).toEntityChunk(entityChunkNbtCodec)

/** Decodes this compressed content while validating its NBT position against [chunkPosition]. */
fun <E : Any> CompressedChunk.toEntityChunk(
    chunkPosition: ChunkPosition,
    entityChunkNbtCodec: EntityChunkNbtCodec<E>,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(nbtFormat = entityChunkNbtCodec.nbtFormat),
): EntityChunk<E> = toNbtDocument(compressedNbtFormat).toEntityChunk(chunkPosition, entityChunkNbtCodec)

/** Projects this generic NBT tree into a semantic Entity Chunk using its stored position. */
fun <E : Any> NbtDocument.toEntityChunk(entityChunkNbtCodec: EntityChunkNbtCodec<E>): EntityChunk<E> =
    entityChunkNbtCodec.decodeDocument(this)

/** Projects this generic NBT tree into a semantic Entity Chunk while validating [chunkPosition]. */
fun <E : Any> NbtDocument.toEntityChunk(
    chunkPosition: ChunkPosition,
    entityChunkNbtCodec: EntityChunkNbtCodec<E>,
): EntityChunk<E> =
    entityChunkNbtCodec.decodeDocument(this, chunkPosition)

/** Converts this semantic Entity Chunk to a generic NBT tree at its retained position. */
fun <E : Any> EntityChunk<E>.toNbtDocument(
    entityChunkNbtCodec: EntityChunkNbtCodec<E>,
): NbtDocument =
    entityChunkNbtCodec.encodeDocument(this)

/** Converts this semantic Entity Chunk directly to detached compressed content. */
fun <E : Any> EntityChunk<E>.toCompressedChunk(
    entityChunkNbtCodec: EntityChunkNbtCodec<E>,
    compression: Compression = Compression.ZLIB,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(nbtFormat = entityChunkNbtCodec.nbtFormat),
): CompressedChunk = toNbtDocument(entityChunkNbtCodec).toCompressedChunk(compression, compressedNbtFormat)

/** Writes this semantic Entity Chunk as complete unnamed-root NBT without closing [sink]. */
fun <E : Any> EntityChunk<E>.writeTo(sink: Sink, entityChunkNbtCodec: EntityChunkNbtCodec<E>) {
    entityChunkNbtCodec.encodeToSink(this, sink)
}
