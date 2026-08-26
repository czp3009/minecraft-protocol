package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.Sink
import kotlinx.io.writeString

/**
 * Handwritten because no maintained KMP writer targets the selected-release
 * SNBT grammar. kotlinx-io owns UTF-8 encoding for the streaming output.
 */
internal class SnbtWriter(
    private val snbtOutput: SnbtOutput,
    private val snbtFormatConfiguration: SnbtFormatConfiguration,
) {
    fun writeTag(nbtTag: NbtTag) {
        when (nbtTag) {
            NbtEnd -> throw NbtEncodingException("TAG_End has no round-trippable SNBT representation")
            is NbtByte -> writeNumber(nbtTag.value.toString(), 'b')
            is NbtShort -> writeNumber(nbtTag.value.toString(), 's')
            is NbtInt -> snbtOutput.write(nbtTag.value.toString())
            is NbtLong -> writeNumber(nbtTag.value.toString(), 'L')
            is NbtFloat -> {
                if (!nbtTag.value.isFinite()) throw NbtEncodingException("SNBT cannot represent a non-finite TAG_Float")
                writeNumber(nbtTag.value.toSnbtString(), 'f')
            }

            is NbtDouble -> {
                if (!nbtTag.value.isFinite()) throw NbtEncodingException("SNBT cannot represent a non-finite TAG_Double")
                writeNumber(nbtTag.value.toSnbtString(), 'd')
            }

            is NbtByteArray -> writeByteArray(nbtTag)
            is NbtString -> writeQuoted(nbtTag.value)
            is NbtList -> writeList(nbtTag)
            is NbtCompound -> writeCompound(nbtTag)
            is NbtIntArray -> writeIntArray(nbtTag)
            is NbtLongArray -> writeLongArray(nbtTag)
        }
    }

    private fun writeByteArray(nbtByteArray: NbtByteArray) {
        snbtOutput.write("[B;")
        var index = 0
        nbtByteArray.forEach { value ->
            if (index != 0) snbtOutput.writeAscii(',')
            writeNumber(value.toString(), 'B')
            index++
        }
        snbtOutput.writeAscii(']')
    }

    private fun writeIntArray(nbtIntArray: NbtIntArray) {
        snbtOutput.write("[I;")
        var index = 0
        nbtIntArray.forEach { value ->
            if (index != 0) snbtOutput.writeAscii(',')
            snbtOutput.write(value.toString())
            index++
        }
        snbtOutput.writeAscii(']')
    }

    private fun writeLongArray(nbtLongArray: NbtLongArray) {
        snbtOutput.write("[L;")
        var index = 0
        nbtLongArray.forEach { value ->
            if (index != 0) snbtOutput.writeAscii(',')
            writeNumber(value.toString(), 'L')
            index++
        }
        snbtOutput.writeAscii(']')
    }

    private fun writeList(nbtList: NbtList) {
        snbtOutput.writeAscii('[')
        repeat(nbtList.size) { index ->
            if (index != 0) snbtOutput.writeAscii(',')
            writeTag(nbtList[index])
        }
        snbtOutput.writeAscii(']')
    }

    private fun writeCompound(nbtCompound: NbtCompound) {
        snbtOutput.writeAscii('{')
        var index = 0
        if (snbtFormatConfiguration.sortCompoundKeys) {
            val names = ArrayList<String>(nbtCompound.size)
            nbtCompound.forEachEntry { name, _ -> names += name }
            names.sort()
            for (name in names) {
                if (index != 0) snbtOutput.writeAscii(',')
                writeKey(name)
                snbtOutput.writeAscii(':')
                writeTag(nbtCompound[name] ?: error("NBT compound changed while writing"))
                index++
            }
        } else {
            nbtCompound.forEachEntry { name, value ->
                if (index != 0) snbtOutput.writeAscii(',')
                writeKey(name)
                snbtOutput.writeAscii(':')
                writeTag(value)
                index++
            }
        }
        snbtOutput.writeAscii('}')
    }

    private fun writeKey(value: String) {
        if (value.isEmpty()) {
            throw NbtEncodingException("Empty compound keys have no round-trippable SNBT representation")
        }
        if (value.isUnquotedKey()) snbtOutput.write(value) else writeQuoted(value)
    }

    private fun writeQuoted(value: String) {
        val quote = value.firstNotNullOfOrNull { character ->
            when (character) {
                '"' -> '\''
                '\'' -> '"'
                else -> null
            }
        } ?: '"'
        snbtOutput.writeAscii(quote)
        var plainStart = 0
        for (index in value.indices) {
            val character = value[index]
            val escape = when {
                character == '\\' -> "\\\\"
                character == quote -> if (quote == '"') "\\\"" else "\\'"
                character == '\b' -> "\\b"
                character == '\t' -> "\\t"
                character == '\n' -> "\\n"
                character == '\u000C' -> "\\f"
                character == '\r' -> "\\r"
                character < ' ' -> null
                else -> continue
            }
            snbtOutput.write(value, plainStart, index)
            if (escape == null) {
                snbtOutput.write("\\x")
                snbtOutput.writeAscii(HEX_DIGITS[(character.code shr 4) and 0xF])
                snbtOutput.writeAscii(HEX_DIGITS[character.code and 0xF])
            } else {
                snbtOutput.write(escape)
            }
            plainStart = index + 1
        }
        snbtOutput.write(value, plainStart, value.length)
        snbtOutput.writeAscii(quote)
    }

    private fun writeNumber(value: String, suffix: Char) {
        snbtOutput.write(value)
        snbtOutput.writeAscii(suffix)
    }
}

internal interface SnbtOutput {
    fun writeAscii(value: Char)

    fun write(value: String, startIndex: Int = 0, endIndex: Int = value.length)
}

internal class StringSnbtOutput : SnbtOutput {
    private val stringBuilder = StringBuilder()

    override fun writeAscii(value: Char) {
        stringBuilder.append(value)
    }

    override fun write(value: String, startIndex: Int, endIndex: Int) {
        for (index in startIndex until endIndex) stringBuilder.append(value[index])
    }

    override fun toString(): String = stringBuilder.toString()
}

internal class SinkSnbtOutput(
    private val sink: Sink,
) : SnbtOutput {
    override fun writeAscii(value: Char) {
        require(value.code < 0x80) { "SNBT syntax character must be ASCII" }
        sink.writeByte(value.code.toByte())
    }

    override fun write(value: String, startIndex: Int, endIndex: Int) {
        sink.writeString(value, startIndex, endIndex)
    }
}

private fun String.isUnquotedKey(): Boolean {
    if (isEmpty() || equals("true", ignoreCase = true) || equals("false", ignoreCase = true)) return false
    if (!(first() in 'A'..'Z' || first() in 'a'..'z' || first() == '.' || first() == '_')) return false
    return all { character ->
        character in '0'..'9' || character in 'A'..'Z' || character in 'a'..'z' ||
                character == '.' || character == '_' || character == '+' || character == '-'
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun Float.toSnbtString(): String =
    if (toBits() == Int.MIN_VALUE) "-0.0" else toString()

private fun Double.toSnbtString(): String =
    if (toBits() == Long.MIN_VALUE) "-0.0" else toString()
