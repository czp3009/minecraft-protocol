package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

internal class NbtBinaryReader(
    private val source: Source,
) {
    fun readAnyTag(): NbtTag {
        val type = readUnsignedByte()
        return readPayload(type)
    }

    fun readNamedTag(): NamedNbtTag {
        val type = readUnsignedByte()
        if (type == TAG_END) {
            throw NbtDecodingException("A named NBT value cannot be TAG_End")
        }
        validateType(type)
        return NamedNbtTag(
            name = readModifiedUtf(),
            tag = readPayload(type),
        )
    }

    fun readUnnamedTag(): NbtTag {
        val type = readUnsignedByte()
        if (type == TAG_END) return NbtEnd
        validateType(type)
        readModifiedUtf()
        return readPayload(type)
    }

    internal fun readPayload(type: Int): NbtTag {
        return when (type) {
            TAG_END -> NbtEnd
            TAG_BYTE -> NbtByte(readByte())
            TAG_SHORT -> NbtShort(readShort())
            TAG_INT -> NbtInt(readInt())
            TAG_LONG -> NbtLong(readLong())
            TAG_FLOAT -> NbtFloat(Float.fromBits(readInt()))
            TAG_DOUBLE -> NbtDouble(Double.fromBits(readLong()))
            TAG_BYTE_ARRAY -> readByteArrayTag()
            TAG_STRING -> NbtString(readModifiedUtf())
            TAG_LIST -> readList()
            TAG_COMPOUND -> readCompound()
            TAG_INT_ARRAY -> readIntArray()
            TAG_LONG_ARRAY -> readLongArray()
            else -> throw NbtDecodingException("Unknown NBT tag type: $type")
        }
    }

    private fun readByteArrayTag(): NbtByteArray {
        val length = checkedLength(
            readInt(),
            "NBT byte array",
        )
        return NbtByteArray(readBytes(length))
    }

    private fun readList(): NbtList {
        val elementType = readUnsignedByte()
        val length = checkedLength(
            readInt(),
            "NBT list",
        )
        if (length == 0) {
            // Vanilla accepts any element ID for an empty list and emits END.
            return NbtList(emptyList())
        }
        validateType(elementType)
        if (elementType == TAG_END) {
            throw NbtDecodingException(
                "Non-empty NBT list has TAG_End element type",
            )
        }
        return NbtList(
            List(length) {
                val element = readPayload(elementType)
                if (elementType == TAG_COMPOUND) {
                    (element as NbtCompound).unwrapListElement()
                } else {
                    element
                }
            },
        )
    }

    private fun readCompound(): NbtCompound {
        val entries = LinkedHashMap<String, NbtTag>()
        while (true) {
            val elementType = readUnsignedByte()
            if (elementType == TAG_END) break
            validateType(elementType)
            val name = readModifiedUtf()
            entries[name] = readPayload(elementType)
        }
        return NbtCompound(entries)
    }

    private fun readIntArray(): NbtIntArray {
        val length = checkedLength(
            readInt(),
            "NBT int array",
        )
        return NbtIntArray(IntArray(length) { readInt() })
    }

    private fun readLongArray(): NbtLongArray {
        val length = checkedLength(
            readInt(),
            "NBT long array",
        )
        return NbtLongArray(LongArray(length) { readLong() })
    }

    internal fun readModifiedUtf(): String {
        val byteLength = readUnsignedShort()
        val characters = CharArray(byteLength)
        var byteIndex = 0
        var characterIndex = 0
        while (byteIndex < byteLength) {
            val first = readUnsignedByte()
            when {
                first <= 0x7F -> {
                    characters[characterIndex++] = first.toChar()
                    byteIndex++
                }

                first shr 4 == 0xC || first shr 4 == 0xD -> {
                    if (byteIndex + 1 >= byteLength) {
                        malformedModifiedUtf(byteIndex)
                    }
                    val second = readUnsignedByte()
                    if (second and 0xC0 != 0x80) {
                        malformedModifiedUtf(byteIndex)
                    }
                    characters[characterIndex++] =
                        (((first and 0x1F) shl 6) or (second and 0x3F)).toChar()
                    byteIndex += 2
                }

                first shr 4 == 0xE -> {
                    if (byteIndex + 2 >= byteLength) {
                        malformedModifiedUtf(byteIndex)
                    }
                    val second = readUnsignedByte()
                    val third = readUnsignedByte()
                    if (
                        second and 0xC0 != 0x80 ||
                        third and 0xC0 != 0x80
                    ) {
                        malformedModifiedUtf(byteIndex)
                    }
                    characters[characterIndex++] =
                        (((first and 0x0F) shl 12) or
                                ((second and 0x3F) shl 6) or
                                (third and 0x3F)).toChar()
                    byteIndex += 3
                }

                else -> malformedModifiedUtf(byteIndex)
            }
        }
        return characters.concatToString(endIndex = characterIndex)
    }

    internal fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    internal fun readByte(): Byte {
        return source.readByte()
    }

    internal fun readShort(): Short {
        return source.readShort()
    }

    internal fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    internal fun readInt(): Int {
        return source.readInt()
    }

    internal fun readLong(): Long {
        return source.readLong()
    }

    internal fun readBytes(length: Int): ByteArray {
        return source.readByteArray(length)
    }

    internal fun skipPayload(type: Int) {
        when (type) {
            TAG_END -> Unit
            TAG_BYTE -> readByte()
            TAG_SHORT -> readShort()
            TAG_INT,
            TAG_FLOAT,
                -> readInt()

            TAG_LONG,
            TAG_DOUBLE,
                -> readLong()

            TAG_BYTE_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    "NBT byte array",
                )
                source.skip(length.toLong())
            }

            TAG_STRING -> readModifiedUtf()
            TAG_LIST -> {
                val elementType = readUnsignedByte()
                val length = checkedLength(
                    readInt(),
                    "NBT list",
                )
                if (length > 0) {
                    validateType(elementType)
                    if (elementType == TAG_END) {
                        throw NbtDecodingException(
                            "Non-empty NBT list has TAG_End element type",
                        )
                    }
                    repeat(length) { skipPayload(elementType) }
                }
            }

            TAG_COMPOUND -> {
                while (true) {
                    val elementType = readUnsignedByte()
                    if (elementType == TAG_END) break
                    validateType(elementType)
                    readModifiedUtf()
                    skipPayload(elementType)
                }
            }

            TAG_INT_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    "NBT int array",
                )
                repeat(length) { readInt() }
            }

            TAG_LONG_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    "NBT long array",
                )
                repeat(length) { readLong() }
            }

            else -> throw NbtDecodingException("Unknown NBT tag type: $type")
        }
    }

    internal fun checkedLength(length: Int, kind: String): Int =
        com.hiczp.minecraft.nbt.serialization.checkedLength(length, kind)
}

internal class NbtBinaryWriter(
    private val sink: Sink,
) {
    fun writeAnyTag(tag: NbtTag) {
        writeByte(typeOf(tag))
        writePayload(tag)
    }

    fun writeNamedTag(value: NamedNbtTag) {
        writeByte(typeOf(value.tag))
        writeModifiedUtf(value.name)
        writePayload(value.tag)
    }

    fun writeUnnamedTag(tag: NbtTag) {
        writeByte(typeOf(tag))
        if (tag === NbtEnd) return
        writeModifiedUtf("")
        writePayload(tag)
    }

    internal fun writePayload(tag: NbtTag) {
        when (tag) {
            NbtEnd -> Unit
            is NbtByte -> writeByte(tag.value.toInt())
            is NbtShort -> writeShort(tag.value.toInt())
            is NbtInt -> writeInt(tag.value)
            is NbtLong -> writeLong(tag.value)
            is NbtFloat -> writeInt(tag.value.toBits())
            is NbtDouble -> writeLong(tag.value.toBits())
            is NbtByteArray -> {
                writeInt(tag.size)
                tag.forEach { writeByte(it.toInt()) }
            }

            is NbtString -> writeModifiedUtf(tag.value)
            is NbtList -> writeList(tag)
            is NbtCompound -> writeCompound(tag)
            is NbtIntArray -> {
                writeInt(tag.size)
                tag.forEach(::writeInt)
            }

            is NbtLongArray -> {
                writeInt(tag.size)
                tag.forEach(::writeLong)
            }
        }
    }

    private fun writeList(tag: NbtList) {
        val rawType = rawListType(tag)
        writeByte(rawType)
        writeInt(tag.size)
        tag.forEach { element ->
            if (
                rawType == TAG_COMPOUND &&
                (element !is NbtCompound || element.isListWrapper())
            ) {
                writeListWrapper(element)
            } else {
                writePayload(element)
            }
        }
    }

    internal fun writeListWrapper(element: NbtTag) {
        writeByte(typeOf(element))
        writeModifiedUtf("")
        writePayload(element)
        writeByte(TAG_END)
    }

    private fun writeCompound(tag: NbtCompound) {
        tag.forEachEntry { name, value ->
            writeByte(typeOf(value))
            writeModifiedUtf(name)
            writePayload(value)
        }
        writeByte(TAG_END)
    }

    internal fun writeModifiedUtf(value: String) {
        val byteLength = modifiedUtfLength(value)
        writeShort(byteLength)
        for (character in value) {
            val code = character.code
            when {
                code in 1..0x7F -> writeByte(code)
                code <= 0x7FF -> {
                    writeByte(0xC0 or (code shr 6))
                    writeByte(0x80 or (code and 0x3F))
                }

                else -> {
                    writeByte(0xE0 or (code shr 12))
                    writeByte(0x80 or ((code shr 6) and 0x3F))
                    writeByte(0x80 or (code and 0x3F))
                }
            }
        }
    }

    internal fun writeByte(value: Int) {
        sink.writeByte(value.toByte())
    }

    internal fun writeShort(value: Int) {
        sink.writeShort(value.toShort())
    }

    internal fun writeInt(value: Int) {
        sink.writeInt(value)
    }

    internal fun writeLong(value: Long) {
        sink.writeLong(value)
    }

    internal fun writeBytes(value: ByteArray) {
        sink.write(value)
    }
}

internal fun checkedLength(length: Int, kind: String): Int {
    if (length < 0) {
        throw NbtDecodingException("$kind has negative length $length")
    }
    return length
}

private fun malformedModifiedUtf(index: Int): Nothing =
    throw NbtDecodingException("Malformed modified UTF-8 at byte $index")
