package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.io.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

/**
 * Composes region compression with compound-document NBT while keeping both
 * independently reusable.
 *
 * This format serves positionless [CompressedChunk] values.
 * Standalone compressed NBT files such as `level.dat` are file-level policy
 * owned by world-io, or compose `NbtFormat` with `CompressionRegistry` directly.
 *
 * [encodeToSink] and [decodeFromSource] are the canonical streaming paths.
 * Compression and NBT serialization share the same kotlinx-io boundary, so
 * callers can compose them without platform adapters or wrapper streams.
 * Methods returning a [CompressedChunk] necessarily retain its compressed payload
 * because those bytes are the value represented by that model.
 */
class CompressedNbtFormat(
    val nbtFormat: NbtFormat = NbtFormat(
        NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
    ),
    val compressionRegistry: CompressionRegistry = CompressionRegistry,
) {
    /**
     * Decodes one complete compressed NBT stream without closing [source].
     * Compression and serialization exceptions propagate unchanged.
     */
    fun decodeDocumentFromSource(
        source: Source,
        compression: Compression,
    ): NbtDocument = decodeCompressed(source, compression, nbtFormat::decodeDocumentFromSource)

    /** Decodes a caller-selected serializable value from one compressed Chunk stream. */
    fun <T> decodeFromSource(
        source: Source,
        compression: Compression,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = decodeCompressed(source, compression) {
        nbtFormat.decodeFromSource(deserializationStrategy, it)
    }

    /**
     * Encodes one complete compressed NBT stream without closing [sink].
     * Compression and serialization exceptions propagate unchanged.
     */
    fun encodeDocumentToSink(
        nbtDocument: NbtDocument,
        compression: Compression,
        sink: Sink,
    ) = encodeCompressed(compression, sink) {
        nbtFormat.encodeDocumentToSink(nbtDocument, it)
    }

    /** Encodes a caller-selected serializable value into one compressed Chunk stream. */
    fun <T> encodeToSink(
        value: T,
        compression: Compression,
        sink: Sink,
        serializationStrategy: SerializationStrategy<T>,
    ) = encodeCompressed(compression, sink) {
        nbtFormat.encodeToSink(serializationStrategy, value, it)
    }

    /** In-memory adapter over [decodeDocumentFromSource]. */
    fun decodeDocument(compressedChunk: CompressedChunk): NbtDocument {
        val source = Buffer()
        compressedChunk.writeTo(source)
        return decodeDocumentFromSource(source, compressedChunk.compression)
    }

    /** In-memory adapter over the typed [decodeFromSource] path. */
    fun <T> decode(
        compressedChunk: CompressedChunk,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T {
        val source = Buffer()
        compressedChunk.writeTo(source)
        return decodeFromSource(source, compressedChunk.compression, deserializationStrategy)
    }

    /**
     * In-memory adapter over [encodeDocumentToSink]. The compressed bytes are retained
     * because they form the returned [CompressedChunk] payload.
     */
    fun encodeDocument(
        nbtDocument: NbtDocument,
        compression: Compression = Compression.ZLIB,
    ): CompressedChunk {
        val compressed = Buffer()
        encodeDocumentToSink(nbtDocument, compression, compressed)
        return CompressedChunk.takeOwnership(compression, compressed.readByteArray())
    }

    /** In-memory adapter over the typed [encodeToSink] path. */
    fun <T> encode(
        value: T,
        compression: Compression = Compression.ZLIB,
        serializationStrategy: SerializationStrategy<T>,
    ): CompressedChunk {
        val compressed = Buffer()
        encodeToSink(value, compression, compressed, serializationStrategy)
        return CompressedChunk.takeOwnership(compression, compressed.readByteArray())
    }

    private fun <T> decodeCompressed(
        source: Source,
        compression: Compression,
        block: (Source) -> T,
    ): T = compressionRegistry.decompressingSource(compression, source).buffered().use { decompressed ->
        val value = block(decompressed)
        if (!decompressed.exhausted()) {
            throw NbtDecodingException(
                "Decompressed chunk has trailing NBT bytes",
            )
        }
        value
    }

    private fun encodeCompressed(
        compression: Compression,
        sink: Sink,
        block: (Sink) -> Unit,
    ) {
        compressionRegistry.compressingSink(compression, sink).buffered().use(block)
    }
}

inline fun <reified T> CompressedNbtFormat.decodeFromSource(
    source: Source,
    compression: Compression,
): T = decodeFromSource(source, compression, nbtFormat.serializersModule.serializer())

inline fun <reified T> CompressedNbtFormat.encodeToSink(
    value: T,
    compression: Compression,
    sink: Sink,
) = encodeToSink(value, compression, sink, nbtFormat.serializersModule.serializer())

inline fun <reified T> CompressedNbtFormat.decode(compressedChunk: CompressedChunk): T =
    decode(compressedChunk, nbtFormat.serializersModule.serializer())

inline fun <reified T> CompressedNbtFormat.encode(
    value: T,
    compression: Compression = Compression.ZLIB,
): CompressedChunk = encode(value, compression, nbtFormat.serializersModule.serializer())
