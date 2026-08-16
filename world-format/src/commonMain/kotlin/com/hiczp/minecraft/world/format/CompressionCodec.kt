package com.hiczp.minecraft.world.format

import kotlinx.io.*

/**
 * One independently usable world-storage compression stream codec.
 *
 * The returned decorators never close their caller-owned [Source] or [Sink].
 * Closing a compressing decorator is nevertheless required because it emits
 * the stream terminator and checksum. A decompressing decorator validates the
 * complete stream when it is read through end-of-stream.
 *
 * Malformed GZIP or LZ4Block framing is reported as [RegionFormatException]
 * because this module owns those vanilla storage framings; raw GZIP and ZLIB
 * libraries still own their algorithms. I/O and backend failures retain their
 * kotlinx-io exception type so callers can distinguish invalid data from
 * stream access failures.
 */
interface CompressionCodec {
    fun compressingSink(sink: Sink): RawSink

    fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource

    /** Compresses all remaining [source] bytes into [sink]. */
    fun compressToSink(source: Source, sink: Sink): Long =
        compressingSink(sink).buffered().use { compressed ->
            source.transferTo(compressed)
        }

    /** Decompresses one complete stream into [sink]. */
    fun decompressToSink(
        source: Source,
        sink: Sink,
        maximumOutputBytes: Int,
    ): Long {
        require(maximumOutputBytes >= 0)
        return decompressingSource(source, maximumOutputBytes)
            .buffered()
            .use { decompressed -> decompressed.transferTo(sink) }
    }

    /** In-memory adapter over [compressToSink]. */
    fun compress(input: ByteArray): ByteArray {
        val source = Buffer().apply { write(input) }
        val sink = Buffer()
        compressToSink(source, sink)
        return sink.readByteArray()
    }

    /** In-memory adapter over [decompressToSink]. */
    fun decompress(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        val source = Buffer().apply { write(input) }
        val sink = Buffer()
        decompressToSink(source, sink, maximumOutputBytes)
        return sink.readByteArray()
    }
}

/**
 * Compression registry shared by region chunks and standalone NBT files.
 *
 * Built-in vanilla codecs are available automatically. Overrides primarily
 * exist for ID 127 custom compression, but may replace any implementation.
 * Stream methods are canonical; byte-array methods are in-memory adapters.
 */
sealed class CompressionCodecs(
    private val overrides: Map<Compression, CompressionCodec>,
) {
    companion object Default : CompressionCodecs(emptyMap()) {
        operator fun invoke(
            overrides: Map<Compression, CompressionCodec> =
                emptyMap(),
        ): CompressionCodecs =
            ConfiguredCompressionCodecs(overrides.toMap())
    }

    fun compressingSink(
        compression: Compression,
        sink: Sink,
    ): RawSink = codec(compression).compressingSink(sink)

    fun decompressingSource(
        compression: Compression,
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return OutputLimitingRawSource(
            codec(compression).decompressingSource(
                source,
                maximumOutputBytes,
            ),
            maximumOutputBytes,
        )
    }

    fun compressToSink(
        compression: Compression,
        source: Source,
        sink: Sink,
    ): Long = compressingSink(compression, sink).buffered().use { compressed ->
        source.transferTo(compressed)
    }

    fun decompressToSink(
        compression: Compression,
        source: Source,
        sink: Sink,
        maximumOutputBytes: Int,
    ): Long {
        require(maximumOutputBytes >= 0)
        return decompressingSource(
            compression,
            source,
            maximumOutputBytes,
        ).buffered().use { limited -> limited.transferTo(sink) }
    }

    fun compress(
        compression: Compression,
        input: ByteArray,
    ): ByteArray {
        val source = Buffer().apply { write(input) }
        val sink = Buffer()
        compressToSink(compression, source, sink)
        return sink.readByteArray()
    }

    fun decompress(
        compression: Compression,
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        val source = Buffer().apply { write(input) }
        val sink = Buffer()
        decompressToSink(
            compression,
            source,
            sink,
            maximumOutputBytes,
        )
        return sink.readByteArray()
    }

    private fun codec(compression: Compression): CompressionCodec =
        overrides[compression] ?: when (compression) {
            Compression.GZIP -> GzipCodec
            Compression.ZLIB -> ZlibCodec
            Compression.NONE -> NoneCodec
            Compression.LZ4 -> Lz4BlockCodec
            Compression.CUSTOM -> throw RegionFormatException(
                "Custom compression requires a registered codec",
            )
        }
}

private class ConfiguredCompressionCodecs(
    overrides: Map<Compression, CompressionCodec>,
) : CompressionCodecs(overrides)

private object NoneCodec : CompressionCodec {
    override fun compressingSink(sink: Sink): RawSink = sink.callerOwned()

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return source.callerOwned()
    }
}

private object ZlibCodec : CompressionCodec {
    override fun compressingSink(sink: Sink): RawSink =
        platformZlibCompressingSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return platformZlibDecompressingSource(source)
    }
}

private object GzipCodec : CompressionCodec {
    override fun compressingSink(sink: Sink): RawSink =
        platformGzipCompressingSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        // Platform GZIP libraries disagree on malformed-prologue failures.
        // Validate only vanilla's invariant header fields here; the library
        // still owns DEFLATE, optional headers, trailer checks, and decoding.
        validateGzipHeader(source.peek())
        return platformGzipDecompressingSource(source)
    }
}

private fun validateGzipHeader(source: Source) {
    try {
        source.require(4)
        if (
            source.readByte().toInt() and 0xFF != 0x1F ||
            source.readByte().toInt() and 0xFF != 0x8B
        ) {
            throw RegionFormatException("Invalid GZIP magic")
        }
        if (source.readByte().toInt() and 0xFF != 8) {
            throw RegionFormatException("Unsupported GZIP compression method")
        }
        if (source.readByte().toInt() and 0xE0 != 0) {
            throw RegionFormatException("Invalid reserved GZIP flags")
        }
    } catch (failure: EOFException) {
        throw RegionFormatException("Truncated GZIP header", failure)
    }
}

/**
 * Vanilla uses lz4-java's legacy `LZ4Block` stream, not the standard LZ4 frame
 * format. The container stays shared while raw LZ4 and XXHash32 are delegated
 * to each platform's maintained library.
 */
private object Lz4BlockCodec : CompressionCodec {
    override fun compressingSink(sink: Sink): RawSink =
        Lz4BlockCompressingRawSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return Lz4BlockDecompressingRawSource(source, maximumOutputBytes)
    }
}

internal expect fun platformZlibCompressingSink(sink: Sink): RawSink

internal expect fun platformZlibDecompressingSource(source: Source): RawSource

internal expect fun platformGzipCompressingSink(sink: Sink): RawSink

internal expect fun platformGzipDecompressingSource(source: Source): RawSource

internal expect fun platformRawLz4Compress(input: ByteArray): ByteArray

internal expect fun platformRawLz4Decompress(
    input: ByteArray,
    outputLength: Int,
): ByteArray

internal expect fun platformXxHash32(input: ByteArray, seed: Int): Int

// Codec decorators must close to emit or validate framing, while the public
// API promises not to close caller-owned resources. These guards separate the
// decorator lifecycle from the underlying kotlinx-io lifecycle.
internal fun Sink.callerOwned(): RawSink = CallerOwnedRawSink(this)

internal fun Source.callerOwned(): RawSource = CallerOwnedRawSource(this)

private class CallerOwnedRawSink(
    private val delegate: Sink,
) : RawSink {
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Compression sink is closed" }
        delegate.write(source, byteCount)
    }

    override fun flush() {
        check(!closed) { "Compression sink is closed" }
        delegate.flush()
    }

    override fun close() {
        closed = true
    }
}

private class CallerOwnedRawSource(
    private val delegate: Source,
) : RawSource {
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Compression source is closed" }
        return delegate.readAtMostTo(sink, byteCount)
    }

    override fun close() {
        closed = true
    }
}

// Probe one byte beyond the configured limit so an oversized stream cannot be
// accepted merely because its consumer stops at exactly the limit.
private class OutputLimitingRawSource(
    private val upstream: RawSource,
    maximumOutputBytes: Int,
) : RawSource {
    private val maximumOutputBytes = maximumOutputBytes.toLong()
    private var outputBytes = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        val remaining = maximumOutputBytes - outputBytes
        val read = upstream.readAtMostTo(
            sink,
            minOf(byteCount, remaining + 1),
        )
        if (read < 0) return -1
        outputBytes += read
        if (outputBytes > maximumOutputBytes) {
            throw RegionFormatException(
                "Decompressed output exceeds configured limit $maximumOutputBytes",
            )
        }
        return read
    }

    override fun close() {
        upstream.close()
    }
}

private class Lz4BlockCompressingRawSink(
    private val downstream: Sink,
) : RawSink {
    private val block = ByteArray(LZ4_BLOCK_SIZE)
    private val encoded = Buffer()
    private var blockSize = 0
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Compression sink is closed" }
        require(byteCount in 0..source.size)
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(
                remaining,
                (block.size - blockSize).toLong(),
            ).toInt()
            val read = source.readAtMostTo(
                block,
                startIndex = blockSize,
                endIndex = blockSize + count,
            )
            check(read > 0)
            blockSize += read
            remaining -= read
            if (blockSize == block.size) writeBlock()
        }
    }

    override fun flush() {
        check(!closed) { "Compression sink is closed" }
        if (blockSize > 0) writeBlock()
        downstream.flush()
    }

    override fun close() {
        if (closed) return
        try {
            if (blockSize > 0) writeBlock()
            writeLz4Header(
                encoded,
                method = LZ4_RAW_METHOD,
                compressedLength = 0,
                originalLength = 0,
                checksum = 0,
            )
            downstream.write(encoded, encoded.size)
        } finally {
            closed = true
        }
    }

    private fun writeBlock() {
        val original = block.copyOf(blockSize)
        val compressed = platformRawLz4Compress(original)
        // lz4-java's legacy LZ4Block format stores raw bytes whenever the raw
        // result is not smaller. This shared choice preserves byte compatibility
        // while each platform library owns raw LZ4.
        val useCompressed = compressed.size < original.size
        val payload = if (useCompressed) compressed else original
        val method = if (useCompressed) LZ4_METHOD else LZ4_RAW_METHOD
        // The legacy lz4-java container deliberately stores 28 checksum bits.
        // The platform library still computes the full XXHash32.
        val checksum = platformXxHash32(original, LZ4_XXHASH_SEED) and
                LZ4_CHECKSUM_MASK
        writeLz4Header(
            encoded,
            method = method,
            compressedLength = payload.size,
            originalLength = original.size,
            checksum = checksum,
        )
        encoded.write(payload)
        downstream.write(encoded, encoded.size)
        blockSize = 0
    }
}

private class Lz4BlockDecompressingRawSource(
    upstream: Source,
    private val maximumOutputBytes: Int,
) : RawSource {
    private val upstream = upstream.callerOwned().buffered()
    private var block = ByteArray(0)
    private var blockOffset = 0
    private var outputBytes = 0L
    private var finished = false
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Compression source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (finished) return -1
        if (blockOffset == block.size && !readBlock()) return -1

        val count = minOf(
            byteCount,
            (block.size - blockOffset).toLong(),
        ).toInt()
        sink.write(block, blockOffset, blockOffset + count)
        blockOffset += count
        return count.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        upstream.close()
    }

    private fun readBlock(): Boolean {
        try {
            repeat(LZ4_MAGIC.size) { index ->
                if (upstream.readByte() != LZ4_MAGIC[index]) {
                    throw RegionFormatException("Invalid LZ4Block magic")
                }
            }
            val token = upstream.readByte().toInt() and 0xFF
            val method = token and 0xF0
            val compressionLevel = token and 0x0F
            if (
                method != LZ4_RAW_METHOD && method != LZ4_METHOD ||
                compressionLevel != LZ4_COMPRESSION_LEVEL
            ) {
                throw RegionFormatException("Invalid LZ4Block token")
            }
            val compressedLength = upstream.readIntLe()
            val originalLength = upstream.readIntLe()
            val expectedChecksum = upstream.readIntLe()
            if (
                compressedLength !in 0..LZ4_BLOCK_SIZE ||
                originalLength !in 0..LZ4_BLOCK_SIZE ||
                (originalLength == 0) != (compressedLength == 0) ||
                (method == LZ4_RAW_METHOD &&
                        originalLength != compressedLength)
            ) {
                throw RegionFormatException("Invalid LZ4Block lengths")
            }
            if (originalLength == 0) {
                if (method != LZ4_RAW_METHOD || expectedChecksum != 0) {
                    throw RegionFormatException("Invalid LZ4Block end marker")
                }
                if (!upstream.exhausted()) {
                    throw RegionFormatException(
                        "Trailing bytes after LZ4Block end marker",
                    )
                }
                finished = true
                return false
            }
            if (
                outputBytes >
                maximumOutputBytes.toLong() - originalLength
            ) {
                throw RegionFormatException(
                    "LZ4 output exceeds configured limit $maximumOutputBytes",
                )
            }

            val encoded = upstream.readByteArray(compressedLength)
            val decoded = if (method == LZ4_RAW_METHOD) {
                encoded
            } else {
                try {
                    platformRawLz4Decompress(encoded, originalLength)
                } catch (failure: IOException) {
                    throw RegionFormatException("Invalid raw LZ4 block", failure)
                }
            }
            if (decoded.size != originalLength) {
                throw RegionFormatException("LZ4 block length mismatch")
            }
            val actualChecksum = platformXxHash32(
                decoded,
                LZ4_XXHASH_SEED,
            ) and LZ4_CHECKSUM_MASK
            if (actualChecksum != expectedChecksum) {
                throw RegionFormatException("Invalid LZ4Block XXHash-32")
            }
            block = decoded
            blockOffset = 0
            outputBytes += originalLength
            return true
        } catch (failure: RegionFormatException) {
            throw failure
        } catch (failure: EOFException) {
            throw RegionFormatException("Truncated LZ4Block stream", failure)
        }
    }
}

private fun writeLz4Header(
    sink: Buffer,
    method: Int,
    compressedLength: Int,
    originalLength: Int,
    checksum: Int,
) {
    sink.write(LZ4_MAGIC)
    sink.writeByte((method or LZ4_COMPRESSION_LEVEL).toByte())
    sink.writeIntLe(compressedLength)
    sink.writeIntLe(originalLength)
    sink.writeIntLe(checksum)
}

private val LZ4_MAGIC = byteArrayOf(
    'L'.code.toByte(),
    'Z'.code.toByte(),
    '4'.code.toByte(),
    'B'.code.toByte(),
    'l'.code.toByte(),
    'o'.code.toByte(),
    'c'.code.toByte(),
    'k'.code.toByte(),
)

private const val LZ4_BLOCK_SIZE = 1 shl 16
private const val LZ4_COMPRESSION_LEVEL = 6
private const val LZ4_RAW_METHOD = 0x10
private const val LZ4_METHOD = 0x20
private const val LZ4_XXHASH_SEED = 0x9747B28C.toInt()
private const val LZ4_CHECKSUM_MASK = 0x0FFF_FFFF
