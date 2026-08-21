package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.io.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

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
    val nbt: NbtFormat = NbtFormat(
        NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
    ),
    val compressionRegistry: CompressionRegistry = CompressionRegistry,
) {
    init {
        require(nbt.configuration.rootEncoding == NbtRootEncoding.UNNAMED) {
            "Region Chunk NBT requires NbtRootEncoding.UNNAMED"
        }
    }

    /**
     * Decodes one complete compressed NBT stream without closing [source].
     * Compression and serialization exceptions propagate unchanged.
     */
    fun decodeDocumentFromSource(
        source: Source,
        compression: Compression,
    ): NbtDocument = decodeCompressed(source, compression, nbt::decodeDocumentFromSource)

    /** Decodes a caller-selected serializable value from one compressed Chunk stream. */
    fun <T> decodeFromSource(
        deserializer: DeserializationStrategy<T>,
        source: Source,
        compression: Compression,
    ): T = decodeCompressed(source, compression) {
        nbt.decodeFromSource(deserializer, it)
    }

    /**
     * Encodes one complete compressed NBT stream without closing [sink].
     * Compression and serialization exceptions propagate unchanged.
     */
    fun encodeDocumentToSink(
        document: NbtDocument,
        compression: Compression,
        sink: Sink,
    ) = encodeCompressed(compression, sink) {
        nbt.encodeDocumentToSink(document, it)
    }

    /** Encodes a caller-selected serializable value into one compressed Chunk stream. */
    fun <T> encodeToSink(
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression,
        sink: Sink,
    ) = encodeCompressed(compression, sink) {
        nbt.encodeToSink(serializer, value, it)
    }

    /** In-memory adapter over [decodeDocumentFromSource]. */
    fun decodeDocument(chunk: CompressedChunk): NbtDocument {
        val source = Buffer()
        chunk.writeTo(source)
        return decodeDocumentFromSource(source, chunk.compression)
    }

    /** In-memory adapter over the typed [decodeFromSource] path. */
    fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        chunk: CompressedChunk,
    ): T {
        val source = Buffer()
        chunk.writeTo(source)
        return decodeFromSource(deserializer, source, chunk.compression)
    }

    /**
     * In-memory adapter over [encodeDocumentToSink]. The compressed bytes are retained
     * because they form the returned [CompressedChunk] payload.
     */
    fun encodeDocument(
        document: NbtDocument,
        compression: Compression = Compression.ZLIB,
    ): CompressedChunk {
        val compressed = Buffer()
        encodeDocumentToSink(document, compression, compressed)
        return CompressedChunk.takeOwnership(compression, compressed.readByteArray())
    }

    /** In-memory adapter over the typed [encodeToSink] path. */
    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = Compression.ZLIB,
    ): CompressedChunk {
        val compressed = Buffer()
        encodeToSink(serializer, value, compression, compressed)
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
