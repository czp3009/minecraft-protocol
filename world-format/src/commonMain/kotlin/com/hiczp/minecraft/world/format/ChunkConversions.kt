package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer

fun Source.readCompressedChunk(compression: Compression): CompressedChunk =
    CompressedChunk.readFromSource(this, compression)

fun CompressedChunkInput.toCompressedChunk(): CompressedChunk {
    if (this is CompressedChunk) return this
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readCompressedChunk(compression)
}

fun CompressedChunk.toNbtDocument(compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat()): NbtDocument =
    compressedNbtFormat.decodeDocument(this)

fun <T> CompressedChunk.decodeNbt(
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
    deserializationStrategy: DeserializationStrategy<T>,
): T = compressedNbtFormat.decode(this, deserializationStrategy)

inline fun <reified T> CompressedChunk.decodeNbt(
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
): T = decodeNbt(compressedNbtFormat, compressedNbtFormat.nbtFormat.serializersModule.serializer())

fun CompressedChunk.writeDecompressedTo(
    sink: Sink,
    compressionRegistry: CompressionRegistry = CompressionRegistry
): Long {
    val source = Buffer()
    writeTo(source)
    return compressionRegistry.decompressToSink(compression, source, sink)
}

fun NbtDocument.toCompressedChunk(
    compression: Compression = Compression.ZLIB,
    compressedNbtFormat: CompressedNbtFormat = CompressedNbtFormat(),
): CompressedChunk = compressedNbtFormat.encodeDocument(this, compression)

fun NbtDocument.toChunk(chunkNbtDecoder: ChunkNbtDecoder): ChunkNbtDecodeResult = chunkNbtDecoder.decodeDocument(this)

fun NbtDocument.toChunk(chunkNbtDecoderContext: ChunkNbtDecoderContext): ChunkNbtDecodeResult =
    toChunk(ChunkNbtDecoder(chunkNbtDecoderContext))

fun CompressedChunk.toChunk(
    chunkNbtDecoder: ChunkNbtDecoder,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): ChunkNbtDecodeResult = decodeCompressedValue(this, compressionRegistry, chunkNbtDecoder::decode)

fun CompressedChunk.toChunk(
    chunkNbtDecoderContext: ChunkNbtDecoderContext,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): ChunkNbtDecodeResult = toChunk(ChunkNbtDecoder(chunkNbtDecoderContext), compressionRegistry)

fun Chunk.toNbtDocument(chunkNbtEncoder: ChunkNbtEncoder): NbtDocument = chunkNbtEncoder.encodeDocument(this)

fun Chunk.toNbtDocument(chunkNbtEncoderContext: ChunkNbtEncoderContext): NbtDocument =
    toNbtDocument(ChunkNbtEncoder(chunkNbtEncoderContext))

fun Chunk.toCompressedChunk(
    chunkNbtEncoder: ChunkNbtEncoder,
    compression: Compression = Compression.ZLIB,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): CompressedChunk = encodeCompressedValue(compression, compressionRegistry) { chunkNbtEncoder.encode(this, it) }

fun Chunk.toCompressedChunk(
    chunkNbtEncoderContext: ChunkNbtEncoderContext,
    compression: Compression = Compression.ZLIB,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): CompressedChunk = toCompressedChunk(ChunkNbtEncoder(chunkNbtEncoderContext), compression, compressionRegistry)

fun Chunk.writeTo(sink: Sink, chunkNbtEncoder: ChunkNbtEncoder) = chunkNbtEncoder.encode(this, sink)

fun Chunk.writeTo(sink: Sink, chunkNbtEncoderContext: ChunkNbtEncoderContext) =
    writeTo(sink, ChunkNbtEncoder(chunkNbtEncoderContext))

internal fun <T> decodeCompressedValue(
    compressedChunk: CompressedChunk,
    compressionRegistry: CompressionRegistry,
    decode: (Source) -> T,
): T {
    val buffer = Buffer()
    compressedChunk.writeTo(buffer)
    return compressionRegistry.decodeCompressedNbt(buffer, compressedChunk.compression, decode)
}

internal fun encodeCompressedValue(
    compression: Compression,
    compressionRegistry: CompressionRegistry,
    encode: (Sink) -> Unit,
): CompressedChunk {
    val buffer = Buffer()
    compressionRegistry.compressingSink(compression, buffer).buffered().use(encode)
    return CompressedChunk.takeOwnership(compression, buffer.readByteArray())
}
