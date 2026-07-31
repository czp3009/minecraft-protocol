package com.hiczp.minecraft.nbt

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.io.*

/**
 * Limits applied while reading and writing binary NBT.
 *
 * The format itself only limits modified-UTF strings to 65,535 encoded bytes.
 * The remaining limits protect callers from hostile or corrupt input.
 */
data class NbtBinaryFormatConfiguration(
    val maximumDepth: Int = 512,
    val maximumCollectionSize: Int = 1_048_576,
    val maximumByteArraySize: Int = 16 * 1_048_576,
    val maximumStringBytes: Int = 65_535,
    val maximumEncodedBytes: Long = 64L * 1_048_576,
) {
    init {
        require(maximumDepth >= 0)
        require(maximumCollectionSize >= 0)
        require(maximumByteArraySize >= 0)
        require(maximumStringBytes in 0..65_535)
        require(maximumEncodedBytes >= 0)
    }
}

/** A named NBT value as stored in the traditional file representation. */
data class NamedNbtTag(
    val name: String,
    val tag: NbtTag,
) {
    init {
        require(tag !== NbtEnd) { "A named NBT value cannot be TAG_End" }
    }
}

/** A named compound root used by Minecraft world files. */
data class NbtDocument(
    val root: NbtCompound,
    val rootName: String = "",
)

class NbtFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Binary NBT for packet values and named world-file roots.
 *
 * Stream methods consume or emit exactly one value and leave ownership of the
 * [Source] or [Sink] with the caller. Byte-array methods require full input
 * consumption.
 */
sealed class NbtBinaryFormat(
    val configuration: NbtBinaryFormatConfiguration,
) {
    companion object Default : NbtBinaryFormat(NbtBinaryFormatConfiguration()) {
        operator fun invoke(
            configuration: NbtBinaryFormatConfiguration = NbtBinaryFormatConfiguration(),
        ): NbtBinaryFormat = ConfiguredNbtBinaryFormat(configuration)
    }

    fun encodeTag(sink: Sink, tag: NbtTag) {
        val output = NbtOutput(sink, configuration)
        output.writeByte(typeOf(tag))
        output.writePayload(tag, depth = 0)
    }

    fun decodeTag(source: Source): NbtTag = decode("unnamed NBT value") {
        val input = NbtInput(source, configuration)
        input.readPayload(input.readUnsignedByte(), depth = 0)
    }

    fun encodeNamedTag(sink: Sink, value: NamedNbtTag) {
        val output = NbtOutput(sink, configuration)
        output.writeByte(typeOf(value.tag))
        output.writeModifiedUtf(value.name)
        output.writePayload(value.tag, depth = 0)
    }

    fun decodeNamedTag(source: Source): NamedNbtTag = decode("named NBT value") {
        val input = NbtInput(source, configuration)
        val type = input.readUnsignedByte()
        if (type == TAG_END) {
            throw NbtFormatException("A named NBT value cannot be TAG_End")
        }
        NamedNbtTag(
            name = input.readModifiedUtf(),
            tag = input.readPayload(type, depth = 0),
        )
    }

    fun encodeDocument(sink: Sink, document: NbtDocument) =
        encodeNamedTag(sink, NamedNbtTag(document.rootName, document.root))

    fun decodeDocument(source: Source): NbtDocument {
        val named = decodeNamedTag(source)
        val root = named.tag as? NbtCompound
            ?: throw NbtFormatException("NBT document root must be TAG_Compound")
        return NbtDocument(root = root, rootName = named.name)
    }

    fun encodeTagToByteArray(tag: NbtTag): ByteArray =
        encodeToByteArray { encodeTag(it, tag) }

    fun decodeTagFromByteArray(bytes: ByteArray): NbtTag =
        decodeFully(bytes, ::decodeTag)

    fun encodeNamedTagToByteArray(value: NamedNbtTag): ByteArray =
        encodeToByteArray { encodeNamedTag(it, value) }

    fun decodeNamedTagFromByteArray(bytes: ByteArray): NamedNbtTag =
        decodeFully(bytes, ::decodeNamedTag)

    fun encodeDocumentToByteArray(document: NbtDocument): ByteArray =
        encodeToByteArray { encodeDocument(it, document) }

    fun decodeDocumentFromByteArray(bytes: ByteArray): NbtDocument =
        decodeFully(bytes, ::decodeDocument)

    private fun <T> encodeToByteArray(block: (Sink) -> T): ByteArray {
        val buffer = Buffer()
        block(buffer)
        return buffer.readByteArray()
    }

    private fun <T> decodeFully(
        bytes: ByteArray,
        block: (Source) -> T,
    ): T {
        if (bytes.size.toLong() > configuration.maximumEncodedBytes) {
            throw NbtFormatException(
                "NBT input size ${bytes.size} exceeds configured limit " +
                        configuration.maximumEncodedBytes,
            )
        }
        val buffer = Buffer()
        buffer.write(bytes)
        val value = block(buffer)
        if (!buffer.exhausted()) {
            throw NbtFormatException(
                "NBT input has ${buffer.size} trailing byte(s)",
            )
        }
        return value
    }
}

private class ConfiguredNbtBinaryFormat(
    configuration: NbtBinaryFormatConfiguration,
) : NbtBinaryFormat(configuration)

private class NbtInput(
    private val source: Source,
    private val configuration: NbtBinaryFormatConfiguration,
) {
    private var bytesRead = 0L

    fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    fun readByte(): Byte {
        account(1)
        return source.readByte()
    }

    fun readShort(): Short {
        account(2)
        return source.readShort()
    }

    fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    fun readInt(): Int {
        account(4)
        return source.readInt()
    }

    fun readLong(): Long {
        account(8)
        return source.readLong()
    }

    fun readBytes(length: Int): ByteArray {
        checkedLength(length, configuration.maximumByteArraySize, "byte sequence")
        account(length.toLong())
        return source.readByteArray(length)
    }

    fun readModifiedUtf(): String {
        val byteLength = readUnsignedShort()
        if (byteLength > configuration.maximumStringBytes) {
            throw NbtFormatException(
                "NBT string byte length $byteLength exceeds configured limit " +
                        configuration.maximumStringBytes,
            )
        }
        account(byteLength.toLong())
        return decodeModifiedUtf(source.readByteArray(byteLength))
    }

    fun readPayload(type: Int, depth: Int): NbtTag {
        checkDepth(depth, configuration)
        return when (type) {
            TAG_END -> NbtEnd
            TAG_BYTE -> NbtByte(readByte())
            TAG_SHORT -> NbtShort(readShort())
            TAG_INT -> NbtInt(readInt())
            TAG_LONG -> NbtLong(readLong())
            TAG_FLOAT -> NbtFloat(Float.fromBits(readInt()))
            TAG_DOUBLE -> NbtDouble(Double.fromBits(readLong()))
            TAG_BYTE_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    configuration.maximumByteArraySize,
                    "NBT byte array",
                )
                NbtByteArray(readBytes(length))
            }

            TAG_STRING -> NbtString(readModifiedUtf())
            TAG_LIST -> {
                val elementType = readUnsignedByte()
                validateType(elementType)
                val length = checkedLength(
                    readInt(),
                    configuration.maximumCollectionSize,
                    "NBT list",
                )
                if (elementType == TAG_END && length != 0) {
                    throw NbtFormatException(
                        "Non-empty NBT list has TAG_End element type",
                    )
                }
                NbtList(List(length) { readPayload(elementType, depth + 1) })
            }

            TAG_COMPOUND -> {
                val entries = LinkedHashMap<String, NbtTag>()
                var count = 0
                while (true) {
                    val elementType = readUnsignedByte()
                    if (elementType == TAG_END) break
                    validateType(elementType)
                    if (count++ >= configuration.maximumCollectionSize) {
                        throw NbtFormatException(
                            "NBT compound exceeds configured entry limit " +
                                    configuration.maximumCollectionSize,
                        )
                    }
                    entries[readModifiedUtf()] =
                        readPayload(elementType, depth + 1)
                }
                NbtCompound(entries)
            }

            TAG_INT_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    configuration.maximumCollectionSize,
                    "NBT int array",
                )
                requireAllocation(length, Int.SIZE_BYTES, "NBT int array")
                NbtIntArray(IntArray(length) { readInt() })
            }

            TAG_LONG_ARRAY -> {
                val length = checkedLength(
                    readInt(),
                    configuration.maximumCollectionSize,
                    "NBT long array",
                )
                requireAllocation(length, Long.SIZE_BYTES, "NBT long array")
                NbtLongArray(LongArray(length) { readLong() })
            }

            else -> throw NbtFormatException("Unknown NBT tag type: $type")
        }
    }

    private fun requireAllocation(
        length: Int,
        bytesPerElement: Int,
        kind: String,
    ) {
        val bytes = length.toLong() * bytesPerElement
        if (bytes > configuration.maximumEncodedBytes - bytesRead) {
            throw NbtFormatException(
                "$kind payload exceeds configured encoded-byte limit",
            )
        }
    }

    private fun account(count: Long) {
        if (count < 0 || count > configuration.maximumEncodedBytes - bytesRead) {
            throw NbtFormatException(
                "NBT exceeds configured encoded-byte limit " +
                        configuration.maximumEncodedBytes,
            )
        }
        bytesRead += count
    }
}

private class NbtOutput(
    private val sink: Sink,
    private val configuration: NbtBinaryFormatConfiguration,
) {
    private var bytesWritten = 0L

    fun writeByte(value: Int) {
        account(1)
        sink.writeByte(value.toByte())
    }

    fun writeShort(value: Int) {
        account(2)
        sink.writeShort(value.toShort())
    }

    fun writeInt(value: Int) {
        account(4)
        sink.writeInt(value)
    }

    fun writeLong(value: Long) {
        account(8)
        sink.writeLong(value)
    }

    fun writeBytes(value: ByteArray) {
        account(value.size.toLong())
        sink.write(value)
    }

    fun writeModifiedUtf(value: String) {
        val bytes = encodeModifiedUtf(value)
        if (bytes.size > configuration.maximumStringBytes) {
            throw NbtFormatException(
                "NBT string byte length ${bytes.size} exceeds configured limit " +
                        configuration.maximumStringBytes,
            )
        }
        writeShort(bytes.size)
        writeBytes(bytes)
    }

    fun writePayload(tag: NbtTag, depth: Int) {
        checkDepth(depth, configuration)
        when (tag) {
            NbtEnd -> Unit
            is NbtByte -> writeByte(tag.value.toInt())
            is NbtShort -> writeShort(tag.value.toInt())
            is NbtInt -> writeInt(tag.value)
            is NbtLong -> writeLong(tag.value)
            is NbtFloat -> writeInt(tag.value.toBits())
            is NbtDouble -> writeLong(tag.value.toBits())
            is NbtByteArray -> {
                checkLength(
                    tag.value.size,
                    configuration.maximumByteArraySize,
                    "NBT byte array",
                )
                writeInt(tag.value.size)
                writeBytes(tag.value)
            }

            is NbtString -> writeModifiedUtf(tag.value)
            is NbtList -> {
                checkLength(
                    tag.value.size,
                    configuration.maximumCollectionSize,
                    "NBT list",
                )
                val elementType = tag.value.firstOrNull()?.let(::typeOf) ?: TAG_END
                writeByte(elementType)
                writeInt(tag.value.size)
                tag.value.forEach {
                    if (typeOf(it) != elementType) {
                        throw NbtFormatException(
                            "NBT list contains mixed tag types",
                        )
                    }
                    writePayload(it, depth + 1)
                }
            }

            is NbtCompound -> {
                checkLength(
                    tag.value.size,
                    configuration.maximumCollectionSize,
                    "NBT compound",
                )
                tag.value.forEach { (name, value) ->
                    val type = typeOf(value)
                    if (type == TAG_END) {
                        throw NbtFormatException(
                            "NBT compounds cannot contain TAG_End values",
                        )
                    }
                    writeByte(type)
                    writeModifiedUtf(name)
                    writePayload(value, depth + 1)
                }
                writeByte(TAG_END)
            }

            is NbtIntArray -> {
                checkLength(
                    tag.value.size,
                    configuration.maximumCollectionSize,
                    "NBT int array",
                )
                writeInt(tag.value.size)
                tag.value.forEach(::writeInt)
            }

            is NbtLongArray -> {
                checkLength(
                    tag.value.size,
                    configuration.maximumCollectionSize,
                    "NBT long array",
                )
                writeInt(tag.value.size)
                tag.value.forEach(::writeLong)
            }
        }
    }

    private fun account(count: Long) {
        if (count < 0 || count > configuration.maximumEncodedBytes - bytesWritten) {
            throw NbtFormatException(
                "NBT exceeds configured encoded-byte limit " +
                        configuration.maximumEncodedBytes,
            )
        }
        bytesWritten += count
    }
}

private inline fun <T> decode(kind: String, block: () -> T): T =
    try {
        block()
    } catch (exception: NbtFormatException) {
        throw exception
    } catch (exception: EOFException) {
        throw NbtFormatException("Unexpected end of $kind", exception)
    } catch (exception: IllegalArgumentException) {
        throw NbtFormatException("Malformed $kind: ${exception.message}", exception)
    }

private fun checkDepth(
    depth: Int,
    configuration: NbtBinaryFormatConfiguration,
) {
    if (depth > configuration.maximumDepth) {
        throw NbtFormatException(
            "NBT exceeds configured depth limit ${configuration.maximumDepth}",
        )
    }
}

private fun checkLength(length: Int, maximum: Int, kind: String) {
    if (length !in 0..maximum) {
        throw NbtFormatException(
            "$kind length $length is outside configured range 0..$maximum",
        )
    }
}

private fun checkedLength(length: Int, maximum: Int, kind: String): Int {
    checkLength(length, maximum, kind)
    return length
}

private fun validateType(type: Int) {
    if (type !in TAG_END..TAG_LONG_ARRAY) {
        throw NbtFormatException("Unknown NBT tag type: $type")
    }
}

private fun typeOf(tag: NbtTag): Int = when (tag) {
    NbtEnd -> TAG_END
    is NbtByte -> TAG_BYTE
    is NbtShort -> TAG_SHORT
    is NbtInt -> TAG_INT
    is NbtLong -> TAG_LONG
    is NbtFloat -> TAG_FLOAT
    is NbtDouble -> TAG_DOUBLE
    is NbtByteArray -> TAG_BYTE_ARRAY
    is NbtString -> TAG_STRING
    is NbtList -> TAG_LIST
    is NbtCompound -> TAG_COMPOUND
    is NbtIntArray -> TAG_INT_ARRAY
    is NbtLongArray -> TAG_LONG_ARRAY
}

private fun encodeModifiedUtf(value: String): ByteArray {
    var byteLength = 0
    for (character in value) {
        val code = character.code
        byteLength += when {
            code in 1..0x7F -> 1
            code <= 0x7FF -> 2
            else -> 3
        }
        if (byteLength > 65_535) {
            throw NbtFormatException(
                "NBT string exceeds modified-UTF unsigned-short limit",
            )
        }
    }

    val result = ByteArray(byteLength)
    var index = 0
    for (character in value) {
        val code = character.code
        when {
            code in 1..0x7F -> {
                result[index++] = code.toByte()
            }

            code <= 0x7FF -> {
                result[index++] = (0xC0 or (code shr 6)).toByte()
                result[index++] = (0x80 or (code and 0x3F)).toByte()
            }

            else -> {
                result[index++] = (0xE0 or (code shr 12)).toByte()
                result[index++] =
                    (0x80 or ((code shr 6) and 0x3F)).toByte()
                result[index++] = (0x80 or (code and 0x3F)).toByte()
            }
        }
    }
    return result
}

private fun decodeModifiedUtf(bytes: ByteArray): String {
    val characters = CharArray(bytes.size)
    var byteIndex = 0
    var characterIndex = 0

    while (byteIndex < bytes.size) {
        val first = bytes[byteIndex].toInt() and 0xFF
        when {
            first <= 0x7F -> {
                characters[characterIndex++] = first.toChar()
                byteIndex++
            }

            first shr 4 == 0xC || first shr 4 == 0xD -> {
                if (byteIndex + 1 >= bytes.size) {
                    throw NbtFormatException(
                        "Malformed modified UTF-8 at byte $byteIndex",
                    )
                }
                val second = bytes[byteIndex + 1].toInt() and 0xFF
                if (second and 0xC0 != 0x80) {
                    throw NbtFormatException(
                        "Malformed modified UTF-8 at byte $byteIndex",
                    )
                }
                characters[characterIndex++] =
                    (((first and 0x1F) shl 6) or (second and 0x3F)).toChar()
                byteIndex += 2
            }

            first shr 4 == 0xE -> {
                if (byteIndex + 2 >= bytes.size) {
                    throw NbtFormatException(
                        "Malformed modified UTF-8 at byte $byteIndex",
                    )
                }
                val second = bytes[byteIndex + 1].toInt() and 0xFF
                val third = bytes[byteIndex + 2].toInt() and 0xFF
                if (second and 0xC0 != 0x80 || third and 0xC0 != 0x80) {
                    throw NbtFormatException(
                        "Malformed modified UTF-8 at byte $byteIndex",
                    )
                }
                characters[characterIndex++] =
                    (((first and 0x0F) shl 12) or
                            ((second and 0x3F) shl 6) or
                            (third and 0x3F)).toChar()
                byteIndex += 3
            }

            else -> throw NbtFormatException(
                "Malformed modified UTF-8 at byte $byteIndex",
            )
        }
    }
    return characters.concatToString(endIndex = characterIndex)
}

private const val TAG_END = 0
private const val TAG_BYTE = 1
private const val TAG_SHORT = 2
private const val TAG_INT = 3
private const val TAG_LONG = 4
private const val TAG_FLOAT = 5
private const val TAG_DOUBLE = 6
private const val TAG_BYTE_ARRAY = 7
private const val TAG_STRING = 8
private const val TAG_LIST = 9
private const val TAG_COMPOUND = 10
private const val TAG_INT_ARRAY = 11
private const val TAG_LONG_ARRAY = 12
