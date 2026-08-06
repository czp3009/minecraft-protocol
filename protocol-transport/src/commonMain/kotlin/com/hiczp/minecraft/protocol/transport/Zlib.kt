package com.hiczp.minecraft.protocol.transport

import com.hiczp.minecraft.compression.RawDeflate
import com.hiczp.minecraft.compression.RawDeflateException
import kotlinx.io.*

internal object Zlib {
    fun compressingSink(sink: Sink): RawSink {
        sink.writeByte(0x78)
        sink.writeByte(0x9C.toByte())
        return ZlibCompressingRawSink(sink)
    }

    fun decompressingSource(
        source: Source,
        maximumOutputBytes: Int,
    ): RawSource {
        require(maximumOutputBytes >= 0)
        readHeader(source)
        return ZlibDecompressingRawSource(source, maximumOutputBytes)
    }

    fun compressToSink(source: Source, sink: Sink): Long {
        val compressed = compressingSink(sink).buffered()
        var failure: Throwable? = null
        return try {
            source.transferTo(compressed)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closePreserving(failure, compressed::close)
        }
    }

    fun decompressToSink(
        source: Source,
        sink: Sink,
        expectedSize: Int,
    ): Long {
        require(expectedSize >= 0)
        val decompressed =
            decompressingSource(source, expectedSize).buffered()
        var failure: Throwable? = null
        return try {
            val count = decompressed.transferTo(sink)
            if (count != expectedSize.toLong()) {
                throw MinecraftTransportException(
                    "Compressed packet produced $count bytes; declared size is $expectedSize",
                )
            }
            count
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closePreserving(failure, decompressed::close)
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

    private fun readHeader(source: Source) {
        try {
            val methodAndInfo = source.readByte().toInt() and 0xFF
            val flags = source.readByte().toInt() and 0xFF
            if (methodAndInfo and 0x0F != COMPRESSION_METHOD_DEFLATE) {
                throw MinecraftTransportException(
                    "Unsupported zlib compression method",
                )
            }
            if (methodAndInfo ushr 4 > 7) {
                throw MinecraftTransportException("Invalid zlib window size")
            }
            if ((methodAndInfo shl 8 or flags) % 31 != 0) {
                throw MinecraftTransportException(
                    "Invalid zlib header checksum",
                )
            }
            if (flags and PRESET_DICTIONARY != 0) {
                throw MinecraftTransportException(
                    "Minecraft packets cannot use a preset zlib dictionary",
                )
            }
        } catch (failure: MinecraftTransportException) {
            throw failure
        } catch (failure: EOFException) {
            throw MinecraftTransportException("Truncated zlib header", failure)
        }
    }
}

private class ZlibCompressingRawSink(
    private val downstream: Sink,
) : RawSink {
    private val deflate = RawDeflate.compressingSink(downstream)
    private val checksum = Adler32()
    private val transfer = Buffer()
    private val scratch = ByteArray(STREAM_COPY_BYTES)
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Zlib sink is closed" }
        require(byteCount in 0..source.size)
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, scratch.size.toLong()).toInt()
            val read = source.readAtMostTo(scratch, endIndex = count)
            check(read > 0)
            checksum.update(scratch, read)
            transfer.write(scratch, endIndex = read)
            try {
                deflate.write(transfer, read.toLong())
            } catch (failure: RawDeflateException) {
                throw MinecraftTransportException(
                    "Cannot deflate packet",
                    failure,
                )
            }
            remaining -= read
        }
    }

    override fun flush() {
        check(!closed) { "Zlib sink is closed" }
        try {
            deflate.flush()
        } catch (failure: RawDeflateException) {
            throw MinecraftTransportException("Cannot deflate packet", failure)
        }
    }

    override fun close() {
        if (closed) return
        try {
            try {
                deflate.close()
            } catch (failure: RawDeflateException) {
                throw MinecraftTransportException(
                    "Cannot deflate packet",
                    failure,
                )
            }
            downstream.writeInt(checksum.value)
        } finally {
            closed = true
        }
    }
}

private class ZlibDecompressingRawSource(
    private val upstream: Source,
    maximumOutputBytes: Int,
) : RawSource {
    private val deflate = RawDeflate.decompressingSource(
        upstream,
        maximumOutputBytes,
    )
    private val checksum = Adler32()
    private val transfer = Buffer()
    private val scratch = ByteArray(STREAM_COPY_BYTES)
    private var finished = false
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Zlib source is closed" }
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (finished) return -1

        val read = try {
            deflate.readAtMostTo(
                transfer,
                minOf(byteCount, scratch.size.toLong()),
            )
        } catch (failure: RawDeflateException) {
            throw MinecraftTransportException(
                "Invalid zlib-compressed packet",
                failure,
            )
        }
        if (read < 0) {
            validateTrailer()
            finished = true
            return -1
        }
        val copied = transfer.readAtMostTo(
            scratch,
            endIndex = read.toInt(),
        )
        check(copied == read.toInt())
        checksum.update(scratch, copied)
        sink.write(scratch, endIndex = copied)
        return read
    }

    override fun close() {
        if (closed) return
        closed = true
        deflate.close()
    }

    private fun validateTrailer() {
        val expected = try {
            upstream.readInt()
        } catch (failure: EOFException) {
            throw MinecraftTransportException(
                "Truncated zlib trailer",
                failure,
            )
        }
        if (checksum.value != expected) {
            throw MinecraftTransportException(
                "Invalid zlib Adler-32 checksum",
            )
        }
        if (!upstream.exhausted()) {
            throw MinecraftTransportException(
                "Trailing bytes after zlib stream",
            )
        }
    }
}

private class Adler32 {
    private var first = 1
    private var second = 0

    val value: Int
        get() = second shl 16 or first

    fun update(bytes: ByteArray, count: Int) {
        repeat(count) { index ->
            first = (first + (bytes[index].toInt() and 0xFF)) % MOD_ADLER
            second = (second + first) % MOD_ADLER
        }
    }
}

private fun closePreserving(
    failure: Throwable?,
    close: () -> Unit,
) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        if (failure == null) throw closeFailure
        failure.addSuppressed(closeFailure)
    }
}

private const val STREAM_COPY_BYTES = 8_192
private const val COMPRESSION_METHOD_DEFLATE = 8
private const val PRESET_DICTIONARY = 0x20
private const val MOD_ADLER = 65_521
