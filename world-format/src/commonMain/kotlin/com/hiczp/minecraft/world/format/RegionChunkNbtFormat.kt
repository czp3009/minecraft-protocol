package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.*

data class RegionChunkNbtFormatConfiguration(
    val maximumDecompressedChunkBytes: Int = 256 * 1_048_576,
) {
    init {
        require(maximumDecompressedChunkBytes >= 0)
    }
}

/**
 * Composes region compression with compound-document NBT while keeping both
 * independently reusable.
 *
 * [encodeToSink] and [decodeFromSource] are the canonical streaming paths.
 * Methods returning a [RegionChunk] necessarily retain its compressed payload
 * because those bytes are the value represented by that model.
 */
class RegionChunkNbtFormat(
    val nbt: NbtFormat = NbtFormat,
    val compressionCodecs: RegionCompressionCodecs =
        RegionCompressionCodecs,
    val configuration: RegionChunkNbtFormatConfiguration =
        RegionChunkNbtFormatConfiguration(),
) {
    /**
     * Decodes one complete compressed NBT stream without closing [source].
     * Compression and serialization exceptions propagate unchanged.
     */
    fun decodeFromSource(
        source: Source,
        compression: RegionCompression,
    ): NbtDocument {
        val decompressed = compressionCodecs.decompressingSource(
            compression,
            source,
            configuration.maximumDecompressedChunkBytes,
        ).buffered()
        var failure: Throwable? = null
        return try {
            val document = nbt.decodeDocumentFromSource(decompressed)
            if (!decompressed.exhausted()) {
                throw NbtDecodingException(
                    "Decompressed chunk has trailing NBT bytes",
                )
            }
            document
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(failure, decompressed::close)
        }
    }

    /**
     * Encodes one complete compressed NBT stream without closing [sink].
     * Compression and serialization exceptions propagate unchanged.
     */
    fun encodeToSink(
        document: NbtDocument,
        compression: RegionCompression,
        sink: Sink,
    ) {
        val compressed =
            compressionCodecs.compressingSink(compression, sink).buffered()
        val limited = DecompressedLimitRawSink(
            compressed,
            configuration.maximumDecompressedChunkBytes,
        ).buffered()
        var failure: Throwable? = null
        try {
            nbt.encodeDocumentToSink(document, limited)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeAllPreserving(
                failure,
                limited::close,
                compressed::close,
            )
        }
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

    /**
     * In-memory adapter over [encodeToSink]. The compressed bytes are retained
     * because they form the returned [RegionChunk] payload.
     */
    fun encode(
        document: NbtDocument,
        compression: RegionCompression = RegionCompression.ZLIB,
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
}

private class DecompressedLimitRawSink(
    private val downstream: Sink,
    maximumBytes: Int,
) : RawSink {
    private val maximumBytes = maximumBytes.toLong()
    private var bytesWritten = 0L

    override fun write(source: Buffer, byteCount: Long) {
        if (
            byteCount < 0 ||
            byteCount > maximumBytes - bytesWritten
        ) {
            throw RegionFormatException(
                "NBT chunk exceeds configured limit $maximumBytes",
            )
        }
        downstream.write(source, byteCount)
        bytesWritten += byteCount
    }

    override fun flush() {
        downstream.flush()
    }

    override fun close() = Unit
}

private fun closeAllPreserving(
    failure: Throwable?,
    vararg closes: () -> Unit,
) {
    var primary = failure
    closes.forEach { close ->
        try {
            close()
        } catch (closeFailure: Throwable) {
            val current = primary
            if (current == null) {
                primary = closeFailure
            } else {
                current.addSuppressed(closeFailure)
            }
        }
    }
    if (failure == null) throw primary ?: return
}
