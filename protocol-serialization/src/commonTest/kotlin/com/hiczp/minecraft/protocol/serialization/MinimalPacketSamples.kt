@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.protocol.model.type.JsonTextComponent
import com.hiczp.minecraft.protocol.model.wire.FixedLength
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

/** Constructs a protocol-valid test value for the selected sample [protocolSampleProfile]. */
internal fun <T> KSerializer<T>.protocolValue(
    protocolSampleProfile: ProtocolSampleProfile,
): T = deserialize(MinimalProtocolValueDecoder(protocolSampleProfile = protocolSampleProfile))

internal enum class ProtocolSampleProfile {
    MINIMAL,
    NON_NULL,
    NON_EMPTY_COLLECTIONS,
    TRUE_BOOLEANS,
    LAST_ENUM,
}

private class MinimalProtocolValueDecoder(
    private val protocolSampleProfile: ProtocolSampleProfile,
    private val annotations: List<Annotation> = emptyList(),
    private val structureSerialName: String? = null,
) : AbstractDecoder(), NbtTagDecoder {
    override val serializersModule: SerializersModule = minimalValueSerializersModule

    override fun beginStructure(
        descriptor: SerialDescriptor,
    ): CompositeDecoder = MinimalProtocolValueDecoder(
        protocolSampleProfile,
        annotations,
        descriptor.serialName,
    )

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        CompositeDecoder.DECODE_DONE

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
        annotations.filterIsInstance<FixedLength>().singleOrNull()?.bytes
            ?: if (protocolSampleProfile == ProtocolSampleProfile.NON_EMPTY_COLLECTIONS) 1 else 0

    override fun decodeBoolean(): Boolean =
        protocolSampleProfile == ProtocolSampleProfile.TRUE_BOOLEANS

    override fun decodeByte(): Byte = 1

    override fun decodeShort(): Short = 1

    override fun decodeInt(): Int = when (structureSerialName) {
        "minecraft.EntityMetadata" -> 255
        "minecraft.ItemStack",
        "minecraft.UntrustedItemStack",
            -> 0

        "minecraft.RecipeDisplay" -> 0
        else -> 1
    }

    override fun decodeLong(): Long = 1L

    override fun decodeFloat(): Float = 1.0f

    override fun decodeDouble(): Double = 1.0

    override fun decodeChar(): Char = 'a'

    override fun decodeString(): String = "minecraft:test"

    override fun decodeNbtTag(): NbtTag = NbtString("minecraft:test")

    override fun <T> decodeSerializableValue(
        deserializer: DeserializationStrategy<T>,
    ): T {
        if (deserializer is NbtTagSerializer<*>) {
            for (candidate in minimalNbtTags) {
                val value = runCatching {
                    deserializer.deserializeTag(candidate)
                }.getOrNull() ?: continue
                @Suppress("UNCHECKED_CAST")
                return value as T
            }
            error("No minimal value for ${deserializer.descriptor.serialName}")
        }
        if (deserializer.descriptor.serialName == UUID_SERIAL_NAME) {
            @Suppress("UNCHECKED_CAST")
            return Uuid.NIL as T
        }
        if (deserializer.descriptor.serialName == JSON_TEXT_COMPONENT_SERIAL_NAME) {
            @Suppress("UNCHECKED_CAST")
            return JsonTextComponent("\"\"") as T
        }
        return super.decodeSerializableValue(deserializer)
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int =
        when {
            protocolSampleProfile == ProtocolSampleProfile.LAST_ENUM ->
                enumDescriptor.elementsCount - 1

            enumDescriptor.serialName.endsWith(".HandshakeNextState") -> 1
            enumDescriptor.serialName.endsWith(".DebugSubscriptionType") -> 1
            else -> 0
        }

    override fun decodeNotNullMark(): Boolean =
        protocolSampleProfile == ProtocolSampleProfile.NON_NULL

    override fun decodeInline(descriptor: SerialDescriptor) =
        MinimalProtocolValueDecoder(protocolSampleProfile, annotations)

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        return MinimalProtocolValueDecoder(
            protocolSampleProfile,
            descriptor.getElementAnnotations(index),
        ).decodeSerializableValue(
            deserializer,
        )
    }
}

private const val UUID_SERIAL_NAME: String = "kotlin.uuid.Uuid"
private const val JSON_TEXT_COMPONENT_SERIAL_NAME: String = "minecraft.JsonTextComponent"

private val minimalNbtTags: List<NbtTag> = listOf(
    NbtString("minecraft:test"),
    NbtCompound(emptyMap()),
    NbtList(emptyList()),
    NbtByte(1),
    NbtShort(1),
    NbtInt(1),
    NbtLong(1),
    NbtFloat(1.0f),
    NbtDouble(1.0),
    NbtByteArray(byteArrayOf(1)),
    NbtIntArray(intArrayOf(1)),
    NbtLongArray(longArrayOf(1)),
    NbtEnd,
)

private val minimalValueSerializersModule: SerializersModule = EmptySerializersModule()
