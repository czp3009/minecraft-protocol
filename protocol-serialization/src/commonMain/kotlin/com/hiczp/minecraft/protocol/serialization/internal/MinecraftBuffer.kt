package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.io.*

internal typealias MinecraftWriter = Sink

internal fun MinecraftWriter.writeByte(value: Int) {
    writeByte(value.toByte())
}

internal fun MinecraftWriter.writeShort(value: Int) {
    writeShort(value.toShort())
}

internal fun MinecraftWriter.writeVarInt(value: Int) {
    var remaining = value
    while (true) {
        if (remaining and 0x7F.inv() == 0) {
            writeByte(remaining)
            return
        }
        writeByte(remaining and 0x7F or 0x80)
        remaining = remaining ushr 7
    }
}

internal fun MinecraftWriter.writeVarLong(value: Long) {
    var remaining = value
    while (true) {
        if (remaining and 0x7FL.inv() == 0L) {
            writeByte(remaining.toInt())
            return
        }
        writeByte((remaining and 0x7F or 0x80).toInt())
        remaining = remaining ushr 7
    }
}

/** A bounded packet-payload view over a caller-owned source. */
internal class MinecraftReader(
    source: Source,
    byteCount: Int,
) {
    private val source: Source = LimitedRawSource(source, byteCount).buffered()
    private var remainingByteCount: Int = byteCount

    init {
        if (byteCount < 0) {
            throw MinecraftSerializationException("Negative payload length: $byteCount")
        }
    }

    val remaining: Int
        get() = remainingByteCount

    fun readByte(): Byte = consume(Byte.SIZE_BYTES) { source.readByte() }

    fun peekByte(): Byte {
        requireRemaining(Byte.SIZE_BYTES)
        return source.peek().readByte()
    }

    fun readUnsignedByte(): Int = consume(Byte.SIZE_BYTES) { source.readUByte().toInt() }

    fun readBytes(length: Int): ByteArray =
        consume(length) { source.readByteArray(length) }

    fun readUtf8(length: Int): String {
        requireRemaining(length)
        var remaining = length
        return buildString(length) {
            while (remaining > 0) {
                val first = readUnsignedByte()
                remaining--
                val codePoint = when (first) {
                    in 0x00..0x7F -> first
                    in 0xC2..0xDF -> {
                        val second = readContinuationByte(remaining)
                        remaining--
                        ((first and 0x1F) shl 6) or (second and 0x3F)
                    }

                    in 0xE0..0xEF -> {
                        if (remaining < 2) invalidUtf8()
                        val second = readContinuationByte(remaining)
                        remaining--
                        val third = readContinuationByte(remaining)
                        remaining--
                        if (
                            first == 0xE0 && second < 0xA0 ||
                            first == 0xED && second >= 0xA0
                        ) {
                            invalidUtf8()
                        }
                        ((first and 0x0F) shl 12) or
                                ((second and 0x3F) shl 6) or
                                (third and 0x3F)
                    }

                    in 0xF0..0xF4 -> {
                        if (remaining < 3) invalidUtf8()
                        val second = readContinuationByte(remaining)
                        remaining--
                        val third = readContinuationByte(remaining)
                        remaining--
                        val fourth = readContinuationByte(remaining)
                        remaining--
                        if (
                            first == 0xF0 && second < 0x90 ||
                            first == 0xF4 && second >= 0x90
                        ) {
                            invalidUtf8()
                        }
                        ((first and 0x07) shl 18) or
                                ((second and 0x3F) shl 12) or
                                ((third and 0x3F) shl 6) or
                                (fourth and 0x3F)
                    }

                    else -> invalidUtf8()
                }
                if (codePoint <= Char.MAX_VALUE.code) {
                    append(codePoint.toChar())
                } else {
                    val supplementary = codePoint - 0x1_0000
                    append(((supplementary ushr 10) + 0xD800).toChar())
                    append(((supplementary and 0x3FF) + 0xDC00).toChar())
                }
            }
        }
    }

    fun skip(length: Int) {
        consume(length) { source.skip(length.toLong()) }
    }

    fun readShort(): Short = consume(Short.SIZE_BYTES) { source.readShort() }

    fun readUnsignedShort(): Int = consume(Short.SIZE_BYTES) { source.readUShort().toInt() }

    fun readInt(): Int = consume(Int.SIZE_BYTES) { source.readInt() }

    fun readLong(): Long = consume(Long.SIZE_BYTES) { source.readLong() }

    fun readVarInt(rejectNonMinimal: Boolean): Int {
        var value = 0
        var position = 0
        var count = 0
        while (true) {
            val current = readUnsignedByte()
            count++
            value = value or ((current and 0x7F) shl position)
            if (current and 0x80 == 0) {
                if (rejectNonMinimal && count != varIntSize(value)) {
                    throw MinecraftSerializationException("Non-minimal VarInt encoding")
                }
                return value
            }
            position += 7
            if (position >= 32) {
                throw MinecraftSerializationException("VarInt is too big")
            }
        }
    }

    fun readVarLong(rejectNonMinimal: Boolean): Long {
        var value = 0L
        var position = 0
        var count = 0
        while (true) {
            val current = readUnsignedByte()
            count++
            value = value or ((current and 0x7F).toLong() shl position)
            if (current and 0x80 == 0) {
                if (rejectNonMinimal && count != varLongSize(value)) {
                    throw MinecraftSerializationException("Non-minimal VarLong encoding")
                }
                return value
            }
            position += 7
            if (position >= 64) {
                throw MinecraftSerializationException("VarLong is too big")
            }
        }
    }

    /**
     * Reserves exactly [length] bytes from this reader for a nested value.
     * The child must be exhausted before its parent is read again.
     */
    fun readBounded(length: Int): MinecraftReader {
        requireRemaining(length)
        remainingByteCount -= length
        return MinecraftReader(source, length)
    }

    /** Returns a non-consuming view used to discover a self-delimiting value's size. */
    fun peekSource(): Source = source.peek()

    private fun readContinuationByte(remaining: Int): Int {
        if (remaining <= 0) invalidUtf8()
        val value = readUnsignedByte()
        if (value !in 0x80..0xBF) invalidUtf8()
        return value
    }

    private fun invalidUtf8(): Nothing =
        throw MinecraftSerializationException("Invalid UTF-8 string")

    private inline fun <T> consume(length: Int, read: () -> T): T {
        requireRemaining(length)
        val result = read()
        remainingByteCount -= length
        return result
    }

    private fun requireRemaining(length: Int) {
        if (length < 0) {
            throw MinecraftSerializationException("Negative byte count: $length")
        }
        if (length > remainingByteCount) {
            throw MinecraftSerializationException(
                "Unexpected end of payload: need $length byte(s), have $remainingByteCount",
            )
        }
    }
}

private class LimitedRawSource(
    private val delegate: Source,
    private val byteCount: Int,
) : RawSource {
    private var supplied: Long = 0

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (supplied == this.byteCount.toLong()) return -1
        val allowed = minOf(byteCount, this.byteCount.toLong() - supplied)
        val read = delegate.readAtMostTo(sink, allowed)
        if (read < 0) return -1
        supplied += read
        return read
    }

    override fun close() = Unit
}

private fun varIntSize(value: Int): Int {
    var remaining = value
    var size = 1
    while (remaining and 0x7F.inv() != 0) {
        size++
        remaining = remaining ushr 7
    }
    return size
}

private fun varLongSize(value: Long): Int {
    var remaining = value
    var size = 1
    while (remaining and 0x7FL.inv() != 0L) {
        size++
        remaining = remaining ushr 7
    }
    return size
}
