@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.nbt.NbtEnd
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.NbtTagDecoder
import com.hiczp.minecraft.nbt.NbtTagSerializer
import com.hiczp.minecraft.protocol.model.wire.*
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

internal class MinecraftDecoder(
    private val minecraftReader: MinecraftReader,
    private val minecraftProtocolFormatConfiguration: MinecraftProtocolFormatConfiguration,
    override val serializersModule: SerializersModule,
) : Decoder, CompositeDecoder, NbtTagDecoder {
    private val nbtBinaryCodec: NbtBinaryCodec = NbtBinaryCodec
    private val json: Json = minecraftJson(serializersModule)
    private val frames: MutableList<Frame> = mutableListOf()
    private var pendingHints: List<Annotation> = emptyList()
    private var injectNotNullMark: Boolean = false

    val remaining: Int
        get() = minecraftReader.remaining

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val decodedCollection = when (descriptor.kind) {
            StructureKind.LIST,
            StructureKind.MAP,
                -> readCollectionSize(descriptor)

            else -> null
        }
        frames += Frame(
            descriptor,
            collectionSize = decodedCollection?.size,
            elementHints = decodedCollection?.elementHints.orEmpty(),
        )
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val frame = frames.removeLastOrNull()
            ?: throw MinecraftSerializationException("Decoder structure stack underflow")
        if (frame.serialDescriptor != descriptor) {
            throw MinecraftSerializationException("Mismatched decoder structure")
        }
    }

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val frame = frames.lastOrNull()
            ?: throw MinecraftSerializationException("No active structure")
        val maximumIndex = when (descriptor.kind) {
            StructureKind.LIST -> frame.collectionSize
                ?: throw MinecraftSerializationException("List has no decoded size")

            StructureKind.MAP -> (frame.collectionSize
                ?: throw MinecraftSerializationException("Map has no decoded size")) * 2

            else -> descriptor.elementsCount
        }
        if (frame.nextIndex >= maximumIndex) {
            return CompositeDecoder.DECODE_DONE
        }
        return frame.nextIndex++
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        val frame = frames.lastOrNull()
            ?: throw MinecraftSerializationException("No active collection")
        return frame.collectionSize
            ?: throw MinecraftSerializationException("Structure is not a collection")
    }

    override fun decodeBoolean(): Boolean {
        val value = minecraftReader.readUnsignedByte()
        if (minecraftProtocolFormatConfiguration.strictBooleans && value !in 0..1) {
            throw MinecraftSerializationException("Invalid boolean byte: $value")
        }
        return value != 0
    }

    override fun decodeByte(): Byte = minecraftReader.readByte()

    override fun decodeShort(): Short = minecraftReader.readShort()

    override fun decodeInt(): Int {
        val hints = takePendingHints()
        return when {
            hints.any { it is VarInt || it is VarIntElements } ->
                minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)

            hints.any { it is UnsignedByte } -> minecraftReader.readUnsignedByte()
            hints.any { it is UnsignedShort } -> minecraftReader.readUnsignedShort()
            else -> minecraftReader.readInt()
        }
    }

    override fun decodeLong(): Long =
        if (takePendingHints().any { it is VarLong || it is VarLongElements }) {
            minecraftReader.readVarLong(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
        } else {
            minecraftReader.readLong()
        }

    override fun decodeFloat(): Float = Float.fromBits(minecraftReader.readInt())

    override fun decodeDouble(): Double = Double.fromBits(minecraftReader.readLong())

    override fun decodeChar(): Char = minecraftReader.readUnsignedShort().toChar()

    override fun decodeString(): String {
        val maximum = takePendingHints().filterIsInstance<MaxLength>()
            .singleOrNull()?.characters ?: DEFAULT_STRING_MAXIMUM
        val byteLength = minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
        if (byteLength < 0 || byteLength > maximum * 3L) {
            throw MinecraftSerializationException(
                "Invalid string byte length $byteLength for limit $maximum",
            )
        }
        val value = minecraftReader.readUtf8(byteLength)
        if (value.length > maximum) {
            throw MinecraftSerializationException(
                "String exceeds its limit of $maximum UTF-16 code units",
            )
        }
        return value
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val hints = takePendingHints()
        val value = when (
            hints.filterIsInstance<EnumEncoding>()
                .singleOrNull()?.kind ?: EnumEncodingKind.VAR_INT
        ) {
            EnumEncodingKind.BYTE -> minecraftReader.readByte().toInt()
            EnumEncodingKind.UNSIGNED_BYTE -> minecraftReader.readUnsignedByte()
            EnumEncodingKind.VAR_INT -> minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
            EnumEncodingKind.INT -> minecraftReader.readInt()
        }
        return when {
            hints.any { it is WrappedEnum } ->
                value.mod(enumDescriptor.elementsCount)

            hints.any { it is ClampEnum } ->
                value.coerceIn(0, enumDescriptor.elementsCount - 1)

            hints.any { it is ZeroFallbackEnum } &&
                    value !in 0 until enumDescriptor.elementsCount -> 0

            else -> value
        }
    }

    override fun decodeNotNullMark(): Boolean {
        if (injectNotNullMark) {
            injectNotNullMark = false
            return true
        }
        return decodeBoolean()
    }

    override fun decodeNull(): Nothing? = null

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this

    override fun decodeNbtTag(): NbtTag = nbtBinaryCodec.readAny(minecraftReader)

    override fun decodeBooleanElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = withElementHints(descriptor, index, ::decodeBoolean)

    override fun decodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Byte = withElementHints(descriptor, index, ::decodeByte)

    override fun decodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Short = withElementHints(descriptor, index, ::decodeShort)

    override fun decodeIntElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Int = withElementHints(descriptor, index, ::decodeInt)

    override fun decodeLongElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Long = withElementHints(descriptor, index, ::decodeLong)

    override fun decodeFloatElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Float = withElementHints(descriptor, index, ::decodeFloat)

    override fun decodeDoubleElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Double = withElementHints(descriptor, index, ::decodeDouble)

    override fun decodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Char = withElementHints(descriptor, index, ::decodeChar)

    override fun decodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): String = withElementHints(descriptor, index, ::decodeString)

    override fun decodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Decoder {
        pendingHints = elementHints(descriptor, index)
        return decodeInline(descriptor.getElementDescriptor(index))
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        return decodeSerializableWithHints(
            deserializer,
            elementHints(descriptor, index),
        )
    }

    override fun <T> decodeSerializableValue(
        deserializer: DeserializationStrategy<T>,
    ): T {
        return when {
            isPrimitiveArraySerializer(deserializer) ->
                decodePrimitiveArray(deserializer.descriptor)

            pendingHints.any { it is NetworkNbt } -> {
                if (deserializer !is NbtTagSerializer<*>) {
                    throw MinecraftSerializationException(
                        "@NetworkNbt can only be used with an NbtTag subtype",
                    )
                }
                decodeWithNbtSerializer(deserializer)
            }

            deserializer is NbtTagSerializer<*> ->
                decodeWithNbtSerializer(deserializer)

            deserializer.descriptor.serialName == UUID_SERIAL_NAME -> {
                @Suppress("UNCHECKED_CAST")
                (Uuid.fromByteArray(minecraftReader.readBytes(Uuid.SIZE_BYTES)) as T)
            }

            pendingHints.any { it is LowPrecisionVector } -> {
                if (deserializer.descriptor.serialName != VECTOR_3D_SERIAL_NAME) {
                    throw MinecraftSerializationException(
                        "@LowPrecisionVector can only be used with Vector3d",
                    )
                }
                @Suppress("UNCHECKED_CAST")
                (LowPrecisionVectorCodec.read(minecraftReader, minecraftProtocolFormatConfiguration) as T)
            }

            pendingHints.any { it is Paletted } -> {
                if (deserializer.descriptor.serialName != PALETTED_CONTAINER_SERIAL_NAME) {
                    throw MinecraftSerializationException(
                        "@Paletted can only be used with PalettedContainer",
                    )
                }
                val paletted = pendingHints.filterIsInstance<Paletted>().single()
                @Suppress("UNCHECKED_CAST")
                (
                        PalettedContainerCodec.read(
                            minecraftReader,
                            paletted.kind,
                            minecraftProtocolFormatConfiguration,
                        ) as T
                        )
            }

            else -> deserializer.deserialize(this)
        }
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? = decodeNullableWithHints(
        deserializer,
        elementHints(descriptor, index),
    )

    private fun <T> decodeSerializableWithHints(
        deserializationStrategy: DeserializationStrategy<T>,
        hints: List<Annotation>,
    ): T {
        val byteLengthPrefixed = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (byteLengthPrefixed != null) {
            return readLengthPrefixed(byteLengthPrefixed) { nested ->
                nested.decodeSerializableWithHints(
                    deserializationStrategy,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
        }
        if (hints.any { it is JsonEncoded }) {
            val encoded = withHints(hints.filterNot { it is JsonEncoded }, ::decodeString)
            return try {
                json.decodeFromString(deserializationStrategy, encoded)
            } catch (failure: SerializationException) {
                throw MinecraftSerializationException("Cannot decode JSON protocol value", failure)
            }
        }
        return withHints(hints) {
            decodeSerializableValue(deserializationStrategy)
        }
    }

    private fun <T : Any> decodeNullableWithHints(
        deserializationStrategy: DeserializationStrategy<T?>,
        hints: List<Annotation>,
    ): T? {
        val byteLengthPrefixed = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (byteLengthPrefixed != null) {
            return readLengthPrefixed(byteLengthPrefixed) { nested ->
                nested.decodeNullableWithHints(
                    deserializationStrategy,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
        }
        return withHints(hints) {
            val nullSentinelByte = hints.filterIsInstance<NullSentinelByte>().singleOrNull()
            if (nullSentinelByte != null) {
                validateNullSentinelByte(nullSentinelByte)
                if (minecraftReader.peekByte().toInt() == nullSentinelByte.value) {
                    minecraftReader.readByte()
                    null
                } else {
                    injectNotNullMark = true
                    try {
                        decodeNullableSerializableValue(deserializationStrategy)
                    } finally {
                        injectNotNullMark = false
                    }
                }
            } else if (hints.any { it is OptionalVarInt }) {
                val encoded = minecraftReader.readVarInt(
                    minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers,
                )
                if (encoded == 0) {
                    null
                } else {
                    @Suppress("UNCHECKED_CAST")
                    ((encoded - 1) as T)
                }
            } else if (hints.any { it is NbtEndOptional }) {
                if (deserializationStrategy !is NbtTagSerializer<*>) {
                    throw MinecraftSerializationException(
                        "@NbtEndOptional can only be used with NbtTag",
                    )
                }
                val nbtTag = nbtBinaryCodec.readAny(minecraftReader)
                if (nbtTag === NbtEnd) {
                    null
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (deserializationStrategy.deserializeTag(nbtTag) as T)
                }
            } else {
                decodeNullableSerializableValue(deserializationStrategy)
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

    private inline fun <T> readLengthPrefixed(
        byteLengthPrefixed: ByteLengthPrefixed,
        decode: (MinecraftDecoder) -> T,
    ): T {
        if (byteLengthPrefixed.maxBytes < 0) {
            throw MinecraftSerializationException(
                "ByteLengthPrefixed maxBytes must be non-negative",
            )
        }
        val size = minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
        if (size !in 0..byteLengthPrefixed.maxBytes) {
            throw MinecraftSerializationException(
                "Invalid length-prefixed value size $size; maximum is ${byteLengthPrefixed.maxBytes}",
            )
        }
        val nested = MinecraftDecoder(
            minecraftReader.readBounded(size),
            minecraftProtocolFormatConfiguration,
            serializersModule,
        )
        val value = decode(nested)
        if (nested.remaining != 0) {
            throw MinecraftSerializationException(
                "Length-prefixed value has ${nested.remaining} unread byte(s)",
            )
        }
        return value
    }

    private fun readCollectionSize(
        serialDescriptor: SerialDescriptor,
    ): DecodedCollection {
        val hints = takePendingHints()
        val size = when {
            hints.any { it is RemainingBytes } -> minecraftReader.remaining
            hints.any { it is FixedLength } ->
                hints.filterIsInstance<FixedLength>().single().bytes

            hints.any { it is ChunkSectionCount } ->
                minecraftProtocolFormatConfiguration.chunkSectionCount
                    ?: throw MinecraftSerializationException(
                        "Decoding chunk sections requires chunkSectionCount in MinecraftProtocolFormatConfiguration",
                    )

            hints.any { it is Unprefixed } ->
                throw MinecraftSerializationException(
                    "An unprefixed collection requires a containing custom serializer",
                )

            else -> minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
        }
        if (size < 0) {
            throw MinecraftSerializationException("Invalid collection size: $size")
        }
        validateCollectionHints(size, hints)
        return DecodedCollection(
            size,
            hints.filter {
                it is VarIntElements ||
                        it is VarLongElements ||
                        it is MaxLength
            },
        )
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

    private inline fun <T> withElementHints(
        serialDescriptor: SerialDescriptor,
        index: Int,
        block: () -> T,
    ): T = withHints(elementHints(serialDescriptor, index), block)

    private fun elementHints(serialDescriptor: SerialDescriptor, index: Int): List<Annotation> {
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> decodePrimitiveArray(serialDescriptor: SerialDescriptor): T {
        val decodedCollection = readCollectionSize(serialDescriptor)
        val size = decodedCollection.size
        val elementHints = decodedCollection.elementHints
        val value: Any = when (serialDescriptor.serialName) {
            BYTE_ARRAY_SERIAL_NAME -> minecraftReader.readBytes(size)
            BOOLEAN_ARRAY_SERIAL_NAME -> {
                requireArrayBytes(size, Byte.SIZE_BYTES)
                BooleanArray(size) {
                    val byte = minecraftReader.readUnsignedByte()
                    if (minecraftProtocolFormatConfiguration.strictBooleans && byte !in 0..1) {
                        throw MinecraftSerializationException("Invalid boolean byte: $byte")
                    }
                    byte != 0
                }
            }

            SHORT_ARRAY_SERIAL_NAME -> {
                requireArrayBytes(size, Short.SIZE_BYTES)
                ShortArray(size) { minecraftReader.readShort() }
            }

            INT_ARRAY_SERIAL_NAME -> {
                val variable = elementHints.any { it is VarIntElements }
                requireArrayBytes(size, if (variable) 1 else Int.SIZE_BYTES)
                IntArray(size) {
                    if (variable) {
                        minecraftReader.readVarInt(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
                    } else {
                        minecraftReader.readInt()
                    }
                }
            }

            LONG_ARRAY_SERIAL_NAME -> {
                val variable = elementHints.any { it is VarLongElements }
                requireArrayBytes(size, if (variable) 1 else Long.SIZE_BYTES)
                LongArray(size) {
                    if (variable) {
                        minecraftReader.readVarLong(minecraftProtocolFormatConfiguration.rejectNonMinimalVarNumbers)
                    } else {
                        minecraftReader.readLong()
                    }
                }
            }

            FLOAT_ARRAY_SERIAL_NAME -> {
                requireArrayBytes(size, Float.SIZE_BYTES)
                FloatArray(size) { Float.fromBits(minecraftReader.readInt()) }
            }

            DOUBLE_ARRAY_SERIAL_NAME -> {
                requireArrayBytes(size, Double.SIZE_BYTES)
                DoubleArray(size) { Double.fromBits(minecraftReader.readLong()) }
            }

            CHAR_ARRAY_SERIAL_NAME -> {
                requireArrayBytes(size, Char.SIZE_BYTES)
                CharArray(size) { minecraftReader.readUnsignedShort().toChar() }
            }

            else -> error("Not a primitive-array descriptor: ${serialDescriptor.serialName}")
        }
        return value as T
    }

    private fun requireArrayBytes(size: Int, minimumElementBytes: Int) {
        if (size > minecraftReader.remaining / minimumElementBytes) {
            throw MinecraftSerializationException(
                "Array declares $size elements but only ${minecraftReader.remaining} payload bytes remain",
            )
        }
    }

    private fun isPrimitiveArraySerializer(deserializationStrategy: DeserializationStrategy<*>): Boolean =
        when (deserializationStrategy.descriptor.serialName) {
            BOOLEAN_ARRAY_SERIAL_NAME -> deserializationStrategy === BooleanArraySerializer()
            BYTE_ARRAY_SERIAL_NAME -> deserializationStrategy === ByteArraySerializer()
            CHAR_ARRAY_SERIAL_NAME -> deserializationStrategy === CharArraySerializer()
            DOUBLE_ARRAY_SERIAL_NAME -> deserializationStrategy === DoubleArraySerializer()
            FLOAT_ARRAY_SERIAL_NAME -> deserializationStrategy === FloatArraySerializer()
            INT_ARRAY_SERIAL_NAME -> deserializationStrategy === IntArraySerializer()
            LONG_ARRAY_SERIAL_NAME -> deserializationStrategy === LongArraySerializer()
            SHORT_ARRAY_SERIAL_NAME -> deserializationStrategy === ShortArraySerializer()
            else -> false
        }

    private fun takePendingHints(): List<Annotation> {
        val result = pendingHints
        pendingHints = emptyList()
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> decodeWithNbtSerializer(
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = deserializationStrategy.deserialize(this)

    private data class Frame(
        val serialDescriptor: SerialDescriptor,
        var nextIndex: Int = 0,
        var collectionSize: Int? = null,
        val elementHints: List<Annotation> = emptyList(),
    )

    private data class DecodedCollection(
        val size: Int,
        val elementHints: List<Annotation>,
    )

    private companion object {
        const val BOOLEAN_ARRAY_SERIAL_NAME: String = "kotlin.BooleanArray"
        const val BYTE_ARRAY_SERIAL_NAME: String = "kotlin.ByteArray"
        const val CHAR_ARRAY_SERIAL_NAME: String = "kotlin.CharArray"
        const val DEFAULT_STRING_MAXIMUM: Int = 32_767
        const val DOUBLE_ARRAY_SERIAL_NAME: String = "kotlin.DoubleArray"
        const val FLOAT_ARRAY_SERIAL_NAME: String = "kotlin.FloatArray"
        const val INT_ARRAY_SERIAL_NAME: String = "kotlin.IntArray"
        const val LONG_ARRAY_SERIAL_NAME: String = "kotlin.LongArray"
        const val VECTOR_3D_SERIAL_NAME: String = "com.hiczp.minecraft.protocol.model.type.Vector3d"
        const val PALETTED_CONTAINER_SERIAL_NAME: String = "com.hiczp.minecraft.protocol.model.type.PalettedContainer"
        const val SHORT_ARRAY_SERIAL_NAME: String = "kotlin.ShortArray"
        const val UUID_SERIAL_NAME: String = "kotlin.uuid.Uuid"
    }
}
