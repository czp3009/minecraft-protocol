package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink

fun CompressedChunk.toPoiChunk(
    poiChunkNbtDecoder: PoiChunkNbtDecoder,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): PoiChunkNbtDecodeResult = decodeCompressedValue(this, compressionRegistry, poiChunkNbtDecoder::decode)

fun CompressedChunk.toPoiChunk(
    poiChunkNbtDecoderContext: PoiChunkNbtDecoderContext,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): PoiChunkNbtDecodeResult = toPoiChunk(PoiChunkNbtDecoder(poiChunkNbtDecoderContext), compressionRegistry)

fun NbtDocument.toPoiChunk(poiChunkNbtDecoder: PoiChunkNbtDecoder): PoiChunkNbtDecodeResult =
    poiChunkNbtDecoder.decodeDocument(this)

fun NbtDocument.toPoiChunk(poiChunkNbtDecoderContext: PoiChunkNbtDecoderContext): PoiChunkNbtDecodeResult =
    toPoiChunk(PoiChunkNbtDecoder(poiChunkNbtDecoderContext))

fun PoiChunk.toNbtDocument(poiChunkNbtEncoder: PoiChunkNbtEncoder): NbtDocument =
    poiChunkNbtEncoder.encodeDocument(this)

fun PoiChunk.toNbtDocument(poiChunkNbtEncoderContext: PoiChunkNbtEncoderContext): NbtDocument =
    toNbtDocument(PoiChunkNbtEncoder(poiChunkNbtEncoderContext))

fun PoiChunk.toCompressedChunk(
    poiChunkNbtEncoder: PoiChunkNbtEncoder,
    compression: Compression = Compression.ZLIB,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): CompressedChunk = encodeCompressedValue(compression, compressionRegistry) { poiChunkNbtEncoder.encode(this, it) }

fun PoiChunk.toCompressedChunk(
    poiChunkNbtEncoderContext: PoiChunkNbtEncoderContext,
    compression: Compression = Compression.ZLIB,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): CompressedChunk = toCompressedChunk(PoiChunkNbtEncoder(poiChunkNbtEncoderContext), compression, compressionRegistry)

fun PoiChunk.writeTo(sink: Sink, poiChunkNbtEncoder: PoiChunkNbtEncoder) = poiChunkNbtEncoder.encode(this, sink)

fun PoiChunk.writeTo(sink: Sink, poiChunkNbtEncoderContext: PoiChunkNbtEncoderContext) =
    writeTo(sink, PoiChunkNbtEncoder(poiChunkNbtEncoderContext))
