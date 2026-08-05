@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.model.type.NbtCompound
import com.hiczp.minecraft.protocol.model.type.NbtEnd
import com.hiczp.minecraft.protocol.model.wire.*
import com.hiczp.minecraft.protocol.serialization.MinecraftFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

internal class MinecraftDecoder(
    private val reader: MinecraftReader,
    private val configuration: MinecraftFormatConfiguration,
    override val serializersModule: SerializersModule,
) : Decoder, CompositeDecoder {
    private val nbtCodec: NbtBinaryCodec = NbtBinaryCodec(configuration)
    private val frames: MutableList<Frame> = mutableListOf()
    private var pendingHints: List<Annotation> = emptyList()
    private var injectNotNullMark: Boolean = false

    val remaining: Int
        get() = reader.remaining

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val collection = when (descriptor.kind) {
            StructureKind.LIST,
            StructureKind.MAP,
                -> readCollectionSize(descriptor)

            else -> null
        }
        frames += Frame(
            descriptor,
            collectionSize = collection?.size,
            elementHints = collection?.elementHints.orEmpty(),
        )
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val frame = frames.removeLastOrNull()
            ?: throw MinecraftSerializationException("Decoder structure stack underflow")
        if (frame.descriptor != descriptor) {
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
        val value = reader.readUnsignedByte()
        if (configuration.strictBooleans && value !in 0..1) {
            throw MinecraftSerializationException("Invalid boolean byte: $value")
        }
        return value != 0
    }

    override fun decodeByte(): Byte = reader.readByte()

    override fun decodeShort(): Short = reader.readShort()

    override fun decodeInt(): Int {
        val hints = takePendingHints()
        return when {
            hints.any { it is VarInt || it is VarIntElements } ->
                reader.readVarInt(configuration.rejectNonMinimalVarNumbers)

            hints.any { it is UnsignedByte } -> reader.readUnsignedByte()
            hints.any { it is UnsignedShort } -> reader.readUnsignedShort()
            else -> reader.readInt()
        }
    }

    override fun decodeLong(): Long =
        if (takePendingHints().any { it is VarLong || it is VarLongElements }) {
            reader.readVarLong(configuration.rejectNonMinimalVarNumbers)
        } else {
            reader.readLong()
        }

    override fun decodeFloat(): Float = Float.fromBits(reader.readInt())

    override fun decodeDouble(): Double = Double.fromBits(reader.readLong())

    override fun decodeChar(): Char = reader.readUnsignedShort().toChar()

    override fun decodeString(): String {
        val maximum = takePendingHints().filterIsInstance<MaxLength>()
            .singleOrNull()?.characters ?: DEFAULT_STRING_MAXIMUM
        val byteLength = reader.readVarInt(configuration.rejectNonMinimalVarNumbers)
        if (byteLength < 0 || byteLength > maximum * 3L) {
            throw MinecraftSerializationException(
                "Invalid string byte length $byteLength for limit $maximum",
            )
        }
        val value = try {
            reader.readBytes(byteLength).decodeToString(throwOnInvalidSequence = true)
        } catch (cause: Throwable) {
            throw MinecraftSerializationException("Invalid UTF-8 string", cause)
        }
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
            EnumEncodingKind.BYTE -> reader.readByte().toInt()
            EnumEncodingKind.UNSIGNED_BYTE -> reader.readUnsignedByte()
            EnumEncodingKind.VAR_INT -> reader.readVarInt(configuration.rejectNonMinimalVarNumbers)
            EnumEncodingKind.INT -> reader.readInt()
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
            pendingHints.any { it is NetworkNbt } -> {
                val value = nbtCodec.readUnnamed(reader)
                if (deserializer.descriptor.serialName == NBT_COMPOUND_SERIAL_NAME &&
                    value !is NbtCompound
                ) {
                    throw MinecraftSerializationException(
                        "Expected an NBT compound, got ${value::class.simpleName}",
                    )
                }
                @Suppress("UNCHECKED_CAST")
                (value as T)
            }

            isNbtDescriptor(deserializer.descriptor) -> {
                @Suppress("UNCHECKED_CAST")
                (nbtCodec.readUnnamed(reader) as T)
            }

            isUuidDescriptor(deserializer.descriptor) -> {
                @Suppress("UNCHECKED_CAST")
                (Uuid.fromByteArray(reader.readBytes(Uuid.SIZE_BYTES)) as T)
            }

            pendingHints.any { it is LowPrecisionVector } -> {
                if (!isVector3dDescriptor(deserializer.descriptor)) {
                    throw MinecraftSerializationException(
                        "@LowPrecisionVector can only be used with Vector3d",
                    )
                }
                @Suppress("UNCHECKED_CAST")
                (LowPrecisionVectorCodec.read(reader, configuration) as T)
            }

            pendingHints.any { it is Paletted } -> {
                if (!isPalettedContainerDescriptor(deserializer.descriptor)) {
                    throw MinecraftSerializationException(
                        "@Paletted can only be used with PalettedContainer",
                    )
                }
                val annotation = pendingHints.filterIsInstance<Paletted>().single()
                @Suppress("UNCHECKED_CAST")
                (
                        PalettedContainerCodec.read(
                            reader,
                            annotation.kind,
                            configuration,
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
        deserializer: DeserializationStrategy<T>,
        hints: List<Annotation>,
    ): T {
        val lengthPrefix = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (lengthPrefix != null) {
            return readLengthPrefixed(lengthPrefix) { nested ->
                nested.decodeSerializableWithHints(
                    deserializer,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
        }
        return withHints(hints) {
            decodeSerializableValue(deserializer)
        }
    }

    private fun <T : Any> decodeNullableWithHints(
        deserializer: DeserializationStrategy<T?>,
        hints: List<Annotation>,
    ): T? {
        val lengthPrefix = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (lengthPrefix != null) {
            return readLengthPrefixed(lengthPrefix) { nested ->
                nested.decodeNullableWithHints(
                    deserializer,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
        }
        return withHints(hints) {
            val nullSentinel = hints.filterIsInstance<NullSentinelByte>().singleOrNull()
            if (nullSentinel != null) {
                validateNullSentinelByte(nullSentinel)
                if (reader.peekByte().toInt() == nullSentinel.value) {
                    reader.readByte()
                    null
                } else {
                    injectNotNullMark = true
                    try {
                        decodeNullableSerializableValue(deserializer)
                    } finally {
                        injectNotNullMark = false
                    }
                }
            } else if (hints.any { it is OptionalVarInt }) {
                val encoded = reader.readVarInt(
                    configuration.rejectNonMinimalVarNumbers,
                )
                if (encoded == 0) {
                    null
                } else {
                    @Suppress("UNCHECKED_CAST")
                    ((encoded - 1) as T)
                }
            } else if (hints.any { it is NbtEndOptional }) {
                val networkNbt = hints.any { it is NetworkNbt }
                if (!isNbtDescriptor(deserializer.descriptor) && !networkNbt) {
                    throw MinecraftSerializationException(
                        "@NbtEndOptional can only be used with NbtTag",
                    )
                }
                val value = nbtCodec.readUnnamed(reader)
                if (value === NbtEnd) {
                    null
                } else {
                    if (
                        networkNbt &&
                        deserializer.descriptor.serialName ==
                        NBT_COMPOUND_SERIAL_NAME &&
                        value !is NbtCompound
                    ) {
                        throw MinecraftSerializationException(
                            "Expected an NBT Compound, got ${value::class.simpleName}",
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    (value as T)
                }
            } else {
                decodeNullableSerializableValue(deserializer)
            }
        }
    }

    private fun validateNullSentinelByte(annotation: NullSentinelByte) {
        if (annotation.value !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            throw MinecraftSerializationException(
                "@NullSentinelByte value must fit a signed byte: ${annotation.value}",
            )
        }
    }

    private inline fun <T> readLengthPrefixed(
        annotation: ByteLengthPrefixed,
        decode: (MinecraftDecoder) -> T,
    ): T {
        if (annotation.maxBytes < 0) {
            throw MinecraftSerializationException(
                "ByteLengthPrefixed maxBytes must be non-negative",
            )
        }
        val size = reader.readVarInt(configuration.rejectNonMinimalVarNumbers)
        val maximum = minOf(
            annotation.maxBytes,
            configuration.maximumByteArraySize,
        )
        if (size !in 0..maximum) {
            throw MinecraftSerializationException(
                "Invalid length-prefixed value size $size; maximum is $maximum",
            )
        }
        val nested = MinecraftDecoder(
            MinecraftReader(reader.readBytes(size)),
            configuration,
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
        descriptor: SerialDescriptor,
    ): DecodedCollection {
        val hints = takePendingHints()
        val size = when {
            hints.any { it is RemainingBytes } -> reader.remaining
            hints.any { it is FixedLength } ->
                hints.filterIsInstance<FixedLength>().single().bytes

            hints.any { it is ChunkSectionCount } ->
                configuration.chunkSectionCount
                    ?: throw MinecraftSerializationException(
                        "Decoding chunk sections requires chunkSectionCount in MinecraftFormatConfiguration",
                    )

            hints.any { it is Unprefixed } ->
                throw MinecraftSerializationException(
                    "An unprefixed collection requires a containing custom serializer",
                )

            else -> reader.readVarInt(configuration.rejectNonMinimalVarNumbers)
        }
        val configuredMaximum =
            if (isByteArrayDescriptor(descriptor)) {
                configuration.maximumByteArraySize
            } else {
                configuration.maximumCollectionSize
            }
        if (size !in 0..configuredMaximum) {
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
        descriptor: SerialDescriptor,
        index: Int,
        block: () -> T,
    ): T = withHints(elementHints(descriptor, index), block)

    private fun elementHints(descriptor: SerialDescriptor, index: Int): List<Annotation> {
        val descriptorIndex = when (descriptor.kind) {
            StructureKind.LIST -> 0
            StructureKind.MAP -> index % descriptor.elementsCount
            else -> index
        }
        return descriptor.getElementAnnotations(descriptorIndex) +
                (frames.lastOrNull()?.elementHints ?: emptyList())
    }

    private fun takePendingHints(): List<Annotation> {
        val result = pendingHints
        pendingHints = emptyList()
        return result
    }

    private fun isNbtDescriptor(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName == NBT_SERIAL_NAME

    private fun isVector3dDescriptor(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName == VECTOR_3D_SERIAL_NAME

    private fun isPalettedContainerDescriptor(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName == PALETTED_CONTAINER_SERIAL_NAME

    private fun isByteArrayDescriptor(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName == BYTE_ARRAY_SERIAL_NAME

    private fun isUuidDescriptor(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName == UUID_SERIAL_NAME

    private data class Frame(
        val descriptor: SerialDescriptor,
        var nextIndex: Int = 0,
        var collectionSize: Int? = null,
        val elementHints: List<Annotation> = emptyList(),
    )

    private data class DecodedCollection(
        val size: Int,
        val elementHints: List<Annotation>,
    )

    private companion object {
        const val DEFAULT_STRING_MAXIMUM: Int = 32_767
        const val NBT_SERIAL_NAME: String =
            "com.hiczp.minecraft.protocol.model.type.NbtTag"
        const val NBT_COMPOUND_SERIAL_NAME: String = "compound"
        const val VECTOR_3D_SERIAL_NAME: String =
            "com.hiczp.minecraft.protocol.model.type.Vector3d"
        const val PALETTED_CONTAINER_SERIAL_NAME: String =
            "com.hiczp.minecraft.protocol.model.type.PalettedContainer"
        const val BYTE_ARRAY_SERIAL_NAME: String = "kotlin.ByteArray"
        const val UUID_SERIAL_NAME: String = "kotlin.uuid.Uuid"
    }
}
