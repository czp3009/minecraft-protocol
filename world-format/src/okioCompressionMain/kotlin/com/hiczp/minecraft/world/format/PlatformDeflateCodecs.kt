package com.hiczp.minecraft.world.format

import okio.*

internal actual fun platformZlibCompressingSink(sink: Sink): Sink =
    sink.callerOwned().deflate(Deflater())

internal actual fun platformZlibDecompressingSource(source: Source): Source {
    // Okio's inflater may read ahead into RFC 1950's four-byte Adler trailer.
    // Retain that trailer until raw DEFLATE completion so exact member
    // validation remains possible without replacing Okio's codec.
    val compressed = ZlibTrailerRetainingSource(source)
    return ExactZlibSource(
        delegate = compressed.inflate(Inflater()),
        compressed = compressed,
    )
}

internal actual fun platformGzipCompressingSink(sink: Sink): Sink =
    sink.callerOwned().gzip()

internal actual fun platformGzipDecompressingSource(source: Source): Source =
    source.callerOwned().gzip()

private class ZlibTrailerRetainingSource(
    private val upstream: Source,
) : Source {
    private val retained = Buffer()
    private var upstreamExhausted = false
    private var closed = false

    val fullyConsumed: Boolean
        get() = upstreamExhausted && retained.size == 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Zlib source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        while (true) {
            if (upstreamExhausted) {
                if (retained.size == 0L) return -1
                return retained.read(sink, minOf(byteCount, 1))
            }

            val available = retained.size - ZLIB_TRAILER_BYTES
            if (available > 0L) {
                return retained.read(sink, minOf(byteCount, available))
            }

            if (upstream.read(retained, STREAM_BUFFER_BYTES) < 0) {
                upstreamExhausted = true
            }
        }
    }

    override fun timeout(): Timeout = upstream.timeout()

    override fun close() {
        closed = true
        retained.clear()
    }
}

// A region compression payload contains exactly one ZLIB member. Okio accepts
// a completed member without requiring source exhaustion, so reject remaining
// junk or a concatenated member at the world-format boundary.
private class ExactZlibSource(
    private val delegate: Source,
    private val compressed: ZlibTrailerRetainingSource,
) : Source {
    private var finished = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = delegate.read(sink, byteCount)
        if (read < 0 && !finished) {
            finished = true
            if (!compressed.fullyConsumed) {
                throw okio.IOException("Trailing bytes after zlib stream")
            }
        }
        return read
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() {
        delegate.close()
    }
}

private const val STREAM_BUFFER_BYTES = 8_192L
private const val ZLIB_TRAILER_BYTES = 4L
