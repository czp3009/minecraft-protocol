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

/** Narrow adapters at calls into the filesystem-independent kotlinx-io format modules. */
internal fun NbtFormat.decodeDocumentFromOkio(source: BufferedSource): NbtDocument =
    decodeFromOkio(source, this::decodeDocumentFromSource)

internal fun <T> NbtFormat.decodeFromOkio(
    deserializationStrategy: DeserializationStrategy<T>,
    source: BufferedSource,
): T = decodeFromOkio(source) { kotlinxSource ->
    decodeFromSource(deserializationStrategy, kotlinxSource)
}

internal inline fun <reified T> NbtFormat.decodeFromOkio(source: BufferedSource): T =
    decodeFromOkio(serializersModule.serializer(), source)

internal fun NbtFormat.encodeDocumentToOkio(nbtDocument: NbtDocument, sink: BufferedSink) {
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        encodeDocumentToSink(nbtDocument, kotlinxSink)
        kotlinxSink.emit()
    }
}

internal fun <T> NbtFormat.encodeToOkio(
    serializationStrategy: SerializationStrategy<T>,
    value: T,
    sink: BufferedSink,
) {
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        encodeToSink(serializationStrategy, value, kotlinxSink)
        kotlinxSink.emit()
    }
}

internal inline fun <reified T> NbtFormat.encodeToOkio(value: T, sink: BufferedSink) =
    encodeToOkio(serializersModule.serializer(), value, sink)

internal fun <B : Any, M : Any> ChunkNbtCodec<B, M>.decodeFromOkio(
    source: BufferedSource,
    expectedPosition: ChunkPosition,
): Chunk<B, M> = decodeFromOkio(source) { kotlinxSource ->
    decodeFromSource(kotlinxSource, expectedPosition)
}

internal fun <E : Any> EntityChunkNbtCodec<E>.decodeFromOkio(
    source: BufferedSource,
    expectedPosition: ChunkPosition,
): EntityChunk<E> = decodeFromOkio(source) { kotlinxSource ->
    decodeFromSource(kotlinxSource, expectedPosition)
}

internal fun PoiChunkNbtCodec.decodeFromOkio(
    source: BufferedSource,
    expectedPosition: ChunkPosition,
): PoiChunk = decodeFromOkio(source) { kotlinxSource ->
    decodeFromSource(kotlinxSource, expectedPosition)
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
    compressedNbtFormat: CompressedNbtFormat,
    compression: Compression,
    encode: (BufferedSink) -> Unit,
): CompressedChunk {
    val compressed = kotlinx.io.Buffer()
    val compressedSink = withOkioIoFailures {
        compressedNbtFormat.compressionRegistry.compressingSink(compression, compressed)
    }.asOkioSink().buffer()
    useResource(compressedSink, { it.close() }) { sink ->
        encode(sink)
    }
    return withOkioIoFailures { CompressedChunk.readFromSource(compressed, compression) }
}

internal fun CompressedNbtFormat.encodeDocumentFromOkio(
    nbtDocument: NbtDocument,
    compression: Compression,
): CompressedChunk = encodeCompressedChunkFromOkio(this, compression) { sink ->
    nbtFormat.encodeDocumentToOkio(nbtDocument, sink)
}

internal fun <T> CompressedNbtFormat.encodeFromOkio(
    serializationStrategy: SerializationStrategy<T>,
    value: T,
    compression: Compression,
): CompressedChunk = encodeCompressedChunkFromOkio(this, compression) { sink ->
    nbtFormat.encodeToOkio(serializationStrategy, value, sink)
}

internal inline fun <reified T> CompressedNbtFormat.encodeFromOkio(
    value: T,
    compression: Compression,
): CompressedChunk = encodeFromOkio(nbtFormat.serializersModule.serializer(), value, compression)

internal fun <B : Any, M : Any> ChunkNbtCodec<B, M>.encodeFromOkio(
    chunk: Chunk<B, M>,
    compressedNbtFormat: CompressedNbtFormat,
    compression: Compression,
): CompressedChunk = encodeCompressedChunkFromOkio(compressedNbtFormat, compression) { sink ->
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        encodeToSink(chunk, kotlinxSink)
        kotlinxSink.emit()
    }
}

internal fun <E : Any> EntityChunkNbtCodec<E>.encodeFromOkio(
    entityChunk: EntityChunk<E>,
    compressedNbtFormat: CompressedNbtFormat,
    compression: Compression,
): CompressedChunk = encodeCompressedChunkFromOkio(compressedNbtFormat, compression) { sink ->
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        encodeToSink(entityChunk, kotlinxSink)
        kotlinxSink.emit()
    }
}

internal fun PoiChunkNbtCodec.encodeFromOkio(
    poiChunk: PoiChunk,
    compressedNbtFormat: CompressedNbtFormat,
    compression: Compression,
): CompressedChunk = encodeCompressedChunkFromOkio(compressedNbtFormat, compression) { sink ->
    val kotlinxSink = sink.asKotlinxIoRawSink().buffered()
    withOkioIoFailures {
        encodeToSink(poiChunk, kotlinxSink)
        kotlinxSink.emit()
    }
}

private fun <T> decodeFromOkio(
    source: BufferedSource,
    decode: (kotlinx.io.Source) -> T,
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
} catch (failure: kotlinx.io.IOException) {
    rethrowKotlinxIoFailureThroughOfficialAdapter(failure)
}

private fun rethrowKotlinxIoFailureThroughOfficialAdapter(failure: kotlinx.io.IOException): Nothing {
    val source = object : RawSource {
        override fun readAtMostTo(sink: kotlinx.io.Buffer, byteCount: Long): Long = throw failure

        override fun close() = Unit
    }.asOkioSource()
    source.read(Buffer(), 1L)
    error("The kotlinx-io failure source returned instead of throwing")
}
