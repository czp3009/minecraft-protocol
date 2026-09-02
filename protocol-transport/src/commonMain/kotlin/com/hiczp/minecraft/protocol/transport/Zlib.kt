package com.hiczp.minecraft.protocol.transport

import kotlinx.io.*

internal object Zlib {
    fun compressingSink(sink: Sink): RawSink =
        platformZlibCompressingSink(sink)

    fun decompressingSource(
        source: Source,
        expectedOutputBytes: Int,
    ): RawSource {
        require(expectedOutputBytes >= 0)
        return ExactOutputRawSource(
            platformZlibDecompressingSource(source),
            expectedOutputBytes,
        )
    }

    fun compressToSink(source: Source, sink: Sink): Long =
        compressingSink(sink).buffered().use { compressed ->
            source.transferTo(compressed)
        }

    fun decompressToSink(
        source: Source,
        sink: Sink,
        expectedSize: Int,
    ): Long {
        return decompressingSource(
            source,
            expectedSize,
        ).buffered().use { decompressed ->
            val count = decompressed.transferTo(sink)
            if (count != expectedSize.toLong()) {
                throw MinecraftTransportException(
                    "Compressed packet produced $count bytes; declared size is $expectedSize",
                )
            }
            count
        }
    }

    fun compress(input: ByteArray): ByteArray {
        val source = Buffer().apply { write(input) }
        val sink = Buffer()
        compressToSink(source, sink)
        return sink.readByteArray()
    }

    fun decompress(
        input: ByteArray,
        expectedSize: Int,
    ): ByteArray {
        val source = Buffer().apply { write(input) }
        val sink = Buffer()
        decompressToSink(source, sink, expectedSize)
        return sink.readByteArray()
    }
}

internal expect fun platformZlibCompressingSink(sink: Sink): RawSink

internal expect fun platformZlibDecompressingSource(source: Source): RawSource

// Compression decorators must close to emit or validate their framing, but
// the transport streaming API promises not to close caller-owned endpoints.
// These guards separate the decorator lifecycle from the caller's lifecycle.
internal fun RawSink.callerOwned(): RawSink = CallerOwnedRawSink(this)

internal fun RawSource.callerOwned(): RawSource = CallerOwnedRawSource(this)

private class CallerOwnedRawSink(
    private val delegate: RawSink,
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
    private val delegate: RawSource,
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

// Read one byte beyond the declared size so an oversized peer payload is
// rejected even when the caller asks for exactly the advertised byte count.
private class ExactOutputRawSource(
    private val delegate: RawSource,
    expectedOutputBytes: Int,
) : RawSource {
    private val expectedOutputBytes = expectedOutputBytes.toLong()
    private var outputBytes = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        val remaining = expectedOutputBytes - outputBytes
        val read = delegate.readAtMostTo(
            sink,
            minOf(byteCount, remaining + 1),
        )
        if (read < 0) return -1
        outputBytes += read
        if (outputBytes > expectedOutputBytes) {
            throw MinecraftTransportException(
                "Compressed packet exceeds declared size $expectedOutputBytes",
            )
        }
        return read
    }

    override fun close() {
        delegate.close()
    }
}
