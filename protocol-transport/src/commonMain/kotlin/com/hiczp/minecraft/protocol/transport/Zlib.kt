package com.hiczp.minecraft.protocol.transport

import kotlinx.io.*

internal object Zlib {
    fun compressingSink(sink: Sink): RawSink =
        platformZlibCompressingSink(sink)

    fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        return MaximumOutputRawSource(
            platformZlibDecompressingSource(source),
            maximumOutputBytes,
        )
    }

    fun compressToSink(source: Source, sink: Sink): Long =
        mapCompressionFailure("Cannot deflate packet") {
            compressingSink(sink).buffered().use { compressed ->
                source.transferTo(compressed)
            }
        }

    fun decompressToSink(
        source: Source,
        sink: Sink,
        expectedSize: Int,
    ): Long = mapCompressionFailure(
        "Invalid zlib-compressed packet of declared size $expectedSize",
    ) {
        require(expectedSize >= 0)
        decompressingSource(source, expectedSize).buffered().use { decompressed ->
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

// Compression decorators must be closed to emit their trailer, but the
// transport streaming API promises not to close its caller-owned sink. This
// ownership guard lets the library decorator finalize without closing it.
internal fun RawSink.callerOwned(): RawSink = CallerOwnedRawSink(this)

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

// Read one byte beyond the declared size so an oversized peer payload is
// rejected even when the caller asks for exactly the advertised byte count.
private class MaximumOutputRawSource(
    private val delegate: RawSource,
    maximumOutputBytes: Int,
) : RawSource {
    private val maximumOutputBytes = maximumOutputBytes.toLong()
    private var outputBytes = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        val remaining = maximumOutputBytes - outputBytes
        val read = delegate.readAtMostTo(
            sink,
            minOf(byteCount, remaining + 1),
        )
        if (read < 0) return -1
        outputBytes += read
        if (outputBytes > maximumOutputBytes) {
            throw MinecraftTransportException(
                "Compressed packet exceeds declared size $maximumOutputBytes",
            )
        }
        return read
    }

    override fun close() {
        delegate.close()
    }
}

// Library and platform codecs expose different internal failure classes. The
// public transport boundary consistently reports MinecraftTransportException.
private inline fun <T> mapCompressionFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: MinecraftTransportException) {
    throw failure
} catch (failure: Exception) {
    throw MinecraftTransportException(message, failure)
}
