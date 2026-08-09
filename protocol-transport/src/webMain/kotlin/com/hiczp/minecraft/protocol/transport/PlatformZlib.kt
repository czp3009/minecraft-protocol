package com.hiczp.minecraft.protocol.transport

import dev.karmakrafts.kompress.decompressingSource
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
    val decompressor = ZlibDecompressor()
    return mapKompressFailure("Cannot create zlib decompression stream") {
        val decoded = source.decompressingSource(
            decompressor = decompressor,
            isSourceOwned = false,
        )
        ExactKompressZlibRawSource(source, decoded, decompressor)
            .withKotlinxIoExceptions("Invalid zlib stream")
    }
}

// Kompress reports how many bytes remain in its last input block but does not
// itself reject bytes after a completed member. Minecraft's envelope permits
// exactly one ZLIB stream, so combine that signal with source exhaustion.
private class ExactKompressZlibRawSource(
    private val compressed: Source,
    private val decoded: RawSource,
    private val decompressor: ZlibDecompressor,
) : RawSource {
    private var finished = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val read = decoded.readAtMostTo(sink, byteCount)
        if (read < 0 && !finished) {
            finished = true
            if (decompressor.remaining != 0 || !compressed.exhausted()) {
                throw kotlinx.io.IOException("Trailing bytes after zlib stream")
            }
        }
        return read
    }

    override fun close() {
        decoded.close()
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
