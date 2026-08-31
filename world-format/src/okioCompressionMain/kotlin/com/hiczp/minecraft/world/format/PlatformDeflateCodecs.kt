package com.hiczp.minecraft.world.format

import kotlinx.io.*
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSink
import kotlinx.io.okio.asOkioSource
import okio.*
import kotlinx.io.IOException as KotlinxIOException
import okio.Buffer as OkioBuffer
import okio.Source as OkioSource

internal actual fun platformZlibCompressingSink(sink: Sink): RawSink =
    sink.callerOwned()
        .asOkioSink()
        .deflate(Deflater())
        .asKotlinxIoRawSink()

internal actual fun platformZlibDecompressingSource(
    source: Source,
): RawSource {
    // Okio's inflater may read ahead into RFC 1950's four-byte Adler trailer.
    // Retain that trailer until raw DEFLATE completion so exact member
    // validation remains possible without replacing Okio's codec.
    val compressed = ZlibTrailerRetainingSource(
        source.callerOwned().asOkioSource(),
    )
    return ExactZlibRawSource(
        delegate = compressed.inflate(Inflater()).asKotlinxIoRawSource(),
        compressed = compressed,
    )
}

internal actual fun platformGzipCompressingSink(sink: Sink): RawSink =
    sink.callerOwned()
        .asOkioSink()
        .gzip()
        .asKotlinxIoRawSink()

internal actual fun platformGzipDecompressingSource(
    source: Source,
): RawSource = source.callerOwned()
    .asOkioSource()
    .gzip()
    .asKotlinxIoRawSource()

private class ZlibTrailerRetainingSource(
    private val upstream: OkioSource,
) : OkioSource {
    private val retained = OkioBuffer()
    private var upstreamExhausted = false
    private var closed = false

    val fullyConsumed: Boolean
        get() = upstreamExhausted && retained.size == 0L

    override fun read(sink: OkioBuffer, byteCount: Long): Long {
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

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
        retained.clear()
    }
}

// A region compression payload contains exactly one ZLIB member. Okio accepts
// a completed member without requiring source exhaustion, so reject remaining
// junk or a concatenated member at the world-format boundary.
private class ExactZlibRawSource(
    private val delegate: RawSource,
    private val compressed: ZlibTrailerRetainingSource,
) : RawSource {
    private var finished = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val read = delegate.readAtMostTo(sink, byteCount)
        if (read < 0 && !finished) {
            finished = true
            if (!compressed.fullyConsumed) {
                throw KotlinxIOException("Trailing bytes after zlib stream")
            }
        }
        return read
    }

    override fun close() {
        delegate.close()
    }
}

private const val STREAM_BUFFER_BYTES = 8_192L
private const val ZLIB_TRAILER_BYTES = 4L
