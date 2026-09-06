package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSink
import kotlinx.io.okio.asOkioSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.IOException as KotlinxIOException
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

/** Narrow adapters at calls into the filesystem-independent kotlinx-io format modules. */
internal fun NbtFormat.decodeDocumentFromOkio(source: BufferedSource): NbtDocument =
    decodeFromOkio(source, this::decodeDocumentFromSource)

internal fun <T> NbtFormat.decodeFromOkio(
    source: BufferedSource,
    deserializationStrategy: DeserializationStrategy<T>,
): T = decodeFromOkio(source) { kotlinxSource ->
    decodeFromSource(deserializationStrategy, kotlinxSource)
}

internal inline fun <reified T> NbtFormat.decodeFromOkio(source: BufferedSource): T =
    decodeFromOkio(source, serializersModule.serializer())

internal fun NbtFormat.encodeDocumentToOkio(nbtDocument: NbtDocument, sink: BufferedSink) {
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        encodeDocumentToSink(nbtDocument, kotlinxSink)
        kotlinxSink.emit()
    }
}

internal fun <T> NbtFormat.encodeToOkio(
    value: T,
    sink: BufferedSink,
    serializationStrategy: SerializationStrategy<T>,
) {
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        encodeToSink(serializationStrategy, value, kotlinxSink)
        kotlinxSink.emit()
    }
}

internal inline fun <reified T> NbtFormat.encodeToOkio(value: T, sink: BufferedSink) =
    encodeToOkio(value, sink, serializersModule.serializer())

internal fun ChunkNbtDecoder.decodeFromOkio(
    source: BufferedSource,
): ChunkNbtDecodeResult = decodeFromOkio(source) { kotlinxSource ->
    decode(kotlinxSource)
}

internal fun EntityChunkNbtDecoder.decodeFromOkio(
    source: BufferedSource,
): EntityChunkNbtDecodeResult = decodeFromOkio(source) { kotlinxSource ->
    decode(kotlinxSource)
}

internal fun PoiChunkNbtDecoder.decodeFromOkio(
    source: BufferedSource,
): PoiChunkNbtDecodeResult = decodeFromOkio(source) { kotlinxSource ->
    decode(kotlinxSource)
}

internal fun BufferedSource.readCompressedChunkFromOkio(compression: Compression): CompressedChunk =
    withOkioIoFailures {
        CompressedChunk.readFromSource(asKotlinxIoRawSource().buffered(), compression)
    }

internal fun CompressedChunkInput.writeToOkio(sink: BufferedSink) {
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        writeTo(kotlinxSink)
        kotlinxSink.emit()
    }
}

internal fun encodeCompressedChunkFromOkio(
    compressionRegistry: CompressionRegistry,
    compression: Compression,
    encode: (BufferedSink) -> Unit,
): CompressedChunk = encodeCompressedChunk(compressionRegistry, compression) { sink ->
    useResource(sink.asOkioSink().buffer(), { it.close() }, encode)
}

internal fun CompressedNbtFormat.encodeDocumentFromOkio(
    nbtDocument: NbtDocument,
    compression: Compression,
): CompressedChunk = encodeCompressedChunk(compressionRegistry, compression) { sink ->
    nbtFormat.encodeDocumentToSink(nbtDocument, sink)
}

internal fun <T> CompressedNbtFormat.encodeFromOkio(
    value: T,
    compression: Compression,
    serializationStrategy: SerializationStrategy<T>,
): CompressedChunk = encodeCompressedChunk(compressionRegistry, compression) { sink ->
    nbtFormat.encodeToSink(serializationStrategy, value, sink)
}

internal inline fun <reified T> CompressedNbtFormat.encodeFromOkio(
    value: T,
    compression: Compression,
): CompressedChunk = encodeFromOkio(value, compression, nbtFormat.serializersModule.serializer())

internal fun ChunkNbtEncoder.encodeFromOkio(
    chunk: Chunk,
    compressionRegistry: CompressionRegistry,
    compression: Compression,
): CompressedChunk = encodeCompressedChunk(compressionRegistry, compression) { sink ->
    encode(chunk, sink)
}

internal fun EntityChunkNbtEncoder.encodeFromOkio(
    entityChunk: EntityChunk,
    compressionRegistry: CompressionRegistry,
    compression: Compression,
): CompressedChunk = encodeCompressedChunk(compressionRegistry, compression) { sink ->
    encode(entityChunk, sink)
}

internal fun PoiChunkNbtEncoder.encodeFromOkio(
    poiChunk: PoiChunk,
    compressionRegistry: CompressionRegistry,
    compression: Compression,
): CompressedChunk = encodeCompressedChunk(compressionRegistry, compression) { sink ->
    encode(poiChunk, sink)
}

private fun encodeCompressedChunk(
    compressionRegistry: CompressionRegistry,
    compression: Compression,
    encode: (KotlinxSink) -> Unit,
): CompressedChunk = withOkioIoFailures {
    val compressed = KotlinxBuffer()
    useResource(compressionRegistry.compressingSink(compression, compressed).buffered(), { it.close() }) { sink ->
        encode(sink)
    }
    CompressedChunk.readFromSource(compressed, compression)
}

private fun <T> decodeFromOkio(
    source: BufferedSource,
    decode: (KotlinxSource) -> T,
): T {
    val kotlinxSource = source.asKotlinxIoRawSource().buffered()
    return withOkioIoFailures {
        val value = decode(kotlinxSource)
        if (!kotlinxSource.exhausted()) throw NbtDecodingException("NBT payload has trailing bytes")
        value
    }
}

/**
 * Completes a terminal lower-format call with the same exception mapping as an adapted stream.
 *
 * The official adapter performs the actual conversion. The failure-only trampoline is needed
 * because a parser or serializer returns a value rather than a kotlinx-io stream that can be
 * adapted back to Okio at the public boundary.
 */
internal fun <T> withOkioIoFailures(block: () -> T): T = try {
    block()
} catch (failure: KotlinxIOException) {
    rethrowKotlinxIoFailureThroughOfficialAdapter(failure)
}

private fun rethrowKotlinxIoFailureThroughOfficialAdapter(failure: KotlinxIOException): Nothing {
    val source = object : RawSource {
        override fun readAtMostTo(sink: KotlinxBuffer, byteCount: Long): Long = throw failure

        override fun close() = Unit
    }.asOkioSource()
    source.read(Buffer(), 1L)
    error("The kotlinx-io failure source returned instead of throwing")
}
