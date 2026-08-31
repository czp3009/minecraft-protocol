package com.hiczp.minecraft.protocol.transport

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
    sink.callerOwned().asOkioSink().deflate(Deflater())
        .asKotlinxIoRawSink()

internal actual fun platformZlibDecompressingSource(
    source: Source,
): RawSource {
    // Okio's inflater may read ahead into RFC 1950's four-byte Adler trailer.
    // Retaining that trailer until raw DEFLATE completion keeps Okio in charge
    // of inflation while still making exact framed-stream validation possible.
    val zlibTrailerRetainingSource = ZlibTrailerRetainingSource(
        source.callerOwned().asOkioSource(),
    )
    val inflaterSource = zlibTrailerRetainingSource.inflate(Inflater())
    return ExactZlibRawSource(
        inflaterSource.asKotlinxIoRawSource(),
        zlibTrailerRetainingSource,
    )
}

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
    }
}

// Okio accepts a completed ZLIB member without requiring the caller's source
// to end there. Minecraft packets contain exactly one member, so reject junk
// or concatenated members after the library reaches end-of-stream.
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
