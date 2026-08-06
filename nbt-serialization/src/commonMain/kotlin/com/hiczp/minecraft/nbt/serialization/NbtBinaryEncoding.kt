@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.NbtTagEncoder
import com.hiczp.minecraft.nbt.NbtTagSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/** Direct kotlinx.serialization event encoder for the binary NBT grammar. */
internal class NbtBinaryEncoder(
    private val writer: NbtBinaryWriter,
    private val configuration: NbtFormatConfiguration,
    private val path: String,
    private val depth: Int,
    private val writeHeader: (Int) -> Unit,
) : Encoder, NbtTagEncoder {
    private var emitted = false
    private var preparedRawListType: Int? = null

    override val serializersModule: SerializersModule
        get() = configuration.serializersModule

    fun requireValue() {
        if (!emitted) {
            throw NbtEncodingException("Serializer emitted no NBT value at $path")
        }
    }

    override fun encodeNbtTag(value: NbtTag) {
        beginValue(typeOf(value))
        writer.writePayload(value, depth)
    }

    override fun encodeNull(): Nothing =
        throw NbtEncodingException("NBT has no null value at $path")

    override fun encodeBoolean(value: Boolean) {
        beginValue(TAG_BYTE)
        writer.writeByte(if (value) 1 else 0)
    }

    override fun encodeByte(value: Byte) {
        beginValue(TAG_BYTE)
        writer.writeByte(value.toInt())
    }

    override fun encodeShort(value: Short) {
        beginValue(TAG_SHORT)
        writer.writeShort(value.toInt())
    }

    override fun encodeChar(value: Char): Nothing =
        throw NbtEncodingException(
            "Char is unsupported by the NBT mapping at $path",
        )

    override fun encodeInt(value: Int) {
        beginValue(TAG_INT)
        writer.writeInt(value)
    }

    override fun encodeLong(value: Long) {
        beginValue(TAG_LONG)
        writer.writeLong(value)
    }

    override fun encodeFloat(value: Float) {
        beginValue(TAG_FLOAT)
        writer.writeInt(value.toBits())
    }

    override fun encodeDouble(value: Double) {
        beginValue(TAG_DOUBLE)
        writer.writeLong(value.toBits())
    }

    override fun encodeString(value: String) {
        beginValue(TAG_STRING)
        writer.writeModifiedUtf(value)
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
                    writer,
                    configuration,
                    descriptor,
                    path,
                    depth,
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
        if (collectionSize !in 0..configuration.maximumCollectionSize) {
            throw NbtLimitException(
                "Collection size $collectionSize at $path exceeds configured limit ${configuration.maximumCollectionSize}",
            )
        }
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
                writer.writeByte(elementType)
                writer.writeInt(collectionSize)
                NbtBinaryListEncoder(
                    writer,
                    configuration,
                    descriptor,
                    path,
                    depth,
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
                    writer,
                    configuration,
                    descriptor,
                    path,
                    depth,
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
                writer.checkByteArrayLength(value.size)
                beginValue(TAG_BYTE_ARRAY)
                writer.writeInt(value.size)
                writer.writeBytes(value)
            }

            value is IntArray &&
                    serializer.descriptor.serialName == INT_ARRAY_SERIAL_NAME -> {
                writer.checkCollectionLength(value.size, "NBT int array")
                beginValue(TAG_INT_ARRAY)
                writer.writeInt(value.size)
                value.forEach(writer::writeInt)
            }

            value is LongArray &&
                    serializer.descriptor.serialName == LONG_ARRAY_SERIAL_NAME -> {
                writer.checkCollectionLength(value.size, "NBT long array")
                beginValue(TAG_LONG_ARRAY)
                writer.writeInt(value.size)
                value.forEach(writer::writeLong)
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
        writer.checkDepth(depth)
        writeHeader(type)
        emitted = true
    }
}

private abstract class NbtBinaryCompositeEncoder(
    protected val writer: NbtBinaryWriter,
    protected val configuration: NbtFormatConfiguration,
    protected val descriptor: SerialDescriptor,
    protected val path: String,
) : CompositeEncoder {
    override val serializersModule: SerializersModule
        get() = configuration.serializersModule

    override fun shouldEncodeElementDefault(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = configuration.encodeDefaults

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
    writer: NbtBinaryWriter,
    configuration: NbtFormatConfiguration,
    descriptor: SerialDescriptor,
    path: String,
    private val depth: Int,
) : NbtBinaryCompositeEncoder(writer, configuration, descriptor, path) {
    private var entryCount = 0

    override fun elementEncoder(index: Int): Encoder {
        val name = descriptor.getElementName(index)
        return NbtBinaryEncoder(
            writer,
            configuration,
            "$path.$name",
            depth + 1,
        ) { type ->
            if (type == TAG_END) {
                throw NbtEncodingException(
                    "NBT compound property '$name' cannot be TAG_End at $path",
                )
            }
            if (entryCount >= configuration.maximumCollectionSize) {
                throw NbtLimitException(
                    "NBT compound exceeds configured entry limit ${configuration.maximumCollectionSize}",
                )
            }
            entryCount++
            writer.writeByte(type)
            writer.writeModifiedUtf(name)
        }
    }

    override fun encodeNull(index: Int) = Unit

    override fun elementPath(index: Int): String =
        "$path.${descriptor.getElementName(index)}"

    override fun endStructure(descriptor: SerialDescriptor) {
        requireDescriptor(descriptor, this.descriptor, path)
        writer.writeByte(TAG_END)
    }
}

private class NbtBinaryListEncoder(
    writer: NbtBinaryWriter,
    configuration: NbtFormatConfiguration,
    descriptor: SerialDescriptor,
    path: String,
    private val depth: Int,
    private val expectedSize: Int,
    private val elementType: Int,
) : NbtBinaryCompositeEncoder(writer, configuration, descriptor, path) {
    private var nextIndex = 0

    override fun elementEncoder(index: Int): Encoder {
        requireIndex(index)
        return NbtBinaryEncoder(
            writer,
            configuration,
            "$path[$index]",
            depth + 1,
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
        requireDescriptor(descriptor, this.descriptor, path)
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

    private fun writeRawElement(value: NbtTag) {
        val actualType = typeOf(value)
        if (actualType == TAG_END) {
            throw NbtEncodingException("NBT lists cannot contain TAG_End at $path")
        }
        if (
            elementType == TAG_COMPOUND &&
            (value !is NbtCompound || value.isListWrapper())
        ) {
            writer.writeListWrapper(value, depth + 1)
        } else {
            if (actualType != elementType) {
                throw NbtEncodingException(
                    "NBT list element at $path[$nextIndex] has tag type $actualType; expected $elementType",
                )
            }
            writer.writePayload(value, depth + 1)
        }
    }
}

private class NbtBinaryMapEncoder(
    writer: NbtBinaryWriter,
    configuration: NbtFormatConfiguration,
    descriptor: SerialDescriptor,
    path: String,
    private val depth: Int,
    private val expectedSize: Int,
) : NbtBinaryCompositeEncoder(writer, configuration, descriptor, path) {
    private var nextIndex = 0
    private var pendingKey: String? = null
    private val keys = mutableSetOf<String>()

    override fun elementEncoder(index: Int): Encoder {
        requireIndex(index)
        return if (index % 2 == 0) {
            NbtStringCaptureEncoder(configuration, "$path[${index / 2}].key")
        } else {
            val key = pendingKey
                ?: throw NbtEncodingException("NBT map value has no key at $path")
            NbtBinaryEncoder(
                writer,
                configuration,
                "$path.$key",
                depth + 1,
            ) { type ->
                if (type == TAG_END) {
                    throw NbtEncodingException(
                        "NBT map value for '$key' cannot be TAG_End at $path",
                    )
                }
                writer.writeByte(type)
                writer.writeModifiedUtf(key)
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
        requireDescriptor(descriptor, this.descriptor, path)
        if (pendingKey != null || nextIndex != expectedSize * 2) {
            throw NbtEncodingException(
                "NBT map at $path emitted ${nextIndex / 2} complete entry/entries; expected $expectedSize",
            )
        }
        writer.writeByte(TAG_END)
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
    private val configuration: NbtFormatConfiguration,
    private val path: String,
) : Encoder {
    override val serializersModule: SerializersModule
        get() = configuration.serializersModule

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

private fun rejectPolymorphism(descriptor: SerialDescriptor) {
    if (descriptor.kind is PolymorphicKind) {
        throw NbtEncodingException(
            "Polymorphic serializer ${descriptor.serialName} is unsupported",
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

private fun nbtTypeOf(descriptor: SerialDescriptor, path: String): Int {
    when (descriptor.serialName) {
        BYTE_ARRAY_SERIAL_NAME -> return TAG_BYTE_ARRAY
        INT_ARRAY_SERIAL_NAME -> return TAG_INT_ARRAY
        LONG_ARRAY_SERIAL_NAME -> return TAG_LONG_ARRAY
    }
    if (descriptor.isInline) {
        return nbtTypeOf(descriptor.getElementDescriptor(0), path)
    }
    return when (val kind = descriptor.kind) {
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
            "Polymorphic serializer ${descriptor.serialName} is unsupported at $path",
        )

        else -> throw NbtEncodingException(
            "Cannot determine the NBT list element type for ${descriptor.serialName} ($kind) at $path",
        )
    }
}

private fun rawListTypeOf(value: Any?, descriptor: SerialDescriptor): Int? {
    if (descriptor.kind != StructureKind.LIST || value !is List<*>) return null
    val elementDescriptor = descriptor.getElementDescriptor(0)
    if (!elementDescriptor.serialName.startsWith(NBT_SERIAL_NAME_PREFIX)) return null
    var type = TAG_END
    for (element in value) {
        val tag = element as? NbtTag ?: return null
        val next = typeOf(tag)
        type = when {
            type == TAG_END -> next
            type == next -> type
            else -> TAG_COMPOUND
        }
    }
    return type
}

private fun SerialDescriptor.hasStringMapKey(): Boolean =
    getElementDescriptor(0).serialName == STRING_SERIAL_NAME

private const val BYTE_ARRAY_SERIAL_NAME = "kotlin.ByteArray"
private const val INT_ARRAY_SERIAL_NAME = "kotlin.IntArray"
private const val LONG_ARRAY_SERIAL_NAME = "kotlin.LongArray"
private const val STRING_SERIAL_NAME = "kotlin.String"
private const val NBT_SERIAL_NAME_PREFIX = "com.hiczp.minecraft.nbt.Nbt"
