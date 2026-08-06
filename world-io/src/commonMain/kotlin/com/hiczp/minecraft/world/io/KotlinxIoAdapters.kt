package com.hiczp.minecraft.world.io

import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.RawSink as KotlinxRawSink
import kotlinx.io.RawSource as KotlinxRawSource
import okio.Buffer as OkioBuffer
import okio.Sink as OkioSink
import okio.Source as OkioSource

internal class OkioToKotlinxRawSource(
    private val upstream: OkioSource,
    private val closeUpstream: Boolean = true,
) : KotlinxRawSource {
    private val transfer = OkioBuffer()
    private val scratch = ByteArray(FILE_STREAM_BUFFER_BYTES)
    private var closed = false

    override fun readAtMostTo(sink: KotlinxBuffer, byteCount: Long): Long {
        check(!closed) { "Source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        val requested = minOf(byteCount, scratch.size.toLong())
        val read = upstream.read(transfer, requested)
        if (read < 0) return -1
        if (read == 0L) {
            throw WorldIOException("Okio source made no progress")
        }
        var copied = 0
        while (copied < read) {
            val count = transfer.read(
                sink = scratch,
                offset = 0,
                byteCount = minOf(read.toInt() - copied, scratch.size),
            )
            check(count > 0)
            sink.write(scratch, endIndex = count)
            copied += count
        }
        return read
    }

    override fun close() {
        if (closed) return
        closed = true
        if (closeUpstream) upstream.close()
    }
}

internal class KotlinxToOkioRawSink(
    private val downstream: OkioSink,
    private val closeDownstream: Boolean = true,
) : KotlinxRawSink {
    private val transfer = OkioBuffer()
    private val scratch = ByteArray(FILE_STREAM_BUFFER_BYTES)
    private var closed = false

    override fun write(source: KotlinxBuffer, byteCount: Long) {
        check(!closed) { "Sink is closed" }
        require(byteCount in 0..source.size)
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, scratch.size.toLong()).toInt()
            val read = source.readAtMostTo(scratch, endIndex = count)
            check(read > 0)
            transfer.write(scratch, offset = 0, byteCount = read)
            downstream.write(transfer, read.toLong())
            remaining -= read
        }
    }

    override fun flush() {
        check(!closed) { "Sink is closed" }
        downstream.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (closeDownstream) downstream.close()
    }
}

private const val FILE_STREAM_BUFFER_BYTES = 8_192
