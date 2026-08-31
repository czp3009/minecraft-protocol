@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.NbtTagEncoder
import com.hiczp.minecraft.nbt.NbtTagSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/** Direct kotlinx.serialization event encoder for the binary NBT grammar. */
internal class NbtBinaryEncoder(
    private val nbtBinaryWriter: NbtBinaryWriter,
    private val nbtFormatConfiguration: NbtFormatConfiguration,
    private val path: String,
    private val writeHeader: (Int) -> Unit,
) : Encoder, NbtTagEncoder {
    private var emitted = false
    private var preparedRawListType: Int? = null

    override val serializersModule: SerializersModule
        get() = nbtFormatConfiguration.serializersModule

    fun requireValue() {
        if (!emitted) {
            throw NbtEncodingException("Serializer emitted no NBT value at $path")
        }
    }

    override fun encodeNbtTag(nbtTag: NbtTag) {
        beginValue(typeOf(nbtTag))
        nbtBinaryWriter.writePayload(nbtTag)
    }

    override fun encodeNull(): Nothing =
        throw NbtEncodingException("NBT has no null value at $path")

    override fun encodeBoolean(value: Boolean) {
        beginValue(TAG_BYTE)
        nbtBinaryWriter.writeByte(if (value) 1 else 0)
    }

    override fun encodeByte(value: Byte) {
        beginValue(TAG_BYTE)
        nbtBinaryWriter.writeByte(value.toInt())
    }

    override fun encodeShort(value: Short) {
        beginValue(TAG_SHORT)
        nbtBinaryWriter.writeShort(value.toInt())
    }

    override fun encodeChar(value: Char): Nothing =
        throw NbtEncodingException(
            "Char is unsupported by the NBT mapping at $path",
        )

    override fun encodeInt(value: Int) {
        beginValue(TAG_INT)
        nbtBinaryWriter.writeInt(value)
    }

    override fun encodeLong(value: Long) {
        beginValue(TAG_LONG)
        nbtBinaryWriter.writeLong(value)
    }

    override fun encodeFloat(value: Float) {
        beginValue(TAG_FLOAT)
        nbtBinaryWriter.writeInt(value.toBits())
    }

    override fun encodeDouble(value: Double) {
        beginValue(TAG_DOUBLE)
        nbtBinaryWriter.writeLong(value.toBits())
    }

    override fun encodeString(value: String) {
        beginValue(TAG_STRING)
        nbtBinaryWriter.writeModifiedUtf(value)
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        encodeString(enumDescriptor.getElementName(index))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        rejectPolymorphism(descriptor)
        return when (descriptor.kind) {
            StructureKind.CLASS,
            StructureKind.OBJECT,
                -> {
                beginValue(TAG_COMPOUND)
                NbtBinaryClassEncoder(
                    nbtBinaryWriter,
                    nbtFormatConfiguration,
                    descriptor,
                    path,
                )
            }

            else -> throw NbtEncodingException(
                "${descriptor.serialName} requires a collection size at $path",
            )
        }
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        rejectPolymorphism(descriptor)
        return when (descriptor.kind) {
            StructureKind.LIST -> {
                val elementType = preparedRawListType
                    ?: nbtTypeOf(descriptor.getElementDescriptor(0), path)
                if (collectionSize > 0 && elementType == TAG_END) {
                    throw NbtEncodingException(
                        "Non-empty NBT list has TAG_End element type at $path",
                    )
                }
                beginValue(TAG_LIST)
                nbtBinaryWriter.writeByte(elementType)
                nbtBinaryWriter.writeInt(collectionSize)
                NbtBinaryListEncoder(
                    nbtBinaryWriter,
                    nbtFormatConfiguration,
                    descriptor,
                    path,
                    collectionSize,
                    elementType,
                )
            }

            StructureKind.MAP -> {
                if (!descriptor.hasStringMapKey()) {
                    throw NbtEncodingException(
                        "NBT maps require Kotlin String keys at $path",
                    )
                }
                beginValue(TAG_COMPOUND)
                NbtBinaryMapEncoder(
                    nbtBinaryWriter,
                    nbtFormatConfiguration,
                    descriptor,
                    path,
                    collectionSize,
                )
            }

            else -> beginStructure(descriptor)
        }
    }

    override fun <T : Any?> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        if (serializer is NbtTagSerializer<*>) {
            serializer.serialize(this, value)
            return
        }
        when {
            value is ByteArray &&
                    serializer.descriptor.serialName == BYTE_ARRAY_SERIAL_NAME -> {
                beginValue(TAG_BYTE_ARRAY)
                nbtBinaryWriter.writeInt(value.size)
                nbtBinaryWriter.writeBytes(value)
            }

            value is IntArray &&
                    serializer.descriptor.serialName == INT_ARRAY_SERIAL_NAME -> {
                beginValue(TAG_INT_ARRAY)
                nbtBinaryWriter.writeInt(value.size)
                value.forEach(nbtBinaryWriter::writeInt)
            }

            value is LongArray &&
                    serializer.descriptor.serialName == LONG_ARRAY_SERIAL_NAME -> {
                beginValue(TAG_LONG_ARRAY)
                nbtBinaryWriter.writeInt(value.size)
                value.forEach(nbtBinaryWriter::writeLong)
            }

            else -> {
                val previousListType = preparedRawListType
                preparedRawListType = rawListTypeOf(value, serializer.descriptor)
                try {
                    serializer.serialize(this, value)
                } finally {
                    preparedRawListType = previousListType
                }
            }
        }
    }

    private fun beginValue(type: Int) {
        if (emitted) {
            throw NbtEncodingException(
                "Serializer emitted more than one NBT value at $path",
            )
        }
        writeHeader(type)
        emitted = true
    }
}

private abstract class NbtBinaryCompositeEncoder(
    protected val nbtBinaryWriter: NbtBinaryWriter,
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
    ) = encode(index) { it.encodeBoolean(value) }

    override fun encodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Byte,
    ) = encode(index) { it.encodeByte(value) }

    override fun encodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Short,
    ) = encode(index) { it.encodeShort(value) }

    override fun encodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Char,
    ) = encode(index) { it.encodeChar(value) }

    override fun encodeIntElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Int,
    ) = encode(index) { it.encodeInt(value) }

    override fun encodeLongElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Long,
    ) = encode(index) { it.encodeLong(value) }

    override fun encodeFloatElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Float,
    ) = encode(index) { it.encodeFloat(value) }

    override fun encodeDoubleElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Double,
    ) = encode(index) { it.encodeDouble(value) }

    override fun encodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: String,
    ) = encode(index) { it.encodeString(value) }

    override fun encodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Encoder = elementEncoder(index)

    override fun <T : Any?> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) = encode(index) { it.encodeSerializableValue(serializer, value) }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) {
        if (value == null) {
            encodeNull(index)
        } else {
            encode(index) { it.encodeSerializableValue(serializer, value) }
        }
    }

    protected abstract fun elementEncoder(index: Int): Encoder

    protected open fun encodeNull(index: Int) {
        throw NbtEncodingException(
            "NBT has no null value at ${elementPath(index)}",
        )
    }

    protected open fun elementPath(index: Int): String = "$path[$index]"

    protected open fun finishElement(index: Int, encoder: Encoder) {
        (encoder as? NbtBinaryEncoder)?.requireValue()
    }

    protected fun encode(index: Int, block: (Encoder) -> Unit) {
        val encoder = elementEncoder(index)
        block(encoder)
        finishElement(index, encoder)
    }
}

private class NbtBinaryClassEncoder(
    nbtBinaryWriter: NbtBinaryWriter,
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
) : NbtBinaryCompositeEncoder(nbtBinaryWriter, nbtFormatConfiguration, serialDescriptor, path) {
    override fun elementEncoder(index: Int): Encoder {
        val name = serialDescriptor.getElementName(index)
        return NbtBinaryEncoder(
            nbtBinaryWriter,
            nbtFormatConfiguration,
            "$path.$name",
        ) { type ->
            if (type == TAG_END) {
                throw NbtEncodingException(
                    "NBT compound property '$name' cannot be TAG_End at $path",
                )
            }
            nbtBinaryWriter.writeByte(type)
            nbtBinaryWriter.writeModifiedUtf(name)
        }
    }

    override fun encodeNull(index: Int) = Unit

    override fun elementPath(index: Int): String =
        "$path.${serialDescriptor.getElementName(index)}"

    override fun endStructure(descriptor: SerialDescriptor) {
        requireDescriptor(descriptor, serialDescriptor, path)
        nbtBinaryWriter.writeByte(TAG_END)
    }
}

private class NbtBinaryListEncoder(
    nbtBinaryWriter: NbtBinaryWriter,
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
    private val expectedSize: Int,
    private val elementType: Int,
) : NbtBinaryCompositeEncoder(nbtBinaryWriter, nbtFormatConfiguration, serialDescriptor, path) {
    private var nextIndex = 0

    override fun elementEncoder(index: Int): Encoder {
        requireIndex(index)
        return NbtBinaryEncoder(
            nbtBinaryWriter,
            nbtFormatConfiguration,
            "$path[$index]",
        ) { actualType ->
            if (actualType != elementType) {
                throw NbtEncodingException(
                    "NBT list element at $path[$index] has tag type $actualType; expected $elementType",
                )
            }
        }
    }

    override fun <T : Any?> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        if (serializer is NbtTagSerializer<*> && value is NbtTag) {
            requireIndex(index)
            writeRawElement(value)
            nextIndex++
        } else {
            super.encodeSerializableElement(descriptor, index, serializer, value)
        }
    }

    override fun finishElement(index: Int, encoder: Encoder) {
        super.finishElement(index, encoder)
        nextIndex++
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        requireDescriptor(descriptor, serialDescriptor, path)
        if (nextIndex != expectedSize) {
            throw NbtEncodingException(
                "NBT list at $path emitted $nextIndex element(s); expected $expectedSize",
            )
        }
    }

    private fun requireIndex(index: Int) {
        if (index != nextIndex || index >= expectedSize) {
            throw NbtEncodingException(
                "NBT list index $index is out of sequence at $path",
            )
        }
    }

    private fun writeRawElement(nbtTag: NbtTag) {
        val actualType = typeOf(nbtTag)
        if (actualType == TAG_END) {
            throw NbtEncodingException("NBT lists cannot contain TAG_End at $path")
        }
        if (
            elementType == TAG_COMPOUND &&
            (nbtTag !is NbtCompound || nbtTag.isListWrapper())
        ) {
            nbtBinaryWriter.writeListWrapper(nbtTag)
        } else {
            if (actualType != elementType) {
                throw NbtEncodingException(
                    "NBT list element at $path[$nextIndex] has tag type $actualType; expected $elementType",
                )
            }
            nbtBinaryWriter.writePayload(nbtTag)
        }
    }
}

private class NbtBinaryMapEncoder(
    nbtBinaryWriter: NbtBinaryWriter,
    nbtFormatConfiguration: NbtFormatConfiguration,
    serialDescriptor: SerialDescriptor,
    path: String,
    private val expectedSize: Int,
) : NbtBinaryCompositeEncoder(nbtBinaryWriter, nbtFormatConfiguration, serialDescriptor, path) {
    private var nextIndex = 0
    private var pendingKey: String? = null
    private val keys = mutableSetOf<String>()

    override fun elementEncoder(index: Int): Encoder {
        requireIndex(index)
        return if (index % 2 == 0) {
            NbtStringCaptureEncoder(nbtFormatConfiguration, "$path[${index / 2}].key")
        } else {
            val key = pendingKey
                ?: throw NbtEncodingException("NBT map value has no key at $path")
            NbtBinaryEncoder(
                nbtBinaryWriter,
                nbtFormatConfiguration,
                "$path.$key",
            ) { type ->
                if (type == TAG_END) {
                    throw NbtEncodingException(
                        "NBT map value for '$key' cannot be TAG_End at $path",
                    )
                }
                nbtBinaryWriter.writeByte(type)
                nbtBinaryWriter.writeModifiedUtf(key)
            }
        }
    }

    override fun finishElement(index: Int, encoder: Encoder) {
        if (index % 2 == 0) {
            val key = (encoder as NbtStringCaptureEncoder).value
                ?: throw NbtEncodingException(
                    "NBT map key serializer emitted no string at $path",
                )
            if (!keys.add(key)) {
                throw NbtEncodingException(
                    "NBT map contains duplicate encoded key '$key' at $path",
                )
            }
            pendingKey = key
        } else {
            super.finishElement(index, encoder)
            pendingKey = null
        }
        nextIndex++
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        requireDescriptor(descriptor, serialDescriptor, path)
        if (pendingKey != null || nextIndex != expectedSize * 2) {
            throw NbtEncodingException(
                "NBT map at $path emitted ${nextIndex / 2} complete entry/entries; expected $expectedSize",
            )
        }
        nbtBinaryWriter.writeByte(TAG_END)
    }

    private fun requireIndex(index: Int) {
        if (index != nextIndex || index >= expectedSize * 2) {
            throw NbtEncodingException(
                "NBT map index $index is out of sequence at $path",
            )
        }
    }
}

private class NbtStringCaptureEncoder(
    private val nbtFormatConfiguration: NbtFormatConfiguration,
    private val path: String,
) : Encoder {
    override val serializersModule: SerializersModule
        get() = nbtFormatConfiguration.serializersModule

    var value: String? = null
        private set

    override fun encodeString(value: String) {
        if (this.value != null) {
            throw NbtEncodingException(
                "NBT map key serializer emitted more than one string at $path",
            )
        }
        this.value = value
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        unsupported()

    override fun encodeBoolean(value: Boolean): Nothing = unsupported()
    override fun encodeByte(value: Byte): Nothing = unsupported()
    override fun encodeShort(value: Short): Nothing = unsupported()
    override fun encodeChar(value: Char): Nothing = unsupported()
    override fun encodeInt(value: Int): Nothing = unsupported()
    override fun encodeLong(value: Long): Nothing = unsupported()
    override fun encodeFloat(value: Float): Nothing = unsupported()
    override fun encodeDouble(value: Double): Nothing = unsupported()
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int): Nothing =
        unsupported()

    override fun encodeNull(): Nothing = unsupported()

    private fun unsupported(): Nothing =
        throw NbtEncodingException(
            "NBT map keys must serialize as strings at $path",
        )
}

private fun rejectPolymorphism(serialDescriptor: SerialDescriptor) {
    if (serialDescriptor.kind is PolymorphicKind) {
        throw NbtEncodingException(
            "Polymorphic serializer ${serialDescriptor.serialName} is unsupported",
        )
    }
}

private fun requireDescriptor(
    actual: SerialDescriptor,
    expected: SerialDescriptor,
    path: String,
) {
    if (actual != expected) {
        throw NbtEncodingException("Mismatched structure at $path")
    }
}

private fun nbtTypeOf(serialDescriptor: SerialDescriptor, path: String): Int {
    when (serialDescriptor.serialName) {
        BYTE_ARRAY_SERIAL_NAME -> return TAG_BYTE_ARRAY
        INT_ARRAY_SERIAL_NAME -> return TAG_INT_ARRAY
        LONG_ARRAY_SERIAL_NAME -> return TAG_LONG_ARRAY
    }
    if (serialDescriptor.isInline) {
        return nbtTypeOf(serialDescriptor.getElementDescriptor(0), path)
    }
    return when (val serialKind = serialDescriptor.kind) {
        PrimitiveKind.BOOLEAN,
        PrimitiveKind.BYTE,
            -> TAG_BYTE

        PrimitiveKind.SHORT -> TAG_SHORT
        PrimitiveKind.INT -> TAG_INT
        PrimitiveKind.LONG -> TAG_LONG
        PrimitiveKind.FLOAT -> TAG_FLOAT
        PrimitiveKind.DOUBLE -> TAG_DOUBLE
        PrimitiveKind.STRING -> TAG_STRING
        SerialKind.ENUM -> TAG_STRING
        StructureKind.LIST -> TAG_LIST
        StructureKind.CLASS,
        StructureKind.OBJECT,
        StructureKind.MAP,
            -> TAG_COMPOUND

        is PolymorphicKind -> throw NbtEncodingException(
            "Polymorphic serializer ${serialDescriptor.serialName} is unsupported at $path",
        )

        else -> throw NbtEncodingException(
            "Cannot determine the NBT list element type for ${serialDescriptor.serialName} ($serialKind) at $path",
        )
    }
}

private fun rawListTypeOf(value: Any?, serialDescriptor: SerialDescriptor): Int? {
    if (serialDescriptor.kind != StructureKind.LIST || value !is List<*>) return null
    val elementDescriptor = serialDescriptor.getElementDescriptor(0)
    if (!elementDescriptor.serialName.startsWith(NBT_SERIAL_NAME_PREFIX)) return null
    var type = TAG_END
    for (element in value) {
        val nbtTag = element as? NbtTag ?: return null
        val next = typeOf(nbtTag)
        type = when {
            type == TAG_END -> next
            type == next -> type
            else -> TAG_COMPOUND
        }
    }
    return type
}

private const val BYTE_ARRAY_SERIAL_NAME = "kotlin.ByteArray"
private const val INT_ARRAY_SERIAL_NAME = "kotlin.IntArray"
private const val LONG_ARRAY_SERIAL_NAME = "kotlin.LongArray"
private const val NBT_SERIAL_NAME_PREFIX = "com.hiczp.minecraft.nbt.Nbt"
