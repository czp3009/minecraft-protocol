package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.io.Buffer

internal typealias MinecraftWriter = Buffer

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

internal class MinecraftReader(private val bytes: ByteArray) {
    var position: Int = 0
        private set

    val remaining: Int
        get() = bytes.size - position

    fun readByte(): Byte {
        requireRemaining(1)
        return bytes[position++]
    }

    fun peekByte(): Byte {
        requireRemaining(1)
        return bytes[position]
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    fun readBytes(length: Int): ByteArray {
        if (length < 0) {
            throw MinecraftSerializationException("Negative byte-array length: $length")
        }
        requireRemaining(length)
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    fun remainingBytes(): ByteArray = bytes.copyOfRange(position, bytes.size)

    fun skip(length: Int) {
        if (length < 0) {
            throw MinecraftSerializationException("Negative skip length: $length")
        }
        requireRemaining(length)
        position += length
    }

    fun readShort(): Short {
        requireRemaining(2)
        val value = (readUnsignedByte() shl 8) or readUnsignedByte()
        return value.toShort()
    }

    fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    fun readInt(): Int {
        requireRemaining(4)
        return (readUnsignedByte() shl 24) or
                (readUnsignedByte() shl 16) or
                (readUnsignedByte() shl 8) or
                readUnsignedByte()
    }

    fun readLong(): Long {
        requireRemaining(8)
        return (readUnsignedByte().toLong() shl 56) or
                (readUnsignedByte().toLong() shl 48) or
                (readUnsignedByte().toLong() shl 40) or
                (readUnsignedByte().toLong() shl 32) or
                (readUnsignedByte().toLong() shl 24) or
                (readUnsignedByte().toLong() shl 16) or
                (readUnsignedByte().toLong() shl 8) or
                readUnsignedByte().toLong()
    }

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

    private fun requireRemaining(length: Int) {
        if (length > remaining) {
            throw MinecraftSerializationException(
                "Unexpected end of input at byte $position: need $length, have $remaining",
            )
        }
    }
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
