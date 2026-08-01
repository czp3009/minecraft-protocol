@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.model.type.NbtEnd
import com.hiczp.minecraft.protocol.model.type.NbtTag
import com.hiczp.minecraft.protocol.model.type.PalettedContainer
import com.hiczp.minecraft.protocol.model.type.Vector3d
import com.hiczp.minecraft.protocol.model.wire.*
import com.hiczp.minecraft.protocol.serialization.MinecraftFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

internal class MinecraftEncoder(
    private val writer: MinecraftWriter,
    private val configuration: MinecraftFormatConfiguration,
    override val serializersModule: SerializersModule,
) : AbstractEncoder() {
    private val nbtCodec: NbtBinaryCodec = NbtBinaryCodec(configuration)
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
        pendingHints =
            descriptor.getElementAnnotations(descriptorIndex) +
                    (frames.lastOrNull()?.elementHints ?: emptyList())
        return true
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        val configuredMaximum =
            if (isByteArrayDescriptor(descriptor)) {
                configuration.maximumByteArraySize
            } else {
                configuration.maximumCollectionSize
            }
        if (collectionSize !in 0..configuredMaximum) {
            throw MinecraftSerializationException("Invalid collection size: $collectionSize")
        }
        val hints = takePendingHints()
        validateCollectionHints(collectionSize, hints)
        val fixed = hints.filterIsInstance<FixedLength>().singleOrNull()
        if (fixed != null && collectionSize != fixed.bytes) {
            throw MinecraftSerializationException(
                "Expected exactly ${fixed.bytes} elements, got $collectionSize",
            )
        }
        if (hints.any { it is ChunkSectionCount }) {
            if (descriptor.kind != StructureKind.LIST) {
                throw MinecraftSerializationException(
                    "@ChunkSectionCount can only be used with a List",
                )
            }
            val expected = configuration.chunkSectionCount
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
            writer.writeVarInt(collectionSize)
        }
        frames += Frame(
            descriptor,
            elementHints = hints.filter {
                it is VarIntElements ||
                        it is VarLongElements ||
                        it is MaxLength
            },
        )
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val frame = frames.removeLastOrNull()
            ?: throw MinecraftSerializationException("Encoder structure stack underflow")
        if (frame.descriptor != descriptor) {
            throw MinecraftSerializationException("Mismatched encoder structure")
        }
    }

    override fun encodeBoolean(value: Boolean): Unit = writer.writeByte(if (value) 1 else 0)

    override fun encodeByte(value: Byte): Unit = writer.writeByte(value.toInt())

    override fun encodeShort(value: Short): Unit = writer.writeShort(value.toInt())

    override fun encodeInt(value: Int) {
        val hints = takePendingHints()
        when {
            hints.any { it is VarInt || it is VarIntElements } -> writer.writeVarInt(value)
            hints.any { it is UnsignedByte } -> {
                require(value in 0..255) { "Unsigned byte is outside 0..255: $value" }
                writer.writeByte(value)
            }

            hints.any { it is UnsignedShort } -> {
                require(value in 0..65_535) { "Unsigned short is outside 0..65535: $value" }
                writer.writeShort(value)
            }

            else -> writer.writeInt(value)
        }
    }

    override fun encodeLong(value: Long) {
        if (takePendingHints().any { it is VarLong || it is VarLongElements }) {
            writer.writeVarLong(value)
        } else {
            writer.writeLong(value)
        }
    }

    override fun encodeFloat(value: Float): Unit = writer.writeInt(value.toBits())

    override fun encodeDouble(value: Double): Unit = writer.writeLong(value.toBits())

    override fun encodeChar(value: Char): Unit = writer.writeShort(value.code)

    override fun encodeString(value: String) {
        val maximum = takePendingHints().filterIsInstance<MaxLength>()
            .singleOrNull()?.characters ?: DEFAULT_STRING_MAXIMUM
        val bytes = value.encodeToByteArray()
        if (value.length > maximum || bytes.size > maximum * 3L) {
            throw MinecraftSerializationException(
                "String exceeds its protocol limit of $maximum UTF-16 code units",
            )
        }
        writer.writeVarInt(bytes.size)
        writer.write(bytes)
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        when (
            takePendingHints().filterIsInstance<EnumEncoding>()
                .singleOrNull()?.kind ?: EnumEncodingKind.VAR_INT
        ) {
            EnumEncodingKind.BYTE,
            EnumEncodingKind.UNSIGNED_BYTE,
                -> writer.writeByte(index)

            EnumEncodingKind.VAR_INT -> writer.writeVarInt(index)
            EnumEncodingKind.INT -> writer.writeInt(index)
        }
    }

    override fun encodeNull(): Unit = encodeBoolean(false)

    override fun encodeNotNullMark(): Unit = encodeBoolean(true)

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

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
            value is NbtTag -> nbtCodec.writeUnnamed(writer, value)
            value is Uuid -> writer.write(value.toByteArray())
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
                LowPrecisionVectorCodec.write(writer, value)
            }

            pendingHints.any { it is Paletted } -> {
                if (value !is PalettedContainer) {
                    throw MinecraftSerializationException(
                        "@Paletted can only be used with PalettedContainer",
                    )
                }
                val annotation = pendingHints.filterIsInstance<Paletted>().single()
                PalettedContainerCodec.write(
                    writer,
                    value,
                    annotation.kind,
                    configuration,
                )
            }

            else -> serializer.serialize(this, value)
        }
    }

    private fun <T> encodeSerializableWithHints(
        serializer: SerializationStrategy<T>,
        value: T,
        hints: List<Annotation>,
    ) {
        val lengthPrefix = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (lengthPrefix != null) {
            writeLengthPrefixed(lengthPrefix) { nested ->
                nested.encodeSerializableWithHints(
                    serializer,
                    value,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
            return
        }
        withHints(hints) {
            encodeSerializableValue(serializer, value)
        }
    }

    private fun <T : Any> encodeNullableWithHints(
        serializer: SerializationStrategy<T>,
        value: T?,
        hints: List<Annotation>,
    ) {
        val lengthPrefix = hints.filterIsInstance<ByteLengthPrefixed>().singleOrNull()
        if (lengthPrefix != null) {
            writeLengthPrefixed(lengthPrefix) { nested ->
                nested.encodeNullableWithHints(
                    serializer,
                    value,
                    hints.filterNot { it is ByteLengthPrefixed },
                )
            }
            return
        }
        withHints(hints) {
            val nullSentinel = hints.filterIsInstance<NullSentinelByte>().singleOrNull()
            if (nullSentinel != null) {
                validateNullSentinelByte(nullSentinel)
                if (value == null) {
                    writer.writeByte(nullSentinel.value)
                } else {
                    encodeSerializableValue(serializer, value)
                }
            } else if (hints.any { it is OptionalVarInt }) {
                val id = value as? Int
                if (id != null && id < 0) {
                    throw MinecraftSerializationException(
                        "@OptionalVarInt value must be non-negative: $id",
                    )
                }
                writer.writeVarInt(id?.plus(1) ?: 0)
            } else if (hints.any { it is NbtEndOptional }) {
                if (
                    !isNbtDescriptor(serializer.descriptor) &&
                    hints.none { it is NetworkNbt }
                ) {
                    throw MinecraftSerializationException(
                        "@NbtEndOptional can only be used with NbtTag",
                    )
                }
                if (value == null) {
                    nbtCodec.writeUnnamed(writer, NbtEnd)
                } else {
                    encodeSerializableValue(serializer, value)
                }
            } else if (value == null) {
                encodeNull()
            } else {
                encodeNotNullMark()
                encodeSerializableValue(serializer, value)
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

    private inline fun writeLengthPrefixed(
        annotation: ByteLengthPrefixed,
        encode: (MinecraftEncoder) -> Unit,
    ) {
        require(annotation.maxBytes >= 0) {
            "ByteLengthPrefixed maxBytes must be non-negative"
        }
        val nestedWriter = MinecraftWriter()
        val nested = MinecraftEncoder(
            nestedWriter,
            configuration,
            serializersModule,
        )
        encode(nested)
        val bytes = nestedWriter.readByteArray()
        val maximum = minOf(
            annotation.maxBytes,
            configuration.maximumByteArraySize,
        )
        if (bytes.size > maximum) {
            throw MinecraftSerializationException(
                "Length-prefixed value has ${bytes.size} bytes; maximum is $maximum",
            )
        }
        writer.writeVarInt(bytes.size)
        writer.write(bytes)
    }

    private fun elementHints(
        descriptor: SerialDescriptor,
        index: Int,
    ): List<Annotation> {
        val descriptorIndex = when (descriptor.kind) {
            StructureKind.LIST -> 0
            StructureKind.MAP -> index % descriptor.elementsCount
            else -> index
        }
        return descriptor.getElementAnnotations(descriptorIndex) +
                (frames.lastOrNull()?.elementHints ?: emptyList())
    }

    private fun isNbtDescriptor(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName == NBT_SERIAL_NAME

    private fun isByteArrayDescriptor(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName == BYTE_ARRAY_SERIAL_NAME

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
        const val DEFAULT_STRING_MAXIMUM: Int = 32_767
        const val NBT_SERIAL_NAME: String =
            "com.hiczp.minecraft.protocol.model.type.NbtTag"
        const val BYTE_ARRAY_SERIAL_NAME: String = "kotlin.ByteArray"
    }

    private data class Frame(
        val descriptor: SerialDescriptor,
        val elementHints: List<Annotation> = emptyList(),
    )
}
