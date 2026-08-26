@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

internal class NbtTreeEncoder(
    private val nbtFormatConfiguration: NbtFormatConfiguration,
    private val path: String,
    private val emit: (NbtTag) -> Unit,
) : Encoder, NbtTagEncoder {
    override val serializersModule: SerializersModule
        get() = nbtFormatConfiguration.serializersModule

    override fun encodeNbtTag(nbtTag: NbtTag) = emit(nbtTag)

    override fun encodeNull(): Nothing =
        throw NbtEncodingException("NBT has no null value at $path")

    override fun encodeBoolean(value: Boolean) =
        emit(NbtByte(if (value) 1 else 0))

    override fun encodeByte(value: Byte) = emit(NbtByte(value))

    override fun encodeShort(value: Short) = emit(NbtShort(value))

    override fun encodeChar(value: Char): Nothing =
        throw NbtEncodingException(
            "Char is unsupported by the NBT mapping at $path",
        )

    override fun encodeInt(value: Int) = emit(NbtInt(value))

    override fun encodeLong(value: Long) = emit(NbtLong(value))

    override fun encodeFloat(value: Float) = emit(NbtFloat(value))

    override fun encodeDouble(value: Double) = emit(NbtDouble(value))

    override fun encodeString(value: String) = emit(NbtString(value))

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        emit(NbtString(enumDescriptor.getElementName(index)))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        compositeEncoder(descriptor, collectionSize = null)

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder = compositeEncoder(descriptor, collectionSize)

    override fun <T : Any?> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        val serialName = serializer.descriptor.serialName
        when {
            value is ByteArray && serialName == BYTE_ARRAY_SERIAL_NAME -> {
                emit(NbtByteArray(value))
            }

            value is IntArray && serialName == INT_ARRAY_SERIAL_NAME -> {
                emit(NbtIntArray(value))
            }

            value is LongArray && serialName == LONG_ARRAY_SERIAL_NAME -> {
                emit(NbtLongArray(value))
            }

            else -> serializer.serialize(this, value)
        }
    }

    private fun compositeEncoder(
        serialDescriptor: SerialDescriptor,
        collectionSize: Int?,
    ): CompositeEncoder {
        if (serialDescriptor.kind is PolymorphicKind) {
            throw NbtEncodingException(
                "Polymorphic serializer ${serialDescriptor.serialName} is unsupported at $path",
            )
        }
        return when (serialDescriptor.kind) {
            StructureKind.CLASS,
            StructureKind.OBJECT,
                -> NbtClassEncoder(
                nbtFormatConfiguration,
                serialDescriptor,
                path,
                emit,
            )

            StructureKind.LIST -> NbtListEncoder(
                nbtFormatConfiguration,
                serialDescriptor,
                path,
                emit,
                collectionSize,
            )

            StructureKind.MAP -> {
                if (!serialDescriptor.hasStringMapKey()) {
                    throw NbtEncodingException(
                        "NBT maps require Kotlin String keys at $path",
                    )
                }
                NbtMapEncoder(
                    nbtFormatConfiguration,
                    serialDescriptor,
                    path,
                    emit,
                    collectionSize,
                )
            }

            else -> throw NbtEncodingException(
                "Unsupported structure ${serialDescriptor.serialName} at $path",
            )
        }
    }
}

private abstract class NbtCompositeEncoder(
    protected val nbtFormatConfiguration: NbtFormatConfiguration,
    protected val serialDescriptor: SerialDescriptor,
    protected val path: String,
) : CompositeEncoder {
    override val serializersModule: SerializersModule
        get() = nbtFormatConfiguration.serializersModule

    override fun shouldEncodeElementDefault(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = nbtFormatConfiguration.encodeDefaults

    override fun encodeBooleanElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Boolean,
    ) = accept(index, NbtByte(if (value) 1 else 0))

    override fun encodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Byte,
    ) = accept(index, NbtByte(value))

    override fun encodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Short,
    ) = accept(index, NbtShort(value))

    override fun encodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Char,
    ): Nothing = throw NbtEncodingException(
        "Char is unsupported by the NBT mapping at ${elementPath(index)}",
    )

    override fun encodeIntElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Int,
    ) = accept(index, NbtInt(value))

    override fun encodeLongElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Long,
    ) = accept(index, NbtLong(value))

    override fun encodeFloatElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Float,
    ) = accept(index, NbtFloat(value))

    override fun encodeDoubleElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Double,
    ) = accept(index, NbtDouble(value))

    override fun encodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: String,
    ) = accept(index, NbtString(value))

    override fun encodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Encoder = child(index)

    override fun <T : Any?> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        child(index).encodeSerializableValue(serializer, value)
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) {
        if (value == null) {
            acceptNull(index)
        } else {
            child(index).encodeSerializableValue(serializer, value)
        }
    }

    protected abstract fun accept(index: Int, nbtTag: NbtTag)

    protected open fun acceptNull(index: Int) {
        throw NbtEncodingException(
            "NBT has no null value at ${elementPath(index)}",
        )
    }

    protected open fun elementPath(index: Int): String = "$path[$index]"

    private fun child(index: Int): NbtTreeEncoder =
        NbtTreeEncoder(nbtFormatConfiguration, elementPath(index)) {
            accept(index, it)
        }
}

private class NbtClassEncoder(
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
    private val emit: (NbtTag) -> Unit,
) : NbtCompositeEncoder(nbtFormatConfiguration, serialDescriptor, path) {
    private val values = linkedMapOf<String, NbtTag>()

    override fun accept(index: Int, nbtTag: NbtTag) {
        values[serialDescriptor.getElementName(index)] = nbtTag
    }

    override fun acceptNull(index: Int) {
        // Null compound properties are represented by absence.
    }

    override fun elementPath(index: Int): String =
        "$path.${serialDescriptor.getElementName(index)}"

    override fun endStructure(descriptor: SerialDescriptor) {
        requireMatchingDescriptor(descriptor)
        emit(NbtCompound(values))
    }

    private fun requireMatchingDescriptor(actual: SerialDescriptor) {
        if (actual != serialDescriptor) {
            throw NbtEncodingException("Mismatched structure at $path")
        }
    }
}

private class NbtListEncoder(
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
    private val emit: (NbtTag) -> Unit,
    expectedSize: Int?,
) : NbtCompositeEncoder(nbtFormatConfiguration, serialDescriptor, path) {
    private val values = ArrayList<NbtTag>(expectedSize ?: 0)

    override fun accept(index: Int, nbtTag: NbtTag) {
        if (index != values.size) {
            throw NbtEncodingException(
                "NBT list index $index is out of sequence at $path",
            )
        }
        values += nbtTag
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (descriptor != serialDescriptor) {
            throw NbtEncodingException("Mismatched list structure at $path")
        }
        emit(NbtList(values))
    }
}

private class NbtMapEncoder(
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
    private val emit: (NbtTag) -> Unit,
    expectedSize: Int?,
) : NbtCompositeEncoder(nbtFormatConfiguration, serialDescriptor, path) {
    private val values = LinkedHashMap<String, NbtTag>(expectedSize ?: 0)
    private var pendingKey: String? = null
    private var nextIndex = 0

    override fun accept(index: Int, nbtTag: NbtTag) {
        if (index != nextIndex) {
            throw NbtEncodingException(
                "NBT map index $index is out of sequence at $path",
            )
        }
        nextIndex++
        if (index % 2 == 0) {
            val key = (nbtTag as? NbtString)?.value
                ?: throw NbtEncodingException(
                    "NBT map keys must serialize as strings at $path",
                )
            pendingKey = key
        } else {
            val key = pendingKey
                ?: throw NbtEncodingException("NBT map value has no key at $path")
            if (values.put(key, nbtTag) != null) {
                throw NbtEncodingException(
                    "NBT map contains duplicate encoded key '$key' at $path",
                )
            }
            pendingKey = null
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (descriptor != serialDescriptor) {
            throw NbtEncodingException("Mismatched map structure at $path")
        }
        if (pendingKey != null) {
            throw NbtEncodingException("NBT map has a key without a value at $path")
        }
        emit(NbtCompound(values))
    }
}

internal class NbtTreeDecoder(
    private val nbtTag: NbtTag?,
    private val nbtFormatConfiguration: NbtFormatConfiguration,
    private val path: String,
) : Decoder, NbtTagDecoder {
    override val serializersModule: SerializersModule
        get() = nbtFormatConfiguration.serializersModule

    override fun decodeNbtTag(): NbtTag =
        nbtTag ?: throw NbtDecodingException("Missing raw NBT tag at $path")

    override fun decodeNotNullMark(): Boolean = nbtTag != null

    override fun decodeNull(): Nothing? {
        if (nbtTag != null) {
            throw NbtDecodingException("Expected null at $path")
        }
        return null
    }

    override fun decodeBoolean(): Boolean {
        val value = requireTag<NbtByte>("TAG_Byte").value.toInt()
        if (nbtFormatConfiguration.strictBooleans && value !in 0..1) {
            throw NbtDecodingException(
                "Invalid Boolean byte $value at $path",
            )
        }
        return value != 0
    }

    override fun decodeByte(): Byte = requireTag<NbtByte>("TAG_Byte").value

    override fun decodeShort(): Short = requireTag<NbtShort>("TAG_Short").value

    override fun decodeChar(): Nothing =
        throw NbtDecodingException(
            "Char is unsupported by the NBT mapping at $path",
        )

    override fun decodeInt(): Int = requireTag<NbtInt>("TAG_Int").value

    override fun decodeLong(): Long = requireTag<NbtLong>("TAG_Long").value

    override fun decodeFloat(): Float = requireTag<NbtFloat>("TAG_Float").value

    override fun decodeDouble(): Double =
        requireTag<NbtDouble>("TAG_Double").value

    override fun decodeString(): String =
        requireTag<NbtString>("TAG_String").value

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
        if (descriptor.kind is PolymorphicKind) {
            throw NbtDecodingException(
                "Polymorphic serializer ${descriptor.serialName} is unsupported at $path",
            )
        }
        return when (descriptor.kind) {
            StructureKind.CLASS,
            StructureKind.OBJECT,
                -> NbtClassDecoder(
                requireTag("TAG_Compound"),
                nbtFormatConfiguration,
                descriptor,
                path,
            )

            StructureKind.LIST -> NbtListDecoder(
                requireTag("TAG_List"),
                nbtFormatConfiguration,
                descriptor,
                path,
            )

            StructureKind.MAP -> {
                if (!descriptor.hasStringMapKey()) {
                    throw NbtDecodingException(
                        "NBT maps require Kotlin String keys at $path",
                    )
                }
                NbtMapDecoder(
                    requireTag("TAG_Compound"),
                    nbtFormatConfiguration,
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
        if (nbtTag != null) {
            @Suppress("UNCHECKED_CAST")
            when (deserializer.descriptor.serialName) {
                BYTE_ARRAY_SERIAL_NAME -> return requireTag<NbtByteArray>(
                    "TAG_Byte_Array",
                ).value as T

                INT_ARRAY_SERIAL_NAME -> return requireTag<NbtIntArray>(
                    "TAG_Int_Array",
                ).value as T

                LONG_ARRAY_SERIAL_NAME -> return requireTag<NbtLongArray>(
                    "TAG_Long_Array",
                ).value as T
            }
        }
        return deserializer.deserialize(this)
    }

    private inline fun <reified T : NbtTag> requireTag(expected: String): T =
        nbtTag as? T ?: throw NbtDecodingException(
            "Expected $expected at $path, got ${nbtTag?.let { it::class.simpleName } ?: "a missing value"}",
        )
}

private abstract class NbtCompositeDecoder(
    protected val nbtFormatConfiguration: NbtFormatConfiguration,
    protected val serialDescriptor: SerialDescriptor,
    protected val path: String,
) : CompositeDecoder {
    override val serializersModule: SerializersModule
        get() = nbtFormatConfiguration.serializersModule

    override fun decodeBooleanElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = child(index).decodeBoolean()

    override fun decodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Byte = child(index).decodeByte()

    override fun decodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Char = child(index).decodeChar()

    override fun decodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Short = child(index).decodeShort()

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

    override fun endStructure(descriptor: SerialDescriptor) {
        if (descriptor != serialDescriptor) {
            throw NbtDecodingException("Mismatched structure at $path")
        }
    }

    protected abstract fun tagAt(index: Int): NbtTag?

    protected open fun elementPath(index: Int): String = "$path[$index]"

    private fun child(index: Int): NbtTreeDecoder =
        NbtTreeDecoder(tagAt(index), nbtFormatConfiguration, elementPath(index))
}

private class NbtClassDecoder(
    nbtCompound: NbtCompound,
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
) : NbtCompositeDecoder(nbtFormatConfiguration, serialDescriptor, path) {
    private val values = nbtCompound.value
    private val indexes: List<Int>
    private var nextIndex = 0

    init {
        val present = linkedSetOf<Int>()
        for (name in values.keys) {
            val index = serialDescriptor.getElementIndex(name)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                if (!nbtFormatConfiguration.ignoreUnknownKeys) {
                    throw NbtDecodingException(
                        "Unknown key '$name' for ${serialDescriptor.serialName} at $path",
                    )
                }
            } else {
                present += index
            }
        }
        for (index in 0 until serialDescriptor.elementsCount) {
            if (
                index !in present &&
                serialDescriptor.getElementDescriptor(index).isNullable &&
                !serialDescriptor.isElementOptional(index)
            ) {
                present += index
            }
        }
        indexes = present.toList()
    }

    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (nextIndex < indexes.size) indexes[nextIndex++]
        else CompositeDecoder.DECODE_DONE

    override fun tagAt(index: Int): NbtTag? =
        values[serialDescriptor.getElementName(index)]

    override fun elementPath(index: Int): String =
        "$path.${serialDescriptor.getElementName(index)}"
}

private class NbtListDecoder(
    nbtList: NbtList,
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
) : NbtCompositeDecoder(nbtFormatConfiguration, serialDescriptor, path) {
    private val values = nbtList.value
    private var nextIndex = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
        values.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (nextIndex < values.size) nextIndex++
        else CompositeDecoder.DECODE_DONE

    override fun tagAt(index: Int): NbtTag = values[index]
}

private class NbtMapDecoder(
    nbtCompound: NbtCompound,
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
) : NbtCompositeDecoder(nbtFormatConfiguration, serialDescriptor, path) {
    private val elements: List<NbtTag> = nbtCompound.value.let { values ->
        buildList(values.size * 2) {
            for ((name, value) in values) {
                add(NbtString(name))
                add(value)
            }
        }
    }
    private var nextIndex = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
        elements.size / 2

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (nextIndex < elements.size) nextIndex++
        else CompositeDecoder.DECODE_DONE

    override fun tagAt(index: Int): NbtTag = elements[index]
}

private const val BYTE_ARRAY_SERIAL_NAME = "kotlin.ByteArray"
private const val INT_ARRAY_SERIAL_NAME = "kotlin.IntArray"
private const val LONG_ARRAY_SERIAL_NAME = "kotlin.LongArray"
private const val STRING_SERIAL_NAME = "kotlin.String"

internal fun SerialDescriptor.hasStringMapKey(): Boolean =
    getElementDescriptor(0).serialName == STRING_SERIAL_NAME
