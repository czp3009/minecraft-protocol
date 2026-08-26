@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.nbt.NbtEnd
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.NbtTagEncoder
import com.hiczp.minecraft.nbt.NbtTagSerializer
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import com.hiczp.minecraft.protocol.model.type.Vector3d
import com.hiczp.minecraft.protocol.model.wire.*
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

internal class MinecraftEncoder(
    private val minecraftWriter: MinecraftWriter,
    private val minecraftProtocolFormatConfiguration: MinecraftProtocolFormatConfiguration,
    override val serializersModule: SerializersModule,
) : AbstractEncoder(), NbtTagEncoder {
    private val nbtBinaryCodec: NbtBinaryCodec = NbtBinaryCodec
    private val frames: MutableList<Frame> = mutableListOf()
    private var pendingHints: List<Annotation> = emptyList()

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        frames += Frame(descriptor)
        return this
    }

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val descriptorIndex = when (descriptor.kind) {
            StructureKind.LIST -> 0
            StructureKind.MAP -> index % descriptor.elementsCount
            else -> index
        }
        pendingHints = combineHints(
            descriptor.getElementAnnotations(descriptorIndex),
            frames.lastOrNull()?.elementHints.orEmpty(),
        )
        return true
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        if (collectionSize < 0) {
            throw MinecraftSerializationException("Invalid collection size: $collectionSize")
        }
        val elementHints = writeCollectionHeader(descriptor, collectionSize)
        frames += Frame(
            descriptor,
            elementHints = elementHints,
        )
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val frame = frames.removeLastOrNull()
            ?: throw MinecraftSerializationException("Encoder structure stack underflow")
        if (frame.serialDescriptor != descriptor) {
            throw MinecraftSerializationException("Mismatched encoder structure")
        }
    }

    override fun encodeBoolean(value: Boolean): Unit = minecraftWriter.writeByte(if (value) 1 else 0)

    override fun encodeByte(value: Byte): Unit = minecraftWriter.writeByte(value.toInt())

    override fun encodeShort(value: Short): Unit = minecraftWriter.writeShort(value.toInt())

    override fun encodeInt(value: Int) {
        val hints = takePendingHints()
        when {
            hints.any { it is VarInt || it is VarIntElements } -> minecraftWriter.writeVarInt(value)
            hints.any { it is UnsignedByte } -> {
                require(value in 0..255) { "Unsigned byte is outside 0..255: $value" }
                minecraftWriter.writeByte(value)
            }

            hints.any { it is UnsignedShort } -> {
                require(value in 0..65_535) { "Unsigned short is outside 0..65535: $value" }
                minecraftWriter.writeShort(value)
            }

            else -> minecraftWriter.writeInt(value)
        }
    }

    override fun encodeLong(value: Long) {
        if (takePendingHints().any { it is VarLong || it is VarLongElements }) {
            minecraftWriter.writeVarLong(value)
        } else {
            minecraftWriter.writeLong(value)
        }
    }

    override fun encodeFloat(value: Float): Unit = minecraftWriter.writeInt(value.toBits())

    override fun encodeDouble(value: Double): Unit = minecraftWriter.writeLong(value.toBits())

    override fun encodeChar(value: Char): Unit = minecraftWriter.writeShort(value.code)

    override fun encodeString(value: String) {
        val maximum = takePendingHints().filterIsInstance<MaxLength>()
            .singleOrNull()?.characters ?: DEFAULT_STRING_MAXIMUM
        val byteCount = utf8ByteCount(value)
        if (value.length > maximum || byteCount > maximum * 3L) {
            throw MinecraftSerializationException(
                "String exceeds its protocol limit of $maximum UTF-16 code units",
            )
        }
        minecraftWriter.writeVarInt(byteCount.toInt())
        minecraftWriter.writeString(value)
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        when (
            takePendingHints().filterIsInstance<EnumEncoding>()
                .singleOrNull()?.kind ?: EnumEncodingKind.VAR_INT
        ) {
            EnumEncodingKind.BYTE,
            EnumEncodingKind.UNSIGNED_BYTE,
                -> minecraftWriter.writeByte(index)

            EnumEncodingKind.VAR_INT -> minecraftWriter.writeVarInt(index)
            EnumEncodingKind.INT -> minecraftWriter.writeInt(index)
        }
    }

    override fun encodeNull(): Unit = encodeBoolean(false)

    override fun encodeNotNullMark(): Unit = encodeBoolean(true)

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun encodeNbtTag(nbtTag: NbtTag) {
        nbtBinaryCodec.writeAny(minecraftWriter, nbtTag)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        encodeSerializableWithHints(
            serializer,
            value,
            elementHints(descriptor, index),
        )
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) {
        encodeNullableWithHints(
            serializer,
            value,
            elementHints(descriptor, index),
        )
    }

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        when {
            encodePrimitiveArray(serializer, value) -> Unit
            serializer is NbtTagSerializer<*> -> serializer.serialize(this, value)
            value is Uuid -> minecraftWriter.write(value.toByteArray())
            pendingHints.any { it is NetworkNbt } -> {
                throw MinecraftSerializationException(
                    "@NetworkNbt can only be used with an NbtTag subtype",
                )
            }

            pendingHints.any { it is LowPrecisionVector } -> {
                if (value !is Vector3d) {
                    throw MinecraftSerializationException(
                        "@LowPrecisionVector can only be used with Vector3d",
                    )
                }
                LowPrecisionVectorCodec.write(minecraftWriter, value)
            }

            pendingHints.any { it is Paletted } -> {
                if (value !is PalettedContainer) {
                    throw MinecraftSerializationException(
                        "@Paletted can only be used with PalettedContainer",
                    )
                }
                val paletted = pendingHints.filterIsInstance<Paletted>().single()
                PalettedContainerCodec.write(
                    minecraftWriter,
                    value,
                    paletted.kind,
                    minecraftProtocolFormatConfiguration,
                )
            }

            else -> serializer.serialize(this, value)
        }
    }

    private fun <T> encodeSerializableWithHints(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        hints: List<Annotation>,
    ) {
        val byteLengthPrefixed = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (byteLengthPrefixed != null) {
            writeLengthPrefixed(byteLengthPrefixed) { nested ->
                nested.encodeSerializableWithHints(
                    serializationStrategy,
                    value,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
            return
        }
        withHints(hints) {
            encodeSerializableValue(serializationStrategy, value)
        }
    }

    private fun <T : Any> encodeNullableWithHints(
        serializationStrategy: SerializationStrategy<T>,
        value: T?,
        hints: List<Annotation>,
    ) {
        val byteLengthPrefixed = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (byteLengthPrefixed != null) {
            writeLengthPrefixed(byteLengthPrefixed) { nested ->
                nested.encodeNullableWithHints(
                    serializationStrategy,
                    value,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
            return
        }
        withHints(hints) {
            val nullSentinelByte = hints.filterIsInstance<NullSentinelByte>().singleOrNull()
            if (nullSentinelByte != null) {
                validateNullSentinelByte(nullSentinelByte)
                if (value == null) {
                    minecraftWriter.writeByte(nullSentinelByte.value)
                } else {
                    encodeSerializableValue(serializationStrategy, value)
                }
            } else if (hints.any { it is OptionalVarInt }) {
                val id = value as? Int
                if (id != null && id < 0) {
                    throw MinecraftSerializationException(
                        "@OptionalVarInt value must be non-negative: $id",
                    )
                }
                minecraftWriter.writeVarInt(id?.plus(1) ?: 0)
            } else if (hints.any { it is NbtEndOptional }) {
                if (
                    serializationStrategy !is NbtTagSerializer<*>
                ) {
                    throw MinecraftSerializationException(
                        "@NbtEndOptional can only be used with NbtTag",
                    )
                }
                if (value == null) {
                    nbtBinaryCodec.writeAny(minecraftWriter, NbtEnd)
                } else {
                    encodeSerializableValue(serializationStrategy, value)
                }
            } else if (value == null) {
                encodeNull()
            } else {
                encodeNotNullMark()
                encodeSerializableValue(serializationStrategy, value)
            }
        }
    }

    private fun validateNullSentinelByte(nullSentinelByte: NullSentinelByte) {
        if (nullSentinelByte.value !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            throw MinecraftSerializationException(
                "@NullSentinelByte value must fit a signed byte: ${nullSentinelByte.value}",
            )
        }
    }

    private inline fun writeLengthPrefixed(
        byteLengthPrefixed: ByteLengthPrefixed,
        encode: (MinecraftEncoder) -> Unit,
    ) {
        require(byteLengthPrefixed.maxBytes >= 0) {
            "ByteLengthPrefixed maxBytes must be non-negative"
        }
        val nestedWriter = Buffer()
        val nested = MinecraftEncoder(
            nestedWriter,
            minecraftProtocolFormatConfiguration,
            serializersModule,
        )
        encode(nested)
        if (nestedWriter.size > byteLengthPrefixed.maxBytes.toLong()) {
            throw MinecraftSerializationException(
                "Length-prefixed value has ${nestedWriter.size} bytes; maximum is ${byteLengthPrefixed.maxBytes}",
            )
        }
        minecraftWriter.writeVarInt(nestedWriter.size.toInt())
        nestedWriter.transferTo(minecraftWriter)
    }

    private fun elementHints(
        serialDescriptor: SerialDescriptor,
        index: Int,
    ): List<Annotation> {
        val descriptorIndex = when (serialDescriptor.kind) {
            StructureKind.LIST -> 0
            StructureKind.MAP -> index % serialDescriptor.elementsCount
            else -> index
        }
        return combineHints(
            serialDescriptor.getElementAnnotations(descriptorIndex),
            frames.lastOrNull()?.elementHints.orEmpty(),
        )
    }

    private fun writeCollectionHeader(
        serialDescriptor: SerialDescriptor,
        collectionSize: Int,
    ): List<Annotation> {
        val hints = takePendingHints()
        validateCollectionHints(collectionSize, hints)
        val fixedLength = hints.filterIsInstance<FixedLength>().singleOrNull()
        if (fixedLength != null && collectionSize != fixedLength.bytes) {
            throw MinecraftSerializationException(
                "Expected exactly ${fixedLength.bytes} elements, got $collectionSize",
            )
        }
        if (hints.any { it is ChunkSectionCount }) {
            if (serialDescriptor.kind != StructureKind.LIST) {
                throw MinecraftSerializationException(
                    "@ChunkSectionCount can only be used with a List",
                )
            }
            val expected = minecraftProtocolFormatConfiguration.chunkSectionCount
            if (expected != null && collectionSize != expected) {
                throw MinecraftSerializationException(
                    "Chunk has $collectionSize sections; active dimension requires $expected",
                )
            }
        }
        if (
            hints.none {
                it is Unprefixed ||
                        it is RemainingBytes ||
                        it is FixedLength ||
                        it is ChunkSectionCount
            }
        ) {
            minecraftWriter.writeVarInt(collectionSize)
        }
        return hints.filter {
            it is VarIntElements ||
                    it is VarLongElements ||
                    it is MaxLength
        }
    }

    private fun <T> encodePrimitiveArray(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ): Boolean {
        if (!isPrimitiveArraySerializer(serializationStrategy)) return false
        val serialDescriptor = serializationStrategy.descriptor
        val size = when {
            serialDescriptor.serialName == BYTE_ARRAY_SERIAL_NAME && value is ByteArray -> value.size
            serialDescriptor.serialName == BOOLEAN_ARRAY_SERIAL_NAME && value is BooleanArray -> value.size
            serialDescriptor.serialName == SHORT_ARRAY_SERIAL_NAME && value is ShortArray -> value.size
            serialDescriptor.serialName == INT_ARRAY_SERIAL_NAME && value is IntArray -> value.size
            serialDescriptor.serialName == LONG_ARRAY_SERIAL_NAME && value is LongArray -> value.size
            serialDescriptor.serialName == FLOAT_ARRAY_SERIAL_NAME && value is FloatArray -> value.size
            serialDescriptor.serialName == DOUBLE_ARRAY_SERIAL_NAME && value is DoubleArray -> value.size
            serialDescriptor.serialName == CHAR_ARRAY_SERIAL_NAME && value is CharArray -> value.size
            else -> return false
        }
        val elementHints = writeCollectionHeader(serialDescriptor, size)
        when (value) {
            is ByteArray -> minecraftWriter.write(value)
            is BooleanArray -> value.forEach { minecraftWriter.writeByte(if (it) 1 else 0) }
            is ShortArray -> value.forEach { minecraftWriter.writeShort(it.toInt()) }
            is IntArray -> if (elementHints.any { it is VarIntElements }) {
                value.forEach(minecraftWriter::writeVarInt)
            } else {
                value.forEach(minecraftWriter::writeInt)
            }

            is LongArray -> if (elementHints.any { it is VarLongElements }) {
                value.forEach(minecraftWriter::writeVarLong)
            } else {
                value.forEach(minecraftWriter::writeLong)
            }

            is FloatArray -> value.forEach { minecraftWriter.writeInt(it.toBits()) }
            is DoubleArray -> value.forEach { minecraftWriter.writeLong(it.toBits()) }
            is CharArray -> value.forEach { minecraftWriter.writeShort(it.code) }
        }
        return true
    }

    private fun isPrimitiveArraySerializer(serializationStrategy: SerializationStrategy<*>): Boolean =
        when (serializationStrategy.descriptor.serialName) {
            BOOLEAN_ARRAY_SERIAL_NAME -> serializationStrategy === BooleanArraySerializer()
            BYTE_ARRAY_SERIAL_NAME -> serializationStrategy === ByteArraySerializer()
            CHAR_ARRAY_SERIAL_NAME -> serializationStrategy === CharArraySerializer()
            DOUBLE_ARRAY_SERIAL_NAME -> serializationStrategy === DoubleArraySerializer()
            FLOAT_ARRAY_SERIAL_NAME -> serializationStrategy === FloatArraySerializer()
            INT_ARRAY_SERIAL_NAME -> serializationStrategy === IntArraySerializer()
            LONG_ARRAY_SERIAL_NAME -> serializationStrategy === LongArraySerializer()
            SHORT_ARRAY_SERIAL_NAME -> serializationStrategy === ShortArraySerializer()
            else -> false
        }

    private inline fun <T> withHints(hints: List<Annotation>, block: () -> T): T {
        val previous = pendingHints
        pendingHints = hints
        return try {
            block()
        } finally {
            pendingHints = previous
        }
    }

    private fun takePendingHints(): List<Annotation> {
        val result = pendingHints
        pendingHints = emptyList()
        return result
    }

    private companion object {
        const val BOOLEAN_ARRAY_SERIAL_NAME: String = "kotlin.BooleanArray"
        const val BYTE_ARRAY_SERIAL_NAME: String = "kotlin.ByteArray"
        const val CHAR_ARRAY_SERIAL_NAME: String = "kotlin.CharArray"
        const val DEFAULT_STRING_MAXIMUM: Int = 32_767
        const val DOUBLE_ARRAY_SERIAL_NAME: String = "kotlin.DoubleArray"
        const val FLOAT_ARRAY_SERIAL_NAME: String = "kotlin.FloatArray"
        const val INT_ARRAY_SERIAL_NAME: String = "kotlin.IntArray"
        const val LONG_ARRAY_SERIAL_NAME: String = "kotlin.LongArray"
        const val SHORT_ARRAY_SERIAL_NAME: String = "kotlin.ShortArray"
    }

    private data class Frame(
        val serialDescriptor: SerialDescriptor,
        val elementHints: List<Annotation> = emptyList(),
    )
}

/** Matches the UTF-8 replacement behavior used by kotlinx.io.Sink.writeString. */
private fun utf8ByteCount(value: String): Long {
    var byteCount = 0L
    var index = 0
    while (index < value.length) {
        val code = value[index].code
        when {
            code < 0x80 -> byteCount++
            code < 0x800 -> byteCount += 2
            code !in 0xD800..0xDFFF -> byteCount += 3
            code <= 0xDBFF &&
                    index + 1 < value.length &&
                    value[index + 1].code in 0xDC00..0xDFFF -> {
                byteCount += 4
                index++
            }

            else -> byteCount++
        }
        index++
    }
    return byteCount
}
