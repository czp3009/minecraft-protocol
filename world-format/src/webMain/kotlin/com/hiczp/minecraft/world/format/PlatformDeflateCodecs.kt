package com.hiczp.minecraft.world.format

import dev.karmakrafts.kompress.*
import dev.karmakrafts.kompress.crc.CRC32
import dev.karmakrafts.kompress.deflate.Deflater
import dev.karmakrafts.kompress.deflate.Inflater
import dev.karmakrafts.kompress.exception.KompressException
import dev.karmakrafts.kompress.zlib.ZlibDecompressor
import dev.karmakrafts.kompress.zlib.zlibSink
import kotlinx.io.*

internal actual fun platformZlibCompressingSink(sink: Sink): RawSink =
    mapKompressFailure("Cannot create zlib compression stream") {
        sink.callerOwned()
            .zlibSink(level = KOMPRESS_SAFE_LEVEL)
            .withKotlinxIoExceptions("Cannot compress zlib stream")
    }

internal actual fun platformZlibDecompressingSource(
    source: Source,
): RawSource {
    val decompressor = ZlibDecompressor()
    return mapKompressFailure("Cannot create zlib decompression stream") {
        val decoded = source.decompressingSource(
            decompressor = decompressor,
            isSourceOwned = false,
        )
        ExactKompressFramedRawSource(
            compressed = source,
            decoded = decoded,
            decompressor = decompressor,
            formatName = "zlib",
        ).withKotlinxIoExceptions("Invalid zlib stream")
    }
}

internal actual fun platformGzipCompressingSink(sink: Sink): RawSink =
// kompress-gzip exposes only an archive callback that must synchronously
// finish an entry. It cannot back caller-driven RawSink/RawSource APIs
// without staging the complete payload, which would defeat streaming and add
// a whole-payload allocation. Use Kompress's official framing base, Deflater,
    // Inflater, and CRC32; only the RFC 1952 adaptation below is project-owned.
    mapKompressFailure("Cannot create gzip compression stream") {
        sink.callerOwned()
            .compressingSink(
                compressor = GzipCompressor(),
                isSinkOwned = true,
            )
            .withKotlinxIoExceptions("Cannot compress gzip stream")
    }

internal actual fun platformGzipDecompressingSource(
    source: Source,
): RawSource {
    val compressed = GzipTrailerRetainingRawSource(
        source.callerOwned(),
    ).buffered()
    val decompressor = GzipDecompressor()
    return mapKompressFailure("Cannot create gzip decompression stream") {
        val decoded = compressed.decompressingSource(
            decompressor = decompressor,
            isSourceOwned = true,
        )
        ExactKompressFramedRawSource(
            compressed = compressed,
            decoded = decoded,
            decompressor = decompressor,
            formatName = "gzip",
        ).withKotlinxIoExceptions("Invalid gzip stream")
    }
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
                throw IOException(
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

// Kompress exposes its own runtime exception for codec failures. Translate
// only that documented backend type at the web implementation boundary; input
// validation, cancellation, and unrelated programming errors stay untouched.
private fun RawSink.withKotlinxIoExceptions(message: String): RawSink =
    KotlinxIoExceptionMappingRawSink(this, message)

private fun RawSource.withKotlinxIoExceptions(message: String): RawSource =
    KotlinxIoExceptionMappingRawSource(this, message)

private class KotlinxIoExceptionMappingRawSink(
    private val delegate: RawSink,
    private val message: String,
) : RawSink {
    override fun write(source: Buffer, byteCount: Long) =
        mapKompressFailure(message) {
            delegate.write(source, byteCount)
        }

    override fun flush() = mapKompressFailure(message, delegate::flush)

    override fun close() = mapKompressFailure(message, delegate::close)
}

private class KotlinxIoExceptionMappingRawSource(
    private val delegate: RawSource,
    private val message: String,
) : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        mapKompressFailure(message) {
            delegate.readAtMostTo(sink, byteCount)
        }

    override fun close() = mapKompressFailure(message, delegate::close)
}

private inline fun <T> mapKompressFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: KompressException) {
    throw IOException(message, failure)
}

/*
 * Kompress supplies the streaming framing lifecycle, raw Deflater/Inflater,
 * and CRC32 implementation. These paired classes only describe RFC 1952's
 * header fields, optional-field traversal, trailer layout, and size check
 * because kompress-gzip has no caller-driven streaming GZIP decorator.
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
            throw IOException("Invalid gzip CRC-32 checksum")
        }
        if (outputSize.toInt() != expectedSize) {
            throw IOException("Invalid gzip uncompressed size")
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
        throw IOException("Invalid gzip magic")
    }
    if (readTracked() != DEFLATE_METHOD) {
        throw IOException("Unsupported gzip compression method")
    }
    val flags = readTracked()
    if (flags and GZIP_RESERVED_FLAGS != 0) {
        throw IOException("Invalid reserved gzip flags")
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
            throw IOException("Invalid gzip header CRC")
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
