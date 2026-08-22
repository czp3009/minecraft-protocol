package com.hiczp.minecraft.protocol.transport

import dev.karmakrafts.kompress.deflate.Deflater
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
    // Kompress 2.3.1's generic chunked decompressingSource can finish a
    // DEFLATE input chunk before all of a split ZLIB epilogue reaches its
    // FramingDecompressor. Minecraft has already bounded and staged the exact
    // compressed frame body, so provide that complete member in one input;
    // Kompress still owns ZLIB validation and the DEFLATE implementation.
    val member = source.readByteArray()
    val decompressor = ZlibDecompressor()
    return try {
        mapKompressFailure("Cannot create zlib decompression stream") {
            decompressor.setInput(member)
            decompressor.finish()
            ExactKompressZlibRawSource(decompressor)
                .withKotlinxIoExceptions("Invalid zlib stream")
        }
    } catch (failure: Throwable) {
        decompressor.close()
        throw failure
    }
}

private class ExactKompressZlibRawSource(
    private val decompressor: ZlibDecompressor,
) : RawSource {
    private var closed = false
    private var finished = false
    private val output = ByteArray(DECOMPRESSION_BUFFER_BYTES)
    private val pending = Buffer()

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Zlib source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (!pending.exhausted()) {
            return pending.readAtMostTo(sink, byteCount)
        }
        if (finished) return -1

        val read = decompressor.decompress(output)
        if (read > 0) {
            pending.write(output, endIndex = read)
            return pending.readAtMostTo(sink, byteCount)
        }
        if (decompressor.finished) {
            finished = true
            if (decompressor.remaining != 0) {
                throw kotlinx.io.IOException("Trailing bytes after zlib stream")
            }
            return -1
        }
        throw kotlinx.io.IOException("Zlib decompressor made no progress")
    }

    override fun close() {
        if (closed) return
        decompressor.close()
        closed = true
    }
}

// Kompress's kotlinx-io decorators expose KompressException directly. Map
// only that documented backend hierarchy so every transport target exposes
// codec I/O failures through kotlinx.io without intercepting cancellation or
// unrelated caller failures.
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

// Kompress 2.3.1 can emit invalid dynamic code-length trees for highly
// skewed registry payloads. Its documented minimum level selects fixed
// Huffman blocks while preserving the same streaming ZLIB API.
private const val KOMPRESS_SAFE_LEVEL = Deflater.MIN_LEVEL
private const val DECOMPRESSION_BUFFER_BYTES = 4_096
