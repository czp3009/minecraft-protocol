package com.hiczp.minecraft.world.format

import okio.*

/**
 * One independently usable region-compression stream codec.
 *
 * The returned decorators never close their caller-owned [Source] or [Sink].
 * Closing a compressing decorator is nevertheless required because it emits
 * the stream terminator and checksum. A decompressing decorator validates the
 * complete stream when it is read through end-of-stream.
 */
interface RegionCompressionCodec {
    fun compressingSink(sink: Sink): Sink

    fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): Source

    /** Compresses all remaining [source] bytes into [sink]. */
    fun compressToSink(source: Source, sink: Sink): Long =
        compressingSink(sink).buffer().use { compressed ->
            compressed.writeAll(source)
        }

    /** Decompresses one complete stream into [sink]. */
    fun decompressToSink(
        source: Source,
        sink: Sink,
        maximumOutputBytes: Int,
    ): Long {
        require(maximumOutputBytes >= 0)
        return decompressingSource(source, maximumOutputBytes)
            .buffer()
            .use { decompressed ->
                decompressed.readAll(sink)
            }
    }

    /** In-memory adapter over [compressToSink]. */
    fun compress(input: ByteArray): ByteArray {
        val source = Buffer().write(input)
        val sink = Buffer()
        compressToSink(source, sink)
        return sink.readByteArray()
    }

    /** In-memory adapter over [decompressToSink]. */
    fun decompress(
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        val source = Buffer().write(input)
        val sink = Buffer()
        decompressToSink(source, sink, maximumOutputBytes)
        return sink.readByteArray()
    }
}

/**
 * Compression registry for region chunks.
 *
 * Built-in vanilla codecs are available automatically. Overrides primarily
 * exist for ID 127 custom compression, but may replace any implementation.
 * Stream methods are canonical; byte-array methods are in-memory adapters.
 * Regardless of the selected platform backend, codec failures crossing this
 * registry are exposed as [RegionFormatException].
 */
sealed class RegionCompressionCodecs(
    private val overrides: Map<RegionCompression, RegionCompressionCodec>,
) {
    companion object Default : RegionCompressionCodecs(emptyMap()) {
        operator fun invoke(
            overrides: Map<RegionCompression, RegionCompressionCodec> =
                emptyMap(),
        ): RegionCompressionCodecs =
            ConfiguredRegionCompressionCodecs(overrides.toMap())
    }

    fun compressingSink(
        compression: RegionCompression,
        sink: Sink,
    ): Sink = mapCompressionFailure(
        "Cannot create ${compression.name} compression stream",
    ) {
        codec(compression)
            .compressingSink(sink)
            .withRegionFormatExceptions(
                "Cannot compress ${compression.name} stream",
            )
    }

    fun decompressingSource(
        compression: RegionCompression,
        source: Source,
        maximumOutputBytes: Int,
    ): Source {
        require(maximumOutputBytes >= 0)
        val decoded = mapCompressionFailure(
            "Cannot create ${compression.name} decompression stream",
        ) {
            codec(compression)
                .decompressingSource(source, maximumOutputBytes)
                .withRegionFormatExceptions(
                    "Invalid ${compression.name} stream",
                )
        }
        return OutputLimitingSource(decoded, maximumOutputBytes)
    }

    fun compressToSink(
        compression: RegionCompression,
        source: Source,
        sink: Sink,
    ): Long = compressingSink(compression, sink).buffer().use { compressed ->
        compressed.writeAll(source)
    }

    fun decompressToSink(
        compression: RegionCompression,
        source: Source,
        sink: Sink,
        maximumOutputBytes: Int,
    ): Long {
        require(maximumOutputBytes >= 0)
        return decompressingSource(
            compression,
            source,
            maximumOutputBytes,
        ).buffer().use { limited ->
            limited.readAll(sink)
        }
    }

    fun compress(
        compression: RegionCompression,
        input: ByteArray,
    ): ByteArray {
        val source = Buffer().write(input)
        val sink = Buffer()
        compressToSink(compression, source, sink)
        return sink.readByteArray()
    }

    fun decompress(
        compression: RegionCompression,
        input: ByteArray,
        maximumOutputBytes: Int,
    ): ByteArray {
        val source = Buffer().write(input)
        val sink = Buffer()
        decompressToSink(
            compression,
            source,
            sink,
            maximumOutputBytes,
        )
        return sink.readByteArray()
    }

    private fun codec(compression: RegionCompression): RegionCompressionCodec =
        overrides[compression] ?: when (compression) {
            RegionCompression.GZIP -> GzipCodec
            RegionCompression.ZLIB -> ZlibCodec
            RegionCompression.NONE -> NoneCodec
            RegionCompression.LZ4 -> Lz4BlockCodec
            RegionCompression.CUSTOM -> throw RegionFormatException(
                "Custom region compression requires a registered codec",
            )
        }
}

private class ConfiguredRegionCompressionCodecs(
    overrides: Map<RegionCompression, RegionCompressionCodec>,
) : RegionCompressionCodecs(overrides)

private object NoneCodec : RegionCompressionCodec {
    override fun compressingSink(sink: Sink): Sink = sink.callerOwned()

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): Source {
        require(maximumOutputBytes >= 0)
        return source.callerOwned()
    }
}

private object ZlibCodec : RegionCompressionCodec {
    override fun compressingSink(sink: Sink): Sink =
        platformZlibCompressingSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): Source {
        require(maximumOutputBytes >= 0)
        return platformZlibDecompressingSource(source)
    }
}

private object GzipCodec : RegionCompressionCodec {
    override fun compressingSink(sink: Sink): Sink =
        platformGzipCompressingSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): Source {
        require(maximumOutputBytes >= 0)
        val buffered = source.buffer()
        // Platform GZIP libraries disagree on malformed-prologue failures.
        // Validate only vanilla's invariant header fields here for one public
        // RegionFormatException contract; the library still owns DEFLATE,
        // optional-header parsing, trailer checks, and decompression.
        validateGzipHeader(buffered.peek())
        return platformGzipDecompressingSource(buffered)
    }
}

private fun validateGzipHeader(source: Source) {
    val header = source.buffer()
    try {
        header.require(4)
        if (
            header.readByte().toInt() and 0xFF != 0x1F ||
            header.readByte().toInt() and 0xFF != 0x8B
        ) {
            throw RegionFormatException("Invalid GZIP magic")
        }
        if (header.readByte().toInt() and 0xFF != 8) {
            throw RegionFormatException("Unsupported GZIP compression method")
        }
        if (header.readByte().toInt() and 0xE0 != 0) {
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
private object Lz4BlockCodec : RegionCompressionCodec {
    override fun compressingSink(sink: Sink): Sink =
        Lz4BlockCompressingSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): Source {
        require(maximumOutputBytes >= 0)
        return Lz4BlockDecompressingSource(source, maximumOutputBytes)
    }
}

internal expect fun platformZlibCompressingSink(sink: Sink): Sink

internal expect fun platformZlibDecompressingSource(source: Source): Source

internal expect fun platformGzipCompressingSink(sink: Sink): Sink

internal expect fun platformGzipDecompressingSource(source: Source): Source

internal expect fun platformRawLz4Compress(input: ByteArray): ByteArray

internal expect fun platformRawLz4Decompress(
    input: ByteArray,
    outputLength: Int,
): ByteArray

internal expect fun platformXxHash32(input: ByteArray, seed: Int): Int

// Codec decorators must close to emit or validate framing, while this public
// API promises not to close caller-owned resources. These guards separate the
// decorator lifetime from the underlying Okio lifetime.
internal fun Sink.callerOwned(): Sink = CallerOwnedSink(this)

internal fun Source.callerOwned(): Source = CallerOwnedSource(this)

private class CallerOwnedSink(
    private val delegate: Sink,
) : Sink {
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Compression sink is closed" }
        delegate.write(source, byteCount)
    }

    override fun flush() {
        check(!closed) { "Compression sink is closed" }
        delegate.flush()
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() {
        closed = true
    }
}

private class CallerOwnedSource(
    delegate: Source,
) : ForwardingSource(delegate) {
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Compression source is closed" }
        return super.read(sink, byteCount)
    }

    override fun close() {
        closed = true
    }
}

// Probe one byte beyond the configured limit so an oversized stream cannot be
// accepted merely because its consumer stops at exactly the limit.
private class OutputLimitingSource(
    private val upstream: Source,
    maximumOutputBytes: Int,
) : Source {
    private val maximumOutputBytes = maximumOutputBytes.toLong()
    private var outputBytes = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        val remaining = maximumOutputBytes - outputBytes
        val read = upstream.read(sink, minOf(byteCount, remaining + 1))
        if (read < 0) return -1
        outputBytes += read
        if (outputBytes > maximumOutputBytes) {
            throw RegionFormatException(
                "Decompressed output exceeds configured limit $maximumOutputBytes",
            )
        }
        return read
    }

    override fun timeout(): Timeout = upstream.timeout()

    override fun close() {
        upstream.close()
    }
}

// Okio/kotlinx-io converters already translate their own I/O failures. This
// layer only normalizes backend and custom-codec failures to world-format's
// stable public exception type.
private fun Sink.withRegionFormatExceptions(message: String): Sink =
    RegionFormatExceptionMappingSink(this, message)

private fun Source.withRegionFormatExceptions(message: String): Source =
    RegionFormatExceptionMappingSource(this, message)

private class RegionFormatExceptionMappingSink(
    private val delegate: Sink,
    private val message: String,
) : Sink {
    override fun write(source: Buffer, byteCount: Long) =
        mapCompressionFailure(message) {
            delegate.write(source, byteCount)
        }

    override fun flush() = mapCompressionFailure(message, delegate::flush)

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = mapCompressionFailure(message, delegate::close)
}

private class RegionFormatExceptionMappingSource(
    private val delegate: Source,
    private val message: String,
) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long =
        mapCompressionFailure(message) {
            delegate.read(sink, byteCount)
        }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = mapCompressionFailure(message, delegate::close)
}

private class Lz4BlockCompressingSink(
    private val downstream: Sink,
) : Sink {
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
            val read = source.read(block, blockSize, count)
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

    override fun timeout(): Timeout = downstream.timeout()

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
        val compressed = mapCompressionFailure("Cannot compress LZ4 block") {
            platformRawLz4Compress(original)
        }
        // lz4-java's legacy LZ4Block format stores raw bytes whenever the raw
        // LZ4 result is not smaller; reproducing that container decision keeps
        // every platform byte-compatible while the library owns raw LZ4.
        val useCompressed = compressed.size < original.size
        val payload = if (useCompressed) compressed else original
        val method = if (useCompressed) LZ4_METHOD else LZ4_RAW_METHOD
        // The legacy lz4-java container deliberately stores 28 checksum bits.
        // The platform library still computes the full XXHash32.
        val checksum = mapCompressionFailure("Cannot hash LZ4 block") {
            platformXxHash32(original, LZ4_XXHASH_SEED)
        } and LZ4_CHECKSUM_MASK
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

private class Lz4BlockDecompressingSource(
    upstream: Source,
    private val maximumOutputBytes: Int,
) : Source {
    private val upstream = upstream.buffer()
    private var block = ByteArray(0)
    private var blockOffset = 0
    private var outputBytes = 0L
    private var finished = false
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Compression source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (finished) return -1
        if (blockOffset == block.size && !readBlock()) return -1

        val count = minOf(
            byteCount,
            (block.size - blockOffset).toLong(),
        ).toInt()
        sink.write(block, blockOffset, count)
        blockOffset += count
        return count.toLong()
    }

    override fun timeout(): Timeout = upstream.timeout()

    override fun close() {
        if (closed) return
        closed = true
        upstream.buffer.clear()
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

            val encoded = upstream.readByteArray(compressedLength.toLong())
            val decoded = if (method == LZ4_RAW_METHOD) {
                encoded
            } else {
                mapCompressionFailure("Invalid raw LZ4 block") {
                    platformRawLz4Decompress(encoded, originalLength)
                }
            }
            if (decoded.size != originalLength) {
                throw RegionFormatException("LZ4 block length mismatch")
            }
            val actualChecksum = mapCompressionFailure(
                "Cannot hash LZ4 block",
            ) {
                platformXxHash32(decoded, LZ4_XXHASH_SEED)
            } and LZ4_CHECKSUM_MASK
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

private inline fun <T> mapCompressionFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: RegionFormatException) {
    throw failure
} catch (failure: Exception) {
    throw RegionFormatException(message, failure)
}

private fun writeLz4Header(
    sink: Buffer,
    method: Int,
    compressedLength: Int,
    originalLength: Int,
    checksum: Int,
) {
    sink.write(LZ4_MAGIC)
    sink.writeByte(method or LZ4_COMPRESSION_LEVEL)
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
