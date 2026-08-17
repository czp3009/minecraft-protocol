@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.NbtTagDecoder
import com.hiczp.minecraft.nbt.NbtTagSerializer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule

/** Direct binary NBT event decoder; no intermediate tag tree is built. */
internal class NbtBinaryDecoder(
    private val reader: NbtBinaryReader,
    private val configuration: NbtFormatConfiguration,
    private val path: String,
    private val type: Int?,
    private val unwrapListElement: Boolean = false,
) : Decoder, NbtTagDecoder {
    override val serializersModule: SerializersModule
        get() = configuration.serializersModule

    override fun decodeNbtTag(): NbtTag {
        val actualType = requirePresentType()
        val tag = reader.readPayload(actualType)
        return if (unwrapListElement && actualType == TAG_COMPOUND) {
            (tag as com.hiczp.minecraft.nbt.NbtCompound).unwrapListElement()
        } else {
            tag
        }
    }

    override fun decodeNotNullMark(): Boolean = type != null

    override fun decodeNull(): Nothing? {
        if (type != null) {
            throw NbtDecodingException("Expected null at $path")
        }
        return null
    }

    override fun decodeBoolean(): Boolean {
        requireType(TAG_BYTE, "TAG_Byte")
        val value = reader.readByte().toInt()
        if (configuration.strictBooleans && value !in 0..1) {
            throw NbtDecodingException(
                "Invalid Boolean byte $value at $path",
            )
        }
        return value != 0
    }

    override fun decodeByte(): Byte {
        requireType(TAG_BYTE, "TAG_Byte")
        return reader.readByte()
    }

    override fun decodeShort(): Short {
        requireType(TAG_SHORT, "TAG_Short")
        return reader.readShort()
    }

    override fun decodeChar(): Nothing =
        throw NbtDecodingException(
            "Char is unsupported by the NBT mapping at $path",
        )

    override fun decodeInt(): Int {
        requireType(TAG_INT, "TAG_Int")
        return reader.readInt()
    }

    override fun decodeLong(): Long {
        requireType(TAG_LONG, "TAG_Long")
        return reader.readLong()
    }

    override fun decodeFloat(): Float {
        requireType(TAG_FLOAT, "TAG_Float")
        return Float.fromBits(reader.readInt())
    }

    override fun decodeDouble(): Double {
        requireType(TAG_DOUBLE, "TAG_Double")
        return Double.fromBits(reader.readLong())
    }

    override fun decodeString(): String {
        requireType(TAG_STRING, "TAG_String")
        return reader.readModifiedUtf()
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = decodeString()
        val index = enumDescriptor.getElementIndex(name)
        if (index == CompositeDecoder.UNKNOWN_NAME) {
            throw NbtDecodingException(
                "Unknown ${enumDescriptor.serialName} value '$name' at $path",
            )
        }
        return index
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        rejectBinaryPolymorphism(descriptor, path)
        return when (descriptor.kind) {
            StructureKind.CLASS,
            StructureKind.OBJECT,
                -> {
                requireType(TAG_COMPOUND, "TAG_Compound")
                NbtBinaryClassDecoder(
                    reader,
                    configuration,
                    descriptor,
                    path,
                )
            }

            StructureKind.LIST -> {
                requireType(TAG_LIST, "TAG_List")
                val elementType = reader.readUnsignedByte()
                val size = reader.checkedLength(
                    reader.readInt(),
                    "NBT list",
                )
                if (size > 0) {
                    validateType(elementType)
                    if (elementType == TAG_END) {
                        throw NbtDecodingException(
                            "Non-empty NBT list has TAG_End element type at $path",
                        )
                    }
                }
                NbtBinaryListDecoder(
                    reader,
                    configuration,
                    descriptor,
                    path,
                    size,
                    elementType,
                )
            }

            StructureKind.MAP -> {
                if (!descriptor.hasBinaryStringMapKey()) {
                    throw NbtDecodingException(
                        "NBT maps require Kotlin String keys at $path",
                    )
                }
                requireType(TAG_COMPOUND, "TAG_Compound")
                NbtBinaryMapDecoder(
                    reader,
                    configuration,
                    descriptor,
                    path,
                )
            }

            else -> throw NbtDecodingException(
                "Unsupported structure ${descriptor.serialName} at $path",
            )
        }
    }

    override fun <T : Any?> decodeSerializableValue(
        deserializer: DeserializationStrategy<T>,
    ): T {
        if (deserializer is NbtTagSerializer<*>) {
            return deserializer.deserialize(this)
        }
        @Suppress("UNCHECKED_CAST")
        return when (deserializer.descriptor.serialName) {
            BYTE_ARRAY_SERIAL_NAME -> {
                requireType(TAG_BYTE_ARRAY, "TAG_Byte_Array")
                val length = reader.checkedLength(
                    reader.readInt(),
                    "NBT byte array",
                )
                reader.readBytes(length) as T
            }

            INT_ARRAY_SERIAL_NAME -> {
                requireType(TAG_INT_ARRAY, "TAG_Int_Array")
                val length = reader.checkedLength(
                    reader.readInt(),
                    "NBT int array",
                )
                IntArray(length) { reader.readInt() } as T
            }

            LONG_ARRAY_SERIAL_NAME -> {
                requireType(TAG_LONG_ARRAY, "TAG_Long_Array")
                val length = reader.checkedLength(
                    reader.readInt(),
                    "NBT long array",
                )
                LongArray(length) { reader.readLong() } as T
            }

            else -> deserializer.deserialize(this)
        }
    }

    private fun requireType(expected: Int, expectedName: String) {
        val actual = requirePresentType()
        if (actual != expected) {
            throw NbtDecodingException(
                "Expected $expectedName at $path, got ${binaryTagName(actual)}",
            )
        }
    }

    private fun requirePresentType(): Int = type
        ?: throw NbtDecodingException("Missing NBT value at $path")
}

private abstract class NbtBinaryCompositeDecoder(
    protected val configuration: NbtFormatConfiguration,
    protected val descriptor: SerialDescriptor,
    protected val path: String,
) : CompositeDecoder {
    override val serializersModule: SerializersModule
        get() = configuration.serializersModule

    override fun decodeBooleanElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = child(index).decodeBoolean()

    override fun decodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Byte = child(index).decodeByte()

    override fun decodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Short = child(index).decodeShort()

    override fun decodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Char = child(index).decodeChar()

    override fun decodeIntElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Int = child(index).decodeInt()

    override fun decodeLongElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Long = child(index).decodeLong()

    override fun decodeFloatElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Float = child(index).decodeFloat()

    override fun decodeDoubleElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Double = child(index).decodeDouble()

    override fun decodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): String = child(index).decodeString()

    override fun decodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Decoder = child(index)

    override fun <T : Any?> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T = child(index).decodeSerializableValue(deserializer)

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? = child(index).decodeNullableSerializableValue(deserializer)

    protected abstract fun child(index: Int): Decoder

    protected fun requireDescriptor(actual: SerialDescriptor) {
        if (actual != descriptor) {
            throw NbtDecodingException("Mismatched structure at $path")
        }
    }
}

private class NbtBinaryClassDecoder(
    private val reader: NbtBinaryReader,
    configuration: NbtFormatConfiguration,
    descriptor: SerialDescriptor,
    path: String,
) : NbtBinaryCompositeDecoder(configuration, descriptor, path) {
    private val seen = BooleanArray(descriptor.elementsCount)
    private val missingNullable = ArrayDeque<Int>()
    private var currentIndex = CompositeDecoder.DECODE_DONE
    private var currentType: Int? = null
    private var ended = false

    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        requireDescriptor(descriptor)
        if (currentIndex != CompositeDecoder.DECODE_DONE) {
            throw NbtDecodingException(
                "Previous NBT compound value was not decoded at $path",
            )
        }
        if (missingNullable.isNotEmpty()) {
            currentIndex = missingNullable.removeFirst()
            currentType = null
            return currentIndex
        }
        if (ended) return CompositeDecoder.DECODE_DONE

        while (true) {
            val type = reader.readUnsignedByte()
            if (type == TAG_END) {
                ended = true
                for (index in 0 until descriptor.elementsCount) {
                    if (
                        !seen[index] &&
                        descriptor.getElementDescriptor(index).isNullable &&
                        !descriptor.isElementOptional(index)
                    ) {
                        missingNullable += index
                    }
                }
                return if (missingNullable.isEmpty()) {
                    CompositeDecoder.DECODE_DONE
                } else {
                    currentIndex = missingNullable.removeFirst()
                    currentType = null
                    currentIndex
                }
            }
            validateType(type)
            val name = reader.readModifiedUtf()
            val index = descriptor.getElementIndex(name)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                if (!configuration.ignoreUnknownKeys) {
                    throw NbtDecodingException(
                        "Unknown key '$name' for ${descriptor.serialName} at $path",
                    )
                }
                reader.skipPayload(type)
                continue
            }
            if (seen[index]) {
                throw NbtDecodingException(
                    "Duplicate key '$name' for ${descriptor.serialName} at $path",
                )
            }
            seen[index] = true
            currentIndex = index
            currentType = type
            return index
        }
    }

    override fun child(index: Int): Decoder {
        if (index != currentIndex) {
            throw NbtDecodingException(
                "NBT compound requested unexpected index $index at $path",
            )
        }
        val name = descriptor.getElementName(index)
        val decoder = NbtBinaryDecoder(
            reader,
            configuration,
            "$path.$name",
            currentType,
        )
        currentIndex = CompositeDecoder.DECODE_DONE
        currentType = null
        return decoder
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        requireDescriptor(descriptor)
        if (!ended || currentIndex != CompositeDecoder.DECODE_DONE) {
            throw NbtDecodingException("NBT compound was not fully decoded at $path")
        }
    }
}

private class NbtBinaryListDecoder(
    private val reader: NbtBinaryReader,
    configuration: NbtFormatConfiguration,
    descriptor: SerialDescriptor,
    path: String,
    private val size: Int,
    private val elementType: Int,
) : NbtBinaryCompositeDecoder(configuration, descriptor, path) {
    private var nextIndex = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        requireDescriptor(descriptor)
        return size
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        requireDescriptor(descriptor)
        return if (nextIndex < size) nextIndex++
        else CompositeDecoder.DECODE_DONE
    }

    override fun child(index: Int): Decoder {
        if (index != nextIndex || index >= size) {
            throw NbtDecodingException(
                "NBT list requested out-of-sequence index $index at $path",
            )
        }
        nextIndex++
        return NbtBinaryDecoder(
            reader,
            configuration,
            "$path[$index]",
            elementType,
            unwrapListElement = true,
        )
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        requireDescriptor(descriptor)
        if (nextIndex != size) {
            throw NbtDecodingException(
                "NBT list at $path decoded $nextIndex element(s); expected $size",
            )
        }
    }
}

private class NbtBinaryMapDecoder(
    private val reader: NbtBinaryReader,
    configuration: NbtFormatConfiguration,
    descriptor: SerialDescriptor,
    path: String,
) : NbtBinaryCompositeDecoder(configuration, descriptor, path) {
    private var nextIndex = 0
    private var currentName: String? = null
    private var currentType: Int? = null
    private var ended = false

    override fun decodeSequentially(): Boolean = false

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
        -1

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        requireDescriptor(descriptor)
        if (ended) return CompositeDecoder.DECODE_DONE
        if (currentName != null) return nextIndex

        val type = reader.readUnsignedByte()
        if (type == TAG_END) {
            ended = true
            return CompositeDecoder.DECODE_DONE
        }
        validateType(type)
        currentType = type
        currentName = reader.readModifiedUtf()
        return nextIndex
    }

    override fun child(index: Int): Decoder {
        if (index != nextIndex) {
            throw NbtDecodingException(
                "NBT map requested out-of-sequence index $index at $path",
            )
        }
        val name = currentName
            ?: throw NbtDecodingException("NBT map has no current entry at $path")
        return if (index % 2 == 0) {
            nextIndex++
            NbtTreeDecoder(NbtString(name), configuration, "$path.$name.key")
        } else {
            val decoder = NbtBinaryDecoder(
                reader,
                configuration,
                "$path.$name",
                currentType,
            )
            nextIndex++
            currentName = null
            currentType = null
            decoder
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        requireDescriptor(descriptor)
        if (!ended || currentName != null) {
            throw NbtDecodingException("NBT map was not fully decoded at $path")
        }
    }
}

private fun rejectBinaryPolymorphism(
    descriptor: SerialDescriptor,
    path: String,
) {
    if (descriptor.kind is PolymorphicKind) {
        throw NbtDecodingException(
            "Polymorphic serializer ${descriptor.serialName} is unsupported at $path",
        )
    }
}

private fun binaryTagName(type: Int): String = when (type) {
    TAG_END -> "TAG_End"
    TAG_BYTE -> "TAG_Byte"
    TAG_SHORT -> "TAG_Short"
    TAG_INT -> "TAG_Int"
    TAG_LONG -> "TAG_Long"
    TAG_FLOAT -> "TAG_Float"
    TAG_DOUBLE -> "TAG_Double"
    TAG_BYTE_ARRAY -> "TAG_Byte_Array"
    TAG_STRING -> "TAG_String"
    TAG_LIST -> "TAG_List"
    TAG_COMPOUND -> "TAG_Compound"
    TAG_INT_ARRAY -> "TAG_Int_Array"
    TAG_LONG_ARRAY -> "TAG_Long_Array"
    else -> "unknown tag type $type"
}

private fun SerialDescriptor.hasBinaryStringMapKey(): Boolean =
    getElementDescriptor(0).serialName == STRING_SERIAL_NAME

private const val BYTE_ARRAY_SERIAL_NAME = "kotlin.ByteArray"
private const val INT_ARRAY_SERIAL_NAME = "kotlin.IntArray"
private const val LONG_ARRAY_SERIAL_NAME = "kotlin.LongArray"
private const val STRING_SERIAL_NAME = "kotlin.String"
