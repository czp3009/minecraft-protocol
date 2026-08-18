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
 * This format serves region-chunk payloads selected by [RegionChunk].
 * Standalone compressed NBT files such as `level.dat` are file-level policy
 * owned by world-io, or compose `NbtFormat` with `CompressionCodecs` directly.
 *
 * [encodeToSink] and [decodeFromSource] are the canonical streaming paths.
 * Compression and NBT serialization share the same kotlinx-io boundary, so
 * callers can compose them without platform adapters or wrapper streams.
 * Methods returning a [RegionChunk] necessarily retain its compressed payload
 * because those bytes are the value represented by that model.
 */
class RegionChunkNbtFormat(
    val nbt: NbtFormat = NbtFormat(
        NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
    ),
    val compressionCodecs: CompressionCodecs = CompressionCodecs,
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
    fun decodeFromSource(
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
    fun encodeToSink(
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

    /** In-memory adapter over [decodeFromSource]. */
    fun decode(chunk: RegionChunk): NbtDocument {
        val compressed = chunk.payload.compressedBytes
            ?: throw RegionFormatException(
                "External region chunk payload has not been resolved",
            )
        val source = Buffer().apply { write(compressed) }
        return decodeFromSource(source, chunk.compression)
    }

    /** In-memory adapter over the typed [decodeFromSource] path. */
    fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        chunk: RegionChunk,
    ): T {
        val compressed = chunk.payload.compressedBytes
            ?: throw RegionFormatException(
                "External region chunk payload has not been resolved",
            )
        val source = Buffer().apply { write(compressed) }
        return decodeFromSource(deserializer, source, chunk.compression)
    }

    /**
     * In-memory adapter over [encodeToSink]. The compressed bytes are retained
     * because they form the returned [RegionChunk] payload.
     */
    fun encode(
        document: NbtDocument,
        compression: Compression = Compression.ZLIB,
        timestamp: Int = 0,
        external: Boolean = false,
    ): RegionChunk {
        val compressed = Buffer()
        encodeToSink(document, compression, compressed)
        val bytes = compressed.readByteArray()
        val payload = if (external) {
            RegionChunkPayload.External(bytes)
        } else {
            RegionChunkPayload.Inline(bytes)
        }
        return RegionChunk(
            compression = compression,
            payload = payload,
            timestamp = timestamp,
        )
    }

    /** In-memory adapter over the typed [encodeToSink] path. */
    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = Compression.ZLIB,
        timestamp: Int = 0,
        external: Boolean = false,
    ): RegionChunk {
        val compressed = Buffer()
        encodeToSink(serializer, value, compression, compressed)
        val bytes = compressed.readByteArray()
        val payload = if (external) {
            RegionChunkPayload.External(bytes)
        } else {
            RegionChunkPayload.Inline(bytes)
        }
        return RegionChunk(
            compression = compression,
            payload = payload,
            timestamp = timestamp,
        )
    }

    private fun <T> decodeCompressed(
        source: Source,
        compression: Compression,
        block: (Source) -> T,
    ): T = compressionCodecs.decompressingSource(compression, source).buffered().use { decompressed ->
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
        compressionCodecs.compressingSink(compression, sink).buffered().use(block)
    }
}
