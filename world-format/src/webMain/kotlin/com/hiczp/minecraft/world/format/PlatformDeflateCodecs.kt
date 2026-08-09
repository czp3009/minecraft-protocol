package com.hiczp.minecraft.world.format

import dev.karmakrafts.kompress.*
import dev.karmakrafts.kompress.crc.CRC32
import dev.karmakrafts.kompress.deflate.Deflater
import dev.karmakrafts.kompress.deflate.Inflater
import dev.karmakrafts.kompress.zlib.ZlibDecompressor
import dev.karmakrafts.kompress.zlib.zlibSink
import kotlinx.io.*
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSink
import kotlinx.io.okio.asOkioSource
import okio.Sink as OkioSink
import okio.Source as OkioSource

internal actual fun platformZlibCompressingSink(sink: OkioSink): OkioSink =
    sink.callerOwned()
        .asKotlinxIoRawSink()
        .zlibSink(level = KOMPRESS_SAFE_LEVEL)
        .asOkioSink()

internal actual fun platformZlibDecompressingSource(
    source: OkioSource,
): OkioSource {
    val compressed = source.asKotlinxIoRawSource().buffered()
    val decompressor = ZlibDecompressor()
    val decoded = compressed.decompressingSource(
        decompressor = decompressor,
        isSourceOwned = false,
    )
    return ExactKompressFramedRawSource(
        compressed = compressed,
        decoded = decoded,
        decompressor = decompressor,
        formatName = "zlib",
    ).asOkioSource()
}

internal actual fun platformGzipCompressingSink(sink: OkioSink): OkioSink =
// Kompress's public GZIP convenience API is archive/callback-oriented and
// cannot implement world-format's incremental synchronous Sink contract.
// This uses Kompress's official framing base, Deflater, and CRC32; only the
    // RFC 1952 stream header/trailer adaptation below is project-owned.
    sink.asKotlinxIoRawSink()
        .compressingSink(
            compressor = GzipCompressor(),
            isSinkOwned = false,
        )
        .asOkioSink()

internal actual fun platformGzipDecompressingSource(
    source: OkioSource,
): OkioSource {
    val compressed = GzipTrailerRetainingRawSource(
        source.asKotlinxIoRawSource(),
    ).buffered()
    val decompressor = GzipDecompressor()
    val decoded = compressed.decompressingSource(
        decompressor = decompressor,
        isSourceOwned = false,
    )
    return ExactKompressFramedRawSource(
        compressed = compressed,
        decoded = decoded,
        decompressor = decompressor,
        formatName = "gzip",
    ).asOkioSource()
}

// Kompress's generic framing source can give raw Inflater a chunk containing
// the RFC 1952 epilogue. On a valid multi-block stream Inflater may read ahead
// across the DEFLATE end marker, leaving FramingDecompressor no complete
// eight-byte trailer. Hold only that trailer until raw completion and then
// release it incrementally; Kompress still performs DEFLATE and CRC32.
private class GzipTrailerRetainingRawSource(
    private val upstream: RawSource,
) : RawSource {
    private val retained = Buffer()
    private var upstreamExhausted = false
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Gzip source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        while (true) {
            if (upstreamExhausted) {
                if (retained.size == 0L) return -1
                return retained.readAtMostTo(sink, minOf(byteCount, 1))
            }

            val available = retained.size - GZIP_TRAILER_BYTES
            if (available > 0L) {
                return retained.readAtMostTo(
                    sink,
                    minOf(byteCount, available),
                )
            }

            if (upstream.readAtMostTo(retained, STREAM_BUFFER_BYTES) < 0) {
                upstreamExhausted = true
            }
        }
    }

    override fun close() {
        closed = true
    }
}

// Kompress exposes remaining bytes but does not reject content after a valid
// framed member. Region payloads contain exactly one member, so require both
// zero decoder remainder and exhaustion of the converted caller source.
private class ExactKompressFramedRawSource(
    private val compressed: Source,
    private val decoded: RawSource,
    private val decompressor: Decompressor,
    private val formatName: String,
) : RawSource {
    private var finished = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val read = decoded.readAtMostTo(sink, byteCount)
        if (read < 0 && !finished) {
            finished = true
            if (decompressor.remaining != 0 || !compressed.exhausted()) {
                throw kotlinx.io.IOException(
                    "Trailing bytes after $formatName stream",
                )
            }
        }
        return read
    }

    override fun close() {
        decoded.close()
    }
}

/*
 * Kompress supplies the streaming framing lifecycle, raw Deflater/Inflater,
 * and CRC32 implementation. These paired classes only describe RFC 1952's
 * header fields, optional-field traversal, trailer layout, and size check
 * because the library has no direct synchronous streaming GZIP decorator.
 */
private class GzipCompressor : FramingCompressor(
    Deflater(level = KOMPRESS_SAFE_LEVEL),
) {
    private val crc32 = CRC32()
    private var uncompressedSize = 0u

    override fun appendPrologue() {
        buffer.write(GZIP_FIXED_HEADER)
    }

    override fun onDataRead(offset: Int, size: Int) {
        crc32.round(input, offset, size)
        uncompressedSize += size.toUInt()
    }

    override fun appendEpilogue() {
        buffer.writeIntLe(crc32.finalize().toInt())
        buffer.writeIntLe(uncompressedSize.toInt())
    }

    override fun reset() {
        super.reset()
        crc32.reset()
        uncompressedSize = 0u
    }
}

private class GzipDecompressor : FramingDecompressor(Inflater()) {
    private val crc32 = CRC32()
    private var outputSize = 0u

    override fun consumePrologue(): Boolean {
        // FramingDecompressor may ask again when a header is split across
        // reads. Parse a copy first so an incomplete header consumes nothing.
        val copy = buffer.copy()
        val initialSize = copy.size
        try {
            readGzipHeader(copy)
        } catch (_: EOFException) {
            return false
        }
        buffer.skip(initialSize - copy.size)
        return true
    }

    override fun onDataWritten(output: ByteArray, offset: Int, size: Int) {
        crc32.round(output, offset, size)
        outputSize += size.toUInt()
    }

    override fun consumeEpilogue(): Boolean {
        if (buffer.size < GZIP_TRAILER_BYTES) return false
        val expectedCrc32 = buffer.readIntLe()
        val expectedSize = buffer.readIntLe()
        if (crc32.finalize().toInt() != expectedCrc32) {
            throw kotlinx.io.IOException("Invalid gzip CRC-32 checksum")
        }
        if (outputSize.toInt() != expectedSize) {
            throw kotlinx.io.IOException("Invalid gzip uncompressed size")
        }
        return true
    }

    override fun reset() {
        super.reset()
        crc32.reset()
        outputSize = 0u
    }
}

private fun readGzipHeader(source: Source) {
    val crc32 = CRC32()
    fun readTracked(): Int {
        val value = source.readByte()
        crc32.round(value)
        return value.toInt() and 0xFF
    }

    if (readTracked() != 0x1F || readTracked() != 0x8B) {
        throw kotlinx.io.IOException("Invalid gzip magic")
    }
    if (readTracked() != DEFLATE_METHOD) {
        throw kotlinx.io.IOException("Unsupported gzip compression method")
    }
    val flags = readTracked()
    if (flags and GZIP_RESERVED_FLAGS != 0) {
        throw kotlinx.io.IOException("Invalid reserved gzip flags")
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
        if (crc32.finalize().toInt() and 0xFFFF != expected) {
            throw kotlinx.io.IOException("Invalid gzip header CRC")
        }
    }
}

private fun skipZeroTerminated(readByte: () -> Int) {
    while (readByte() != 0) {
        // Consume the optional zero-terminated field.
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

private const val GZIP_TRAILER_BYTES = 8L
private const val STREAM_BUFFER_BYTES = 8_192L
private const val DEFLATE_METHOD = 8
private const val GZIP_HEADER_CRC = 0x02
private const val GZIP_EXTRA = 0x04
private const val GZIP_NAME = 0x08
private const val GZIP_COMMENT = 0x10
private const val GZIP_RESERVED_FLAGS = 0xE0

// Kompress 2.3.1 can emit invalid dynamic code-length trees for highly
// skewed NBT data. Its documented minimum level selects fixed Huffman blocks
// for both framed formats without changing their streaming contracts.
private const val KOMPRESS_SAFE_LEVEL = Deflater.MIN_LEVEL
