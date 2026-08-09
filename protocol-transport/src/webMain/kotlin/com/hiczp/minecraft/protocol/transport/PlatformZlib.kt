package com.hiczp.minecraft.protocol.transport

import dev.karmakrafts.kompress.decompressingSource
import dev.karmakrafts.kompress.deflate.Deflater
import dev.karmakrafts.kompress.zlib.ZlibDecompressor
import dev.karmakrafts.kompress.zlib.zlibSink
import kotlinx.io.*

internal actual fun platformZlibCompressingSink(sink: Sink): RawSink =
    sink.callerOwned().zlibSink(level = KOMPRESS_SAFE_LEVEL)

internal actual fun platformZlibDecompressingSource(
    source: Source,
): RawSource {
    val decompressor = ZlibDecompressor()
    val decoded = source.decompressingSource(
        decompressor = decompressor,
        isSourceOwned = false,
    )
    return ExactKompressZlibRawSource(source, decoded, decompressor)
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

// Kompress 2.3.1 can emit invalid dynamic code-length trees for highly
// skewed registry payloads. Its documented minimum level selects fixed
// Huffman blocks while preserving the same streaming ZLIB API.
private const val KOMPRESS_SAFE_LEVEL = Deflater.MIN_LEVEL
