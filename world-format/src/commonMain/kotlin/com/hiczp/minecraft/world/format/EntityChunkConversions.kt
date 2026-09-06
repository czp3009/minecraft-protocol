package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.io.Sink

fun CompressedChunk.toEntityChunk(
    entityChunkNbtDecoder: EntityChunkNbtDecoder,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): EntityChunkNbtDecodeResult = decodeCompressedValue(this, compressionRegistry, entityChunkNbtDecoder::decode)

fun CompressedChunk.toEntityChunk(
    entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): EntityChunkNbtDecodeResult = toEntityChunk(EntityChunkNbtDecoder(entityChunkNbtDecoderContext), compressionRegistry)

fun NbtDocument.toEntityChunk(entityChunkNbtDecoder: EntityChunkNbtDecoder): EntityChunkNbtDecodeResult =
    entityChunkNbtDecoder.decodeDocument(this)

fun NbtDocument.toEntityChunk(entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext): EntityChunkNbtDecodeResult =
    toEntityChunk(EntityChunkNbtDecoder(entityChunkNbtDecoderContext))

fun EntityChunk.toNbtDocument(entityChunkNbtEncoder: EntityChunkNbtEncoder): NbtDocument =
    entityChunkNbtEncoder.encodeDocument(this)

fun EntityChunk.toNbtDocument(entityChunkNbtEncoderContext: EntityChunkNbtEncoderContext): NbtDocument =
    toNbtDocument(EntityChunkNbtEncoder(entityChunkNbtEncoderContext))

fun EntityChunk.toCompressedChunk(
    entityChunkNbtEncoder: EntityChunkNbtEncoder,
    compression: Compression = Compression.ZLIB,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): CompressedChunk = encodeCompressedValue(compression, compressionRegistry) { entityChunkNbtEncoder.encode(this, it) }

fun EntityChunk.toCompressedChunk(
    entityChunkNbtEncoderContext: EntityChunkNbtEncoderContext,
    compression: Compression = Compression.ZLIB,
    compressionRegistry: CompressionRegistry = CompressionRegistry,
): CompressedChunk =
    toCompressedChunk(EntityChunkNbtEncoder(entityChunkNbtEncoderContext), compression, compressionRegistry)

fun EntityChunk.writeTo(sink: Sink, entityChunkNbtEncoder: EntityChunkNbtEncoder) =
    entityChunkNbtEncoder.encode(this, sink)

fun EntityChunk.writeTo(sink: Sink, entityChunkNbtEncoderContext: EntityChunkNbtEncoderContext) =
    writeTo(sink, EntityChunkNbtEncoder(entityChunkNbtEncoderContext))
