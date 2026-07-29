@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.NbtString
import com.hiczp.minecraft.protocol.model.type.NbtTag
import com.hiczp.minecraft.protocol.model.wire.FixedLength
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * Constructs one deliberately small, protocol-valid value from a serializer.
 *
 * This is test infrastructure for exercising every registered packet codec. It
 * does not attempt to manufacture semantically useful game data.
 */
internal fun <T> KSerializer<T>.minimalProtocolValue(): T =
    protocolValue(ProtocolSampleProfile.MINIMAL)

internal fun <T> KSerializer<T>.protocolValue(
    profile: ProtocolSampleProfile,
): T = deserialize(MinimalProtocolValueDecoder(profile = profile))

internal enum class ProtocolSampleProfile {
    MINIMAL,
    NON_NULL,
    NON_EMPTY_COLLECTIONS,
    TRUE_BOOLEANS,
    LAST_ENUM,
}

private class MinimalProtocolValueDecoder(
    private val profile: ProtocolSampleProfile,
    private val annotations: List<Annotation> = emptyList(),
    private val structureSerialName: String? = null,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = minimalValueSerializersModule

    override fun beginStructure(
        descriptor: SerialDescriptor,
    ): CompositeDecoder = MinimalProtocolValueDecoder(
        profile,
        annotations,
        descriptor.serialName,
    )

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        CompositeDecoder.DECODE_DONE

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
        annotations.filterIsInstance<FixedLength>().singleOrNull()?.bytes
            ?: if (profile == ProtocolSampleProfile.NON_EMPTY_COLLECTIONS) 1 else 0

    override fun decodeBoolean(): Boolean =
        profile == ProtocolSampleProfile.TRUE_BOOLEANS

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

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int =
        when {
            profile == ProtocolSampleProfile.LAST_ENUM ->
                enumDescriptor.elementsCount - 1

            enumDescriptor.serialName.endsWith(".HandshakeNextState") -> 1
            enumDescriptor.serialName.endsWith(".DebugSubscriptionType") -> 1
            else -> 0
        }

    override fun decodeNotNullMark(): Boolean =
        profile == ProtocolSampleProfile.NON_NULL

    override fun decodeInline(descriptor: SerialDescriptor) =
        MinimalProtocolValueDecoder(profile, annotations)

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        return deserializer.deserialize(
            MinimalProtocolValueDecoder(
                profile,
                descriptor.getElementAnnotations(index),
            ),
        )
    }
}

private val minimalValueSerializersModule: SerializersModule = SerializersModule {
    polymorphic(NbtTag::class) {
        defaultDeserializer { NbtString.serializer() }
    }
}
