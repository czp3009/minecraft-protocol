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
    require(buffer.size == compressedByteCount) {
        "Compressed Chunk input declared $compressedByteCount bytes but wrote ${buffer.size}"
    }
    return buffer.readCompressedChunk(compression)
}

/** Decodes this compressed Chunk as a complete generic NBT tree. */
fun CompressedChunk.toNbtDocument(format: CompressedNbtFormat = CompressedNbtFormat()): NbtDocument =
    format.decodeDocument(this)

/** Decodes this compressed Chunk with a caller-selected NBT serializer. */
fun <T> CompressedChunk.decodeNbt(
    deserializer: DeserializationStrategy<T>,
    format: CompressedNbtFormat = CompressedNbtFormat(),
): T = format.decode(deserializer, this)

/** Decodes this compressed Chunk with the serializer selected from [format]. */
inline fun <reified T> CompressedChunk.decodeNbt(
    format: CompressedNbtFormat = CompressedNbtFormat(),
): T = decodeNbt(format.nbt.serializersModule.serializer(), format)

/** Decodes this compressed content using the Chunk position carried by its NBT root. */
fun <B : Any, M : Any> CompressedChunk.toChunk(
    codec: ChunkNbtCodec<B, M>,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): Chunk<B, M> = toNbtDocument(format).toChunk(codec)

/**
 * Decodes this compressed content and validates its NBT position against [position].
 *
 * [codec] supplies the selected-release layout and caller-owned block-state and biome registries. Pass [format] when
 * custom compression is registered.
 */
fun <B : Any, M : Any> CompressedChunk.toChunk(
    position: ChunkPosition,
    codec: ChunkNbtCodec<B, M>,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): Chunk<B, M> = toNbtDocument(format).toChunk(position, codec)

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
    codec: ChunkNbtCodec<B, M>,
): Chunk<B, M> = codec.decodeDocument(this)

/** Projects this generic NBT tree into a semantic Chunk while validating [position]. */
fun <B : Any, M : Any> NbtDocument.toChunk(
    position: ChunkPosition,
    codec: ChunkNbtCodec<B, M>,
): Chunk<B, M> = codec.decodeDocument(this, position)

/** Compresses this generic NBT tree as detached Chunk content. */
fun NbtDocument.toCompressedChunk(
    compression: Compression = Compression.ZLIB,
    format: CompressedNbtFormat = CompressedNbtFormat(),
): CompressedChunk = format.encodeDocument(this, compression)

/** Converts this semantic Chunk to a generic NBT tree at its retained position. */
fun <B : Any, M : Any> Chunk<B, M>.toNbtDocument(
    codec: ChunkNbtCodec<B, M>,
): NbtDocument = codec.encodeDocument(this)

/** Converts this semantic Chunk directly to detached compressed content. */
fun <B : Any, M : Any> Chunk<B, M>.toCompressedChunk(
    codec: ChunkNbtCodec<B, M>,
    compression: Compression = Compression.ZLIB,
    format: CompressedNbtFormat = CompressedNbtFormat(nbt = codec.nbt),
): CompressedChunk = toNbtDocument(codec).toCompressedChunk(compression, format)

/** Writes this semantic Chunk as complete unnamed-root NBT without closing [sink]. */
fun <B : Any, M : Any> Chunk<B, M>.writeTo(
    sink: Sink,
    codec: ChunkNbtCodec<B, M>,
) {
    codec.encodeToSink(this, sink)
}
