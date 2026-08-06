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

    private fun readPayload(type: Int, depth: Int): NbtTag {
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
        account(length.toLong())
        return NbtByteArray(source.readByteArray(length))
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

    private fun readModifiedUtf(): String {
        val byteLength = readUnsignedShort()
        if (byteLength > configuration.maximumStringBytes) {
            throw NbtLimitException(
                "NBT string byte length $byteLength exceeds configured limit ${configuration.maximumStringBytes}",
            )
        }
        account(byteLength.toLong())
        return decodeModifiedUtf(source.readByteArray(byteLength))
    }

    private fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    private fun readByte(): Byte {
        account(1)
        return source.readByte()
    }

    private fun readShort(): Short {
        account(Short.SIZE_BYTES.toLong())
        return source.readShort()
    }

    private fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    private fun readInt(): Int {
        account(Int.SIZE_BYTES.toLong())
        return source.readInt()
    }

    private fun readLong(): Long {
        account(Long.SIZE_BYTES.toLong())
        return source.readLong()
    }

    private fun requireMinimumPayload(
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

    private fun checkDepth(depth: Int) {
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

    private fun writePayload(tag: NbtTag, depth: Int) {
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
                val value = tag.value
                checkByteArrayLength(value.size)
                writeInt(value.size)
                writeBytes(value)
            }

            is NbtString -> writeModifiedUtf(tag.value)
            is NbtList -> writeList(tag, depth)
            is NbtCompound -> writeCompound(tag, depth)
            is NbtIntArray -> {
                val value = tag.value
                checkCollectionLength(value.size, "NBT int array")
                writeInt(value.size)
                value.forEach(::writeInt)
            }

            is NbtLongArray -> {
                val value = tag.value
                checkCollectionLength(value.size, "NBT long array")
                writeInt(value.size)
                value.forEach(::writeLong)
            }
        }
    }

    private fun writeList(tag: NbtList, depth: Int) {
        val values = tag.value
        checkCollectionLength(values.size, "NBT list")
        val rawType = rawListType(values)
        writeByte(rawType)
        writeInt(values.size)
        for (element in values) {
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

    private fun writeListWrapper(element: NbtTag, depth: Int) {
        checkDepth(depth)
        writeByte(typeOf(element))
        writeModifiedUtf("")
        writePayload(element, depth + 1)
        writeByte(TAG_END)
    }

    private fun writeCompound(tag: NbtCompound, depth: Int) {
        val values = tag.value
        checkCollectionLength(values.size, "NBT compound")
        for ((name, value) in values) {
            writeByte(typeOf(value))
            writeModifiedUtf(name)
            writePayload(value, depth + 1)
        }
        writeByte(TAG_END)
    }

    private fun writeModifiedUtf(value: String) {
        val byteLength = modifiedUtfLength(value)
        if (byteLength > configuration.maximumStringBytes) {
            throw NbtLimitException(
                "NBT string byte length $byteLength exceeds configured limit ${configuration.maximumStringBytes}",
            )
        }
        val bytes = encodeModifiedUtf(value)
        writeShort(byteLength)
        writeBytes(bytes)
    }

    private fun writeByte(value: Int) {
        account(1)
        sink.writeByte(value.toByte())
    }

    private fun writeShort(value: Int) {
        account(Short.SIZE_BYTES.toLong())
        sink.writeShort(value.toShort())
    }

    private fun writeInt(value: Int) {
        account(Int.SIZE_BYTES.toLong())
        sink.writeInt(value)
    }

    private fun writeLong(value: Long) {
        account(Long.SIZE_BYTES.toLong())
        sink.writeLong(value)
    }

    private fun writeBytes(value: ByteArray) {
        account(value.size.toLong())
        sink.write(value)
    }

    private fun checkDepth(depth: Int) {
        if (depth > configuration.maximumDepth) {
            throw NbtLimitException(
                "NBT exceeds configured depth limit ${configuration.maximumDepth}",
            )
        }
    }

    private fun checkCollectionLength(length: Int, kind: String) {
        if (length > configuration.maximumCollectionSize) {
            throw NbtLimitException(
                "$kind length $length exceeds configured limit ${configuration.maximumCollectionSize}",
            )
        }
    }

    private fun checkByteArrayLength(length: Int) {
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

private fun checkedLength(length: Int, maximum: Int, kind: String): Int {
    if (length !in 0..maximum) {
        throw NbtLimitException(
            "$kind length $length is outside configured range 0..$maximum",
        )
    }
    return length
}

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
