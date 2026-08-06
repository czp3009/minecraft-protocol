package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.compression.RawDeflate
import kotlinx.io.*

/**
 * One independently usable region-compression stream codec.
 *
 * The returned decorators never close their caller-owned [Source] or [Sink].
 * Closing a compressing decorator is nevertheless required because it emits
 * the stream terminator and checksum. A decompressing decorator validates the
 * trailer when it is read through end-of-stream.
 */
interface RegionCompressionCodec {
    fun compressingSink(sink: Sink): RawSink

    fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource

    /** Compresses all remaining [source] bytes into [sink]. */
    fun compressToSink(source: Source, sink: Sink): Long {
        val compressed = compressingSink(sink).buffered()
        var failure: Throwable? = null
        return try {
            source.transferTo(compressed)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeTransformPreserving(failure, compressed::close)
        }
    }

    /** Decompresses one complete stream into [sink]. */
    fun decompressToSink(
        source: Source,
        sink: Sink,
        maximumOutputBytes: Int,
    ): Long {
        require(maximumOutputBytes >= 0)
        val decompressed =
            decompressingSource(source, maximumOutputBytes).buffered()
        var failure: Throwable? = null
        return try {
            decompressed.transferTo(sink)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeTransformPreserving(failure, decompressed::close)
        }
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
 * Compression registry for region chunks.
 *
 * Built-in vanilla codecs are available automatically. Overrides primarily
 * exist for ID 127 custom compression, but may replace any implementation.
 * Stream methods are canonical; byte-array methods are in-memory adapters.
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
    ): RawSink = codec(compression).compressingSink(sink)

    fun decompressingSource(
        compression: RegionCompression,
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        val decoded = codec(compression).decompressingSource(
            source,
            maximumOutputBytes,
        )
        return OutputLimitingRawSource(decoded, maximumOutputBytes)
    }

    fun compressToSink(
        compression: RegionCompression,
        source: Source,
        sink: Sink,
    ): Long = codec(compression).compressToSink(source, sink)

    fun decompressToSink(
        compression: RegionCompression,
        source: Source,
        sink: Sink,
        maximumOutputBytes: Int,
    ): Long {
        require(maximumOutputBytes >= 0)
        val limited = decompressingSource(
            compression,
            source,
            maximumOutputBytes,
        ).buffered()
        var failure: Throwable? = null
        return try {
            limited.transferTo(sink)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeTransformPreserving(failure, limited::close)
        }
    }

    fun compress(
        compression: RegionCompression,
        input: ByteArray,
    ): ByteArray {
        val source = Buffer().apply { write(input) }
        val sink = Buffer()
        compressToSink(compression, source, sink)
        return sink.readByteArray()
    }

    fun decompress(
        compression: RegionCompression,
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
    override fun compressingSink(sink: Sink): RawSink =
        ForwardingRawSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return OutputLimitingRawSource(
            ForwardingRawSource(source),
            maximumOutputBytes,
        )
    }
}

private object ZlibCodec : RegionCompressionCodec {
    override fun compressingSink(sink: Sink): RawSink {
        sink.writeByte(0x78)
        sink.writeByte(0x9C.toByte())
        return ZlibCompressingSink(sink)
    }

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        readZlibHeader(source)
        return ZlibDecompressingSource(
            source,
            maximumOutputBytes,
        )
    }
}

private object GzipCodec : RegionCompressionCodec {
    override fun compressingSink(sink: Sink): RawSink {
        sink.write(GZIP_FIXED_HEADER)
        return GzipCompressingSink(sink)
    }

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        readGzipHeader(source)
        return GzipDecompressingSource(
            source,
            maximumOutputBytes,
        )
    }
}

/**
 * Vanilla uses lz4-java's legacy `LZ4Block` stream, not the standard LZ4 frame
 * format. Encoding raw blocks is valid for that stream and deterministic;
 * decoding accepts both raw and LZ4-compressed blocks.
 */
private object Lz4BlockCodec : RegionCompressionCodec {
    override fun compressingSink(sink: Sink): RawSink =
        Lz4BlockCompressingSink(sink)

    override fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return Lz4BlockDecompressingSource(source, maximumOutputBytes)
    }
}

private class ForwardingRawSink(
    private val downstream: Sink,
) : RawSink {
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Compression sink is closed" }
        downstream.write(source, byteCount)
    }

    override fun flush() {
        check(!closed) { "Compression sink is closed" }
        downstream.flush()
    }

    override fun close() {
        closed = true
    }
}

private class ForwardingRawSource(
    private val upstream: Source,
) : RawSource {
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Compression source is closed" }
        return upstream.readAtMostTo(sink, byteCount)
    }

    override fun close() {
        closed = true
    }
}

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

private abstract class DeflateCompressingSink(
    protected val downstream: Sink,
) : RawSink {
    private val deflate = RawDeflate.compressingSink(downstream)
    private val transfer = Buffer()
    private val scratch = ByteArray(STREAM_COPY_BYTES)
    private var closed = false

    final override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Compression sink is closed" }
        require(byteCount in 0..source.size)
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, scratch.size.toLong()).toInt()
            val read = source.readAtMostTo(scratch, endIndex = count)
            check(read > 0)
            update(scratch, read)
            transfer.write(scratch, endIndex = read)
            deflate.write(transfer, read.toLong())
            remaining -= read
        }
    }

    final override fun flush() {
        check(!closed) { "Compression sink is closed" }
        deflate.flush()
    }

    final override fun close() {
        if (closed) return
        try {
            deflate.close()
            writeTrailer()
        } finally {
            closed = true
        }
    }

    protected abstract fun update(bytes: ByteArray, count: Int)

    protected abstract fun writeTrailer()
}

private class ZlibCompressingSink(
    downstream: Sink,
) : DeflateCompressingSink(downstream) {
    private val checksum = Adler32()

    override fun update(bytes: ByteArray, count: Int) {
        checksum.update(bytes, count)
    }

    override fun writeTrailer() {
        downstream.writeInt(checksum.value)
    }
}

private class GzipCompressingSink(
    downstream: Sink,
) : DeflateCompressingSink(downstream) {
    private val checksum = Crc32()
    private var size = 0u

    override fun update(bytes: ByteArray, count: Int) {
        checksum.update(bytes, count)
        size += count.toUInt()
    }

    override fun writeTrailer() {
        downstream.writeIntLe(checksum.value)
        downstream.writeIntLe(size.toInt())
    }
}

private abstract class DeflateDecompressingSource(
    protected val upstream: Source,
    maximumOutputBytes: Int,
) : RawSource {
    private val deflate = RawDeflate.decompressingSource(
        upstream,
        maximumOutputBytes,
    )
    private val transfer = Buffer()
    private val scratch = ByteArray(STREAM_COPY_BYTES)
    private var finished = false
    private var closed = false

    final override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Compression source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (finished) return -1

        val requested = minOf(byteCount, scratch.size.toLong())
        val read = deflate.readAtMostTo(transfer, requested)
        if (read < 0) {
            validateTrailer()
            if (!upstream.exhausted()) {
                throw RegionFormatException(
                    "Trailing bytes after compressed stream",
                )
            }
            finished = true
            return -1
        }
        val copied = transfer.readAtMostTo(
            scratch,
            endIndex = read.toInt(),
        )
        check(copied == read.toInt())
        update(scratch, copied)
        sink.write(scratch, endIndex = copied)
        return read
    }

    final override fun close() {
        if (closed) return
        closed = true
        deflate.close()
    }

    protected abstract fun update(bytes: ByteArray, count: Int)

    protected abstract fun validateTrailer()
}

private class ZlibDecompressingSource(
    source: Source,
    maximumOutputBytes: Int,
) : DeflateDecompressingSource(source, maximumOutputBytes) {
    private val checksum = Adler32()

    override fun update(bytes: ByteArray, count: Int) {
        checksum.update(bytes, count)
    }

    override fun validateTrailer() {
        val expected = readIntBigEndian(upstream, "zlib trailer")
        if (checksum.value != expected) {
            throw RegionFormatException("Invalid zlib Adler-32 checksum")
        }
    }
}

private class GzipDecompressingSource(
    source: Source,
    maximumOutputBytes: Int,
) : DeflateDecompressingSource(source, maximumOutputBytes) {
    private val checksum = Crc32()
    private var size = 0u

    override fun update(bytes: ByteArray, count: Int) {
        checksum.update(bytes, count)
        size += count.toUInt()
    }

    override fun validateTrailer() {
        val expectedChecksum = readIntLittleEndian(
            upstream,
            "gzip checksum",
        )
        if (checksum.value != expectedChecksum) {
            throw RegionFormatException("Invalid gzip CRC-32 checksum")
        }
        val expectedSize = readIntLittleEndian(upstream, "gzip size")
        if (size.toInt() != expectedSize) {
            throw RegionFormatException("Invalid gzip uncompressed size")
        }
    }
}

private class Lz4BlockCompressingSink(
    private val downstream: Sink,
) : RawSink {
    private val block = ByteArray(LZ4_BLOCK_SIZE)
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
                downstream,
                compressedLength = 0,
                originalLength = 0,
                checksum = 0,
            )
        } finally {
            closed = true
        }
    }

    private fun writeBlock() {
        writeLz4Header(
            downstream,
            compressedLength = blockSize,
            originalLength = blockSize,
            checksum = xxHash32(
                block,
                startIndex = 0,
                length = blockSize,
                seed = LZ4_XXHASH_SEED,
            ) and LZ4_CHECKSUM_MASK,
        )
        downstream.write(block, endIndex = blockSize)
        blockSize = 0
    }
}

private class Lz4BlockDecompressingSource(
    private val upstream: Source,
    private val maximumOutputBytes: Int,
) : RawSource {
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
        sink.write(
            block,
            startIndex = blockOffset,
            endIndex = blockOffset + count,
        )
        blockOffset += count
        return count.toLong()
    }

    override fun close() {
        closed = true
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
            val compressionLevel = 10 + (token and 0x0F)
            if (method != LZ4_RAW_METHOD && method != LZ4_METHOD) {
                throw RegionFormatException("Invalid LZ4Block method")
            }
            val compressedLength = upstream.readIntLe()
            val originalLength = upstream.readIntLe()
            val expectedChecksum = upstream.readIntLe()
            val maximumBlockSize = 1 shl compressionLevel
            if (
                compressedLength !in 0..maximumBlockSize ||
                originalLength !in 0..maximumBlockSize ||
                (originalLength == 0) != (compressedLength == 0) ||
                (method == LZ4_RAW_METHOD &&
                        originalLength != compressedLength)
            ) {
                throw RegionFormatException("Invalid LZ4Block lengths")
            }
            if (originalLength == 0) {
                if (expectedChecksum != 0) {
                    throw RegionFormatException(
                        "Invalid LZ4Block end checksum",
                    )
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
                decodeLz4Block(encoded, originalLength)
            }
            val actualChecksum = xxHash32(
                decoded,
                startIndex = 0,
                length = decoded.size,
                seed = LZ4_XXHASH_SEED,
            ) and LZ4_CHECKSUM_MASK
            if (actualChecksum != expectedChecksum) {
                throw RegionFormatException(
                    "Invalid LZ4Block XXHash-32",
                )
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

private class Adler32 {
    private var first = 1
    private var second = 0

    val value: Int
        get() = second shl 16 or first

    fun update(bytes: ByteArray, count: Int) {
        for (index in 0 until count) {
            first = (first + (bytes[index].toInt() and 0xFF)) %
                    ADLER_MODULUS
            second = (second + first) % ADLER_MODULUS
        }
    }
}

private class Crc32 {
    private var crc = -1

    val value: Int
        get() = crc.inv()

    fun update(value: Byte) {
        crc = crc xor (value.toInt() and 0xFF)
        repeat(Byte.SIZE_BITS) {
            crc = if (crc and 1 != 0) {
                (crc ushr 1) xor CRC32_POLYNOMIAL
            } else {
                crc ushr 1
            }
        }
    }

    fun update(bytes: ByteArray, count: Int) {
        repeat(count) { index -> update(bytes[index]) }
    }
}

private fun readZlibHeader(source: Source) {
    try {
        val methodAndInfo = source.readByte().toInt() and 0xFF
        val flags = source.readByte().toInt() and 0xFF
        if (methodAndInfo and 0x0F != DEFLATE_METHOD) {
            throw RegionFormatException(
                "Unsupported zlib compression method",
            )
        }
        if (methodAndInfo ushr 4 > 7) {
            throw RegionFormatException("Invalid zlib window size")
        }
        if ((methodAndInfo shl 8 or flags) % 31 != 0) {
            throw RegionFormatException("Invalid zlib header checksum")
        }
        if (flags and ZLIB_PRESET_DICTIONARY != 0) {
            throw RegionFormatException(
                "Preset zlib dictionaries are not supported by vanilla regions",
            )
        }
    } catch (failure: RegionFormatException) {
        throw failure
    } catch (failure: EOFException) {
        throw RegionFormatException("Truncated zlib header", failure)
    }
}

private fun readGzipHeader(source: Source) {
    val checksum = Crc32()
    fun readTracked(): Int {
        val value = source.readByte()
        checksum.update(value)
        return value.toInt() and 0xFF
    }

    try {
        if (readTracked() != 0x1F || readTracked() != 0x8B) {
            throw RegionFormatException("Invalid gzip magic")
        }
        if (readTracked() != DEFLATE_METHOD) {
            throw RegionFormatException(
                "Unsupported gzip compression method",
            )
        }
        val flags = readTracked()
        if (flags and GZIP_RESERVED_FLAGS != 0) {
            throw RegionFormatException("Invalid reserved gzip flags")
        }
        repeat(6) { readTracked() }

        if (flags and GZIP_EXTRA != 0) {
            val length = readTracked() or (readTracked() shl 8)
            repeat(length) { readTracked() }
        }
        if (flags and GZIP_NAME != 0) skipZeroTerminated(::readTracked)
        if (flags and GZIP_COMMENT != 0) skipZeroTerminated(::readTracked)
        if (flags and GZIP_HEADER_CRC != 0) {
            val expected = (source.readByte().toInt() and 0xFF) or
                    ((source.readByte().toInt() and 0xFF) shl 8)
            if (checksum.value and 0xFFFF != expected) {
                throw RegionFormatException("Invalid gzip header CRC")
            }
        }
    } catch (failure: RegionFormatException) {
        throw failure
    } catch (failure: EOFException) {
        throw RegionFormatException("Truncated gzip header", failure)
    }
}

private fun skipZeroTerminated(readByte: () -> Int) {
    while (readByte() != 0) {
        // Consume the optional zero-terminated field.
    }
}

private fun readIntBigEndian(source: Source, kind: String): Int = try {
    source.readInt()
} catch (failure: EOFException) {
    throw RegionFormatException("Truncated $kind", failure)
}

private fun readIntLittleEndian(source: Source, kind: String): Int = try {
    source.readIntLe()
} catch (failure: EOFException) {
    throw RegionFormatException("Truncated $kind", failure)
}

private fun writeLz4Header(
    sink: Sink,
    compressedLength: Int,
    originalLength: Int,
    checksum: Int,
) {
    sink.write(LZ4_MAGIC)
    sink.writeByte((LZ4_RAW_METHOD or LZ4_COMPRESSION_LEVEL).toByte())
    sink.writeIntLe(compressedLength)
    sink.writeIntLe(originalLength)
    sink.writeIntLe(checksum)
}

private fun decodeLz4Block(
    input: ByteArray,
    outputLength: Int,
): ByteArray {
    val output = ByteArray(outputLength)
    var source = 0
    var destination = 0

    while (source < input.size) {
        val token = input[source++].toInt() and 0xFF
        var literalLength = token ushr 4
        if (literalLength == 15) {
            var extension: Int
            do {
                if (source >= input.size) {
                    throw RegionFormatException(
                        "Truncated LZ4 literal length",
                    )
                }
                extension = input[source++].toInt() and 0xFF
                literalLength += extension
            } while (extension == 255)
        }
        if (
            literalLength > input.size - source ||
            literalLength > outputLength - destination
        ) {
            throw RegionFormatException("Invalid LZ4 literal range")
        }
        input.copyInto(
            output,
            destinationOffset = destination,
            startIndex = source,
            endIndex = source + literalLength,
        )
        source += literalLength
        destination += literalLength
        if (source == input.size) break

        if (source > input.size - 2) {
            throw RegionFormatException("Truncated LZ4 match offset")
        }
        val matchOffset =
            (input[source].toInt() and 0xFF) or
                    ((input[source + 1].toInt() and 0xFF) shl 8)
        source += 2
        if (matchOffset == 0 || matchOffset > destination) {
            throw RegionFormatException("Invalid LZ4 match offset")
        }

        var matchLength = token and 0x0F
        if (matchLength == 15) {
            var extension: Int
            do {
                if (source >= input.size) {
                    throw RegionFormatException(
                        "Truncated LZ4 match length",
                    )
                }
                extension = input[source++].toInt() and 0xFF
                matchLength += extension
            } while (extension == 255)
        }
        matchLength += 4
        if (matchLength > outputLength - destination) {
            throw RegionFormatException("LZ4 match exceeds output block")
        }
        var matchSource = destination - matchOffset
        repeat(matchLength) {
            output[destination++] = output[matchSource++]
        }
    }
    if (source != input.size || destination != outputLength) {
        throw RegionFormatException("LZ4 block length mismatch")
    }
    return output
}

private fun xxHash32(
    bytes: ByteArray,
    startIndex: Int,
    length: Int,
    seed: Int,
): Int {
    var offset = startIndex
    val end = startIndex + length
    var hash: Int
    if (length >= 16) {
        var first = seed + XXHASH_PRIME_1 + XXHASH_PRIME_2
        var second = seed + XXHASH_PRIME_2
        var third = seed
        var fourth = seed - XXHASH_PRIME_1
        val limit = end - 16
        while (offset <= limit) {
            first = xxHashRound(first, readIntLittleEndian(bytes, offset))
            second = xxHashRound(second, readIntLittleEndian(bytes, offset + 4))
            third = xxHashRound(third, readIntLittleEndian(bytes, offset + 8))
            fourth = xxHashRound(fourth, readIntLittleEndian(bytes, offset + 12))
            offset += 16
        }
        hash =
            first.rotateLeft(1) +
                    second.rotateLeft(7) +
                    third.rotateLeft(12) +
                    fourth.rotateLeft(18)
    } else {
        hash = seed + XXHASH_PRIME_5
    }
    hash += length
    while (offset <= end - 4) {
        hash += readIntLittleEndian(bytes, offset) * XXHASH_PRIME_3
        hash = hash.rotateLeft(17) * XXHASH_PRIME_4
        offset += 4
    }
    while (offset < end) {
        hash += (bytes[offset].toInt() and 0xFF) * XXHASH_PRIME_5
        hash = hash.rotateLeft(11) * XXHASH_PRIME_1
        offset++
    }
    hash = (hash xor (hash ushr 15)) * XXHASH_PRIME_2
    hash = (hash xor (hash ushr 13)) * XXHASH_PRIME_3
    return hash xor (hash ushr 16)
}

private fun xxHashRound(accumulator: Int, value: Int): Int =
    (accumulator + value * XXHASH_PRIME_2)
        .rotateLeft(13) * XXHASH_PRIME_1

private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun closeTransformPreserving(
    failure: Throwable?,
    close: () -> Unit,
) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        if (failure == null) throw closeFailure
        failure.addSuppressed(closeFailure)
    }
}

private val GZIP_FIXED_HEADER = byteArrayOf(
    0x1F,
    0x8B.toByte(),
    DEFLATE_METHOD.toByte(),
    0,
    0,
    0,
    0,
    0,
    0,
    0xFF.toByte(),
)
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

private const val STREAM_COPY_BYTES = 8_192
private const val DEFLATE_METHOD = 8
private const val ZLIB_PRESET_DICTIONARY = 0x20
private const val ADLER_MODULUS = 65_521
private const val GZIP_HEADER_CRC = 0x02
private const val GZIP_EXTRA = 0x04
private const val GZIP_NAME = 0x08
private const val GZIP_COMMENT = 0x10
private const val GZIP_RESERVED_FLAGS = 0xE0
private const val CRC32_POLYNOMIAL = 0xEDB88320.toInt()
private const val LZ4_BLOCK_SIZE = 1 shl 16
private const val LZ4_COMPRESSION_LEVEL = 6
private const val LZ4_RAW_METHOD = 0x10
private const val LZ4_METHOD = 0x20
private const val LZ4_XXHASH_SEED = 0x9747B28C.toInt()

// lz4-java's LZ4Block stream obtains the hash through asChecksum(), whose
// wire-compatible value is masked to 28 bits.
private const val LZ4_CHECKSUM_MASK = 0x0FFF_FFFF
private const val XXHASH_PRIME_1 = 0x9E3779B1.toInt()
private const val XXHASH_PRIME_2 = 0x85EBCA77.toInt()
private const val XXHASH_PRIME_3 = 0xC2B2AE3D.toInt()
private const val XXHASH_PRIME_4 = 0x27D4EB2F
private const val XXHASH_PRIME_5 = 0x165667B1
