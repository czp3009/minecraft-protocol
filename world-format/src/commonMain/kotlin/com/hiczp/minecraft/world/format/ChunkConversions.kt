package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer

/** Reads all remaining compressed bytes as a detached [CompressedChunk] without closing this source. */
fun Source.readCompressedChunk(compression: Compression): CompressedChunk =
    CompressedChunk.readFromSource(this, compression)

/** Materializes this input as a detached [CompressedChunk]. */
fun CompressedChunkInput.toCompressedChunk(): CompressedChunk {
    if (this is CompressedChunk) return this

    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readCompressedChunk(compression)
}

/** Decodes this compressed Chunk as a complete generic NBT tree. */
fun CompressedChunk.toNbtDocument(compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat()): NbtDocument =
    compressedNbtFormat.decodeDocument(this)

/** Decodes this compressed Chunk with a caller-selected NBT serializer. */
fun <T> CompressedChunk.decodeNbt(
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    deserializationStrategy: DeserializationStrategy<T>,
): T = compressedNbtFormat.decode(this, deserializationStrategy)

/** Decodes this compressed Chunk with the serializer selected from [compressedNbtFormat]. */
inline fun <reified T> CompressedChunk.decodeNbt(
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
): T = decodeNbt(compressedNbtFormat, compressedNbtFormat.nbtFormat.serializersModule.serializer())

/** Decodes this compressed content using the Chunk position carried by its NBT root. */
fun <B : Any, M : Any> CompressedChunk.toChunk(
    chunkNbtCodec: ChunkNbtCodec<B, M>,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(nbtFormat = chunkNbtCodec.nbtFormat),
): Chunk<B, M> = toNbtDocument(compressedNbtFormat).toChunk(chunkNbtCodec)

/** Writes the complete decompressed Chunk NBT bytes without closing [sink]. */
fun CompressedChunk.writeDecompressedTo(
    sink: Sink,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): Long {
    val source = Buffer()
    writeTo(source)
    return compressionRegistry.decompressToSink(compression, source, sink)
}

/** Projects this generic NBT tree into a semantic Chunk using its stored position. */
fun <B : Any, M : Any> NbtDocument.toChunk(
    chunkNbtCodec: ChunkNbtCodec<B, M>,
): Chunk<B, M> = chunkNbtCodec.decodeDocument(this)

/** Compresses this generic NBT tree as detached Chunk content. */
fun NbtDocument.toCompressedChunk(
    compression: Compression = Compression.ZLIB,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
): CompressedChunk = compressedNbtFormat.encodeDocument(this, compression)

/** Converts this semantic Chunk to a generic NBT tree at its retained position. */
fun <B : Any, M : Any> Chunk<B, M>.toNbtDocument(
    chunkNbtCodec: ChunkNbtCodec<B, M>,
): NbtDocument = chunkNbtCodec.encodeDocument(this)

/** Converts this semantic Chunk directly to detached compressed content. */
fun <B : Any, M : Any> Chunk<B, M>.toCompressedChunk(
    chunkNbtCodec: ChunkNbtCodec<B, M>,
    compression: Compression = Compression.ZLIB,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(nbtFormat = chunkNbtCodec.nbtFormat),
): CompressedChunk = toNbtDocument(chunkNbtCodec).toCompressedChunk(compression, compressedNbtFormat)

/** Writes this semantic Chunk as complete unnamed-root NBT without closing [sink]. */
fun <B : Any, M : Any> Chunk<B, M>.writeTo(
    sink: Sink,
    chunkNbtCodec: ChunkNbtCodec<B, M>,
) {
    chunkNbtCodec.encodeToSink(this, sink)
}
