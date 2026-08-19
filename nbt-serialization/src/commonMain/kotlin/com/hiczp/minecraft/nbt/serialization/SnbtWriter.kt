package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.Sink
import kotlinx.io.writeString

/**
 * Handwritten because no maintained KMP writer targets the selected-release
 * SNBT grammar. kotlinx-io owns UTF-8 encoding for the streaming output.
 */
internal class SnbtWriter(
    private val output: SnbtOutput,
    private val configuration: SnbtFormatConfiguration,
) {
    fun writeTag(tag: NbtTag) {
        when (tag) {
            NbtEnd -> throw NbtEncodingException("TAG_End has no round-trippable SNBT representation")
            is NbtByte -> writeNumber(tag.value.toString(), 'b')
            is NbtShort -> writeNumber(tag.value.toString(), 's')
            is NbtInt -> output.write(tag.value.toString())
            is NbtLong -> writeNumber(tag.value.toString(), 'L')
            is NbtFloat -> {
                if (!tag.value.isFinite()) throw NbtEncodingException("SNBT cannot represent a non-finite TAG_Float")
                writeNumber(tag.value.toSnbtString(), 'f')
            }

            is NbtDouble -> {
                if (!tag.value.isFinite()) throw NbtEncodingException("SNBT cannot represent a non-finite TAG_Double")
                writeNumber(tag.value.toSnbtString(), 'd')
            }

            is NbtByteArray -> writeByteArray(tag)
            is NbtString -> writeQuoted(tag.value)
            is NbtList -> writeList(tag)
            is NbtCompound -> writeCompound(tag)
            is NbtIntArray -> writeIntArray(tag)
            is NbtLongArray -> writeLongArray(tag)
        }
    }

    private fun writeByteArray(tag: NbtByteArray) {
        output.write("[B;")
        var index = 0
        tag.forEach { value ->
            if (index != 0) output.writeAscii(',')
            writeNumber(value.toString(), 'B')
            index++
        }
        output.writeAscii(']')
    }

    private fun writeIntArray(tag: NbtIntArray) {
        output.write("[I;")
        var index = 0
        tag.forEach { value ->
            if (index != 0) output.writeAscii(',')
            output.write(value.toString())
            index++
        }
        output.writeAscii(']')
    }

    private fun writeLongArray(tag: NbtLongArray) {
        output.write("[L;")
        var index = 0
        tag.forEach { value ->
            if (index != 0) output.writeAscii(',')
            writeNumber(value.toString(), 'L')
            index++
        }
        output.writeAscii(']')
    }

    private fun writeList(tag: NbtList) {
        output.writeAscii('[')
        repeat(tag.size) { index ->
            if (index != 0) output.writeAscii(',')
            writeTag(tag[index])
        }
        output.writeAscii(']')
    }

    private fun writeCompound(tag: NbtCompound) {
        output.writeAscii('{')
        var index = 0
        if (configuration.sortCompoundKeys) {
            val names = ArrayList<String>(tag.size)
            tag.forEachEntry { name, _ -> names += name }
            names.sort()
            for (name in names) {
                if (index != 0) output.writeAscii(',')
                writeKey(name)
                output.writeAscii(':')
                writeTag(tag[name] ?: error("NBT compound changed while writing"))
                index++
            }
        } else {
            tag.forEachEntry { name, value ->
                if (index != 0) output.writeAscii(',')
                writeKey(name)
                output.writeAscii(':')
                writeTag(value)
                index++
            }
        }
        output.writeAscii('}')
    }

    private fun writeKey(value: String) {
        if (value.isEmpty()) {
            throw NbtEncodingException("Empty compound keys have no round-trippable SNBT representation")
        }
        if (value.isUnquotedKey()) output.write(value) else writeQuoted(value)
    }

    private fun writeQuoted(value: String) {
        val quote = value.firstNotNullOfOrNull { character ->
            when (character) {
                '"' -> '\''
                '\'' -> '"'
                else -> null
            }
        } ?: '"'
        output.writeAscii(quote)
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
            output.write(value, plainStart, index)
            if (escape == null) {
                output.write("\\x")
                output.writeAscii(HEX_DIGITS[(character.code shr 4) and 0xF])
                output.writeAscii(HEX_DIGITS[character.code and 0xF])
            } else {
                output.write(escape)
            }
            plainStart = index + 1
        }
        output.write(value, plainStart, value.length)
        output.writeAscii(quote)
    }

    private fun writeNumber(value: String, suffix: Char) {
        output.write(value)
        output.writeAscii(suffix)
    }
}

internal interface SnbtOutput {
    fun writeAscii(value: Char)

    fun write(value: String, startIndex: Int = 0, endIndex: Int = value.length)
}

internal class StringSnbtOutput : SnbtOutput {
    private val builder = StringBuilder()

    override fun writeAscii(value: Char) {
        builder.append(value)
    }

    override fun write(value: String, startIndex: Int, endIndex: Int) {
        for (index in startIndex until endIndex) builder.append(value[index])
    }

    override fun toString(): String = builder.toString()
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
