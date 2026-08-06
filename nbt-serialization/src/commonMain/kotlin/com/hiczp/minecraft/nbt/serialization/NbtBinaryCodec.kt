package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

internal class NbtBinaryReader(
    private val source: Source,
    private val configuration: NbtFormatConfiguration,
) {
    private var bytesRead = 0L

    fun readAnyTag(): NbtTag {
        val type = readUnsignedByte()
        return readPayload(type, depth = 0)
    }

    fun readNamedTag(): NamedNbtTag {
        val type = readUnsignedByte()
        if (type == TAG_END) {
            throw NbtDecodingException("A named NBT value cannot be TAG_End")
        }
        validateType(type)
        return NamedNbtTag(
            name = readModifiedUtf(),
            tag = readPayload(type, depth = 0),
        )
    }

    fun readUnnamedTag(): NbtTag {
        val type = readUnsignedByte()
        if (type == TAG_END) return NbtEnd
        validateType(type)
        readModifiedUtf()
        return readPayload(type, depth = 0)
    }

    internal fun readPayload(type: Int, depth: Int): NbtTag {
        checkDepth(depth)
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
            TAG_LIST -> readList(depth)
            TAG_COMPOUND -> readCompound(depth)
            TAG_INT_ARRAY -> readIntArray()
            TAG_LONG_ARRAY -> readLongArray()
            else -> throw NbtDecodingException("Unknown NBT tag type: $type")
        }
    }

    private fun readByteArrayTag(): NbtByteArray {
        val length = checkedLength(
            readInt(),
            configuration.maximumByteArraySize,
            "NBT byte array",
        )
        return NbtByteArray(readBytes(length))
    }

    private fun readList(depth: Int): NbtList {
        val elementType = readUnsignedByte()
        val length = checkedLength(
            readInt(),
            configuration.maximumCollectionSize,
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
        requireMinimumPayload(length, minimumPayloadBytes(elementType), "NBT list")
        return NbtList(
            List(length) {
                val element = readPayload(elementType, depth + 1)
                if (elementType == TAG_COMPOUND) {
                    (element as NbtCompound).unwrapListElement()
                } else {
                    element
                }
            },
        )
    }

    private fun readCompound(depth: Int): NbtCompound {
        val entries = LinkedHashMap<String, NbtTag>()
        var count = 0
        while (true) {
            val elementType = readUnsignedByte()
            if (elementType == TAG_END) break
            validateType(elementType)
            if (count++ >= configuration.maximumCollectionSize) {
                throw NbtLimitException(
                    "NBT compound exceeds configured entry limit ${configuration.maximumCollectionSize}",
                )
            }
            val name = readModifiedUtf()
            entries[name] = readPayload(elementType, depth + 1)
        }
        return NbtCompound(entries)
    }

    private fun readIntArray(): NbtIntArray {
        val length = checkedLength(
            readInt(),
            configuration.maximumCollectionSize,
            "NBT int array",
        )
        requireMinimumPayload(length, Int.SIZE_BYTES, "NBT int array")
        return NbtIntArray(IntArray(length) { readInt() })
    }

    private fun readLongArray(): NbtLongArray {
        val length = checkedLength(
            readInt(),
            configuration.maximumCollectionSize,
            "NBT long array",
        )
        requireMinimumPayload(length, Long.SIZE_BYTES, "NBT long array")
        return NbtLongArray(LongArray(length) { readLong() })
    }

    internal fun readModifiedUtf(): String {
        val byteLength = readUnsignedShort()
        if (byteLength > configuration.maximumStringBytes) {
            throw NbtLimitException(
                "NBT string byte length $byteLength exceeds configured limit ${configuration.maximumStringBytes}",
            )
        }
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
        account(1)
        return source.readByte()
    }

    internal fun readShort(): Short {
        account(Short.SIZE_BYTES.toLong())
        return source.readShort()
    }

    internal fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    internal fun readInt(): Int {
        account(Int.SIZE_BYTES.toLong())
        return source.readInt()
    }

    internal fun readLong(): Long {
        account(Long.SIZE_BYTES.toLong())
        return source.readLong()
    }

    internal fun readBytes(length: Int): ByteArray {
        account(length.toLong())
        return source.readByteArray(length)
    }

    internal fun skipPayload(type: Int, depth: Int) {
        checkDepth(depth)
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
                    configuration.maximumByteArraySize,
                    "NBT byte array",
                )
                account(length.toLong())
                source.skip(length.toLong())
            }

            TAG_STRING -> readModifiedUtf()
            TAG_LIST -> {
                val elementType = readUnsignedByte()
                val length = checkedLength(
                    readInt(),
                    configuration.maximumCollectionSize,
                    "NBT list",
                )
                if (length > 0) {
                    validateType(elementType)
                    if (elementType == TAG_END) {
                        throw NbtDecodingException(
                            "Non-empty NBT list has TAG_End element type",
                        )
                    }
                    repeat(length) { skipPayload(elementType, depth + 1) }
                }
            }

            TAG_COMPOUND -> {
                var count = 0
                while (true) {
                    val elementType = readUnsignedByte()
                    if (elementType == TAG_END) break
                    validateType(elementType)
                    if (count++ >= configuration.maximumCollectionSize) {
                        throw NbtLimitException(
                            "NBT compound exceeds configured entry limit ${configuration.maximumCollectionSize}",
                        )
                    }
                    readModifiedUtf()
                    skipPayload(elementType, depth + 1)
                }
            }

            TAG_INT_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    configuration.maximumCollectionSize,
                    "NBT int array",
                )
                repeat(length) { readInt() }
            }

            TAG_LONG_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    configuration.maximumCollectionSize,
                    "NBT long array",
                )
                repeat(length) { readLong() }
            }

            else -> throw NbtDecodingException("Unknown NBT tag type: $type")
        }
    }

    internal fun checkedLength(length: Int, maximum: Int, kind: String): Int =
        com.hiczp.minecraft.nbt.serialization.checkedLength(length, maximum, kind)

    internal fun requireMinimumPayload(
        length: Int,
        bytesPerElement: Int,
        kind: String,
    ) {
        val bytes = length.toLong() * bytesPerElement
        if (bytes > configuration.maximumEncodedBytes - bytesRead) {
            throw NbtLimitException(
                "$kind payload exceeds configured encoded-byte limit",
            )
        }
    }

    internal fun checkDepth(depth: Int) {
        if (depth > configuration.maximumDepth) {
            throw NbtLimitException(
                "NBT exceeds configured depth limit ${configuration.maximumDepth}",
            )
        }
    }

    private fun account(count: Long) {
        if (
            count < 0 ||
            count > configuration.maximumEncodedBytes - bytesRead
        ) {
            throw NbtLimitException(
                "NBT exceeds configured encoded-byte limit ${configuration.maximumEncodedBytes}",
            )
        }
        bytesRead += count
    }
}

internal class NbtBinaryWriter(
    private val sink: Sink,
    private val configuration: NbtFormatConfiguration,
) {
    private var bytesWritten = 0L

    fun writeAnyTag(tag: NbtTag) {
        writeByte(typeOf(tag))
        writePayload(tag, depth = 0)
    }

    fun writeNamedTag(value: NamedNbtTag) {
        writeByte(typeOf(value.tag))
        writeModifiedUtf(value.name)
        writePayload(value.tag, depth = 0)
    }

    fun writeUnnamedTag(tag: NbtTag) {
        writeByte(typeOf(tag))
        if (tag === NbtEnd) return
        writeModifiedUtf("")
        writePayload(tag, depth = 0)
    }

    internal fun writePayload(tag: NbtTag, depth: Int) {
        checkDepth(depth)
        when (tag) {
            NbtEnd -> Unit
            is NbtByte -> writeByte(tag.value.toInt())
            is NbtShort -> writeShort(tag.value.toInt())
            is NbtInt -> writeInt(tag.value)
            is NbtLong -> writeLong(tag.value)
            is NbtFloat -> writeInt(tag.value.toBits())
            is NbtDouble -> writeLong(tag.value.toBits())
            is NbtByteArray -> {
                checkByteArrayLength(tag.size)
                writeInt(tag.size)
                tag.forEach { writeByte(it.toInt()) }
            }

            is NbtString -> writeModifiedUtf(tag.value)
            is NbtList -> writeList(tag, depth)
            is NbtCompound -> writeCompound(tag, depth)
            is NbtIntArray -> {
                checkCollectionLength(tag.size, "NBT int array")
                writeInt(tag.size)
                tag.forEach(::writeInt)
            }

            is NbtLongArray -> {
                checkCollectionLength(tag.size, "NBT long array")
                writeInt(tag.size)
                tag.forEach(::writeLong)
            }
        }
    }

    private fun writeList(tag: NbtList, depth: Int) {
        checkCollectionLength(tag.size, "NBT list")
        val rawType = rawListType(tag)
        writeByte(rawType)
        writeInt(tag.size)
        tag.forEach { element ->
            if (
                rawType == TAG_COMPOUND &&
                (element !is NbtCompound || element.isListWrapper())
            ) {
                writeListWrapper(element, depth + 1)
            } else {
                writePayload(element, depth + 1)
            }
        }
    }

    internal fun writeListWrapper(element: NbtTag, depth: Int) {
        checkDepth(depth)
        writeByte(typeOf(element))
        writeModifiedUtf("")
        writePayload(element, depth + 1)
        writeByte(TAG_END)
    }

    private fun writeCompound(tag: NbtCompound, depth: Int) {
        checkCollectionLength(tag.size, "NBT compound")
        tag.forEachEntry { name, value ->
            writeByte(typeOf(value))
            writeModifiedUtf(name)
            writePayload(value, depth + 1)
        }
        writeByte(TAG_END)
    }

    internal fun writeModifiedUtf(value: String) {
        val byteLength = modifiedUtfLength(value)
        if (byteLength > configuration.maximumStringBytes) {
            throw NbtLimitException(
                "NBT string byte length $byteLength exceeds configured limit ${configuration.maximumStringBytes}",
            )
        }
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
        account(1)
        sink.writeByte(value.toByte())
    }

    internal fun writeShort(value: Int) {
        account(Short.SIZE_BYTES.toLong())
        sink.writeShort(value.toShort())
    }

    internal fun writeInt(value: Int) {
        account(Int.SIZE_BYTES.toLong())
        sink.writeInt(value)
    }

    internal fun writeLong(value: Long) {
        account(Long.SIZE_BYTES.toLong())
        sink.writeLong(value)
    }

    internal fun writeBytes(value: ByteArray) {
        account(value.size.toLong())
        sink.write(value)
    }

    internal fun checkDepth(depth: Int) {
        if (depth > configuration.maximumDepth) {
            throw NbtLimitException(
                "NBT exceeds configured depth limit ${configuration.maximumDepth}",
            )
        }
    }

    internal fun checkCollectionLength(length: Int, kind: String) {
        if (length > configuration.maximumCollectionSize) {
            throw NbtLimitException(
                "$kind length $length exceeds configured limit ${configuration.maximumCollectionSize}",
            )
        }
    }

    internal fun checkByteArrayLength(length: Int) {
        if (length > configuration.maximumByteArraySize) {
            throw NbtLimitException(
                "NBT byte array length $length exceeds configured limit ${configuration.maximumByteArraySize}",
            )
        }
    }

    private fun account(count: Long) {
        if (
            count < 0 ||
            count > configuration.maximumEncodedBytes - bytesWritten
        ) {
            throw NbtLimitException(
                "NBT exceeds configured encoded-byte limit ${configuration.maximumEncodedBytes}",
            )
        }
        bytesWritten += count
    }
}

internal fun checkedLength(length: Int, maximum: Int, kind: String): Int {
    if (length !in 0..maximum) {
        throw NbtLimitException(
            "$kind length $length is outside configured range 0..$maximum",
        )
    }
    return length
}

private fun malformedModifiedUtf(index: Int): Nothing =
    throw NbtDecodingException("Malformed modified UTF-8 at byte $index")

private fun minimumPayloadBytes(type: Int): Int = when (type) {
    TAG_BYTE -> 1
    TAG_SHORT -> 2
    TAG_INT,
    TAG_FLOAT,
        -> 4

    TAG_LONG,
    TAG_DOUBLE,
        -> 8

    TAG_BYTE_ARRAY,
    TAG_INT_ARRAY,
    TAG_LONG_ARRAY,
        -> 4

    TAG_STRING -> 2
    TAG_LIST -> 5
    TAG_COMPOUND -> 1
    else -> 0
}
