@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class ItemAttributeModifier(
    @VarInt
    val attributeRegistryId: Int,
    val id: Identifier,
    val amount: Double,
    @ZeroFallbackEnum
    val operation: AttributeModifierOperation,
    @ZeroFallbackEnum
    val slot: EquipmentSlotGroup,
    val display: AttributeModifierDisplay = AttributeModifierDisplay.Default,
)

@Serializable
enum class EquipmentSlotGroup {
    ANY,
    MAINHAND,
    OFFHAND,
    HAND,
    FEET,
    LEGS,
    CHEST,
    HEAD,
    ARMOR,
    BODY,
    SADDLE,
}

@Serializable(with = AttributeModifierDisplaySerializer::class)
sealed interface AttributeModifierDisplay {
    data object Default : AttributeModifierDisplay

    data object Hidden : AttributeModifierDisplay

    @Serializable
    data class Override(
        val text: TextComponent,
    ) : AttributeModifierDisplay
}

internal object AttributeModifierDisplaySerializer :
    KSerializer<AttributeModifierDisplay> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.AttributeModifierDisplay",
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element(
            "override",
            AttributeModifierDisplay.Override.serializer().descriptor,
            isOptional = true,
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: AttributeModifierDisplay,
    ) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            AttributeModifierDisplay.Default ->
                output.encodeIntElement(descriptor, TYPE, DEFAULT)

            AttributeModifierDisplay.Hidden ->
                output.encodeIntElement(descriptor, TYPE, HIDDEN)

            is AttributeModifierDisplay.Override -> {
                output.encodeIntElement(descriptor, TYPE, OVERRIDE)
                output.encodeSerializableElement(
                    descriptor,
                    VALUE,
                    AttributeModifierDisplay.Override.serializer(),
                    value,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): AttributeModifierDisplay {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "AttributeModifierDisplay requires ordered decoding",
            )
        }
        val attributeModifierDisplay = when (
            input.decodeIntElement(descriptor, TYPE)
        ) {
            HIDDEN -> AttributeModifierDisplay.Hidden
            OVERRIDE -> input.decodeSerializableElement(
                descriptor,
                VALUE,
                AttributeModifierDisplay.Override.serializer(),
            )

            else -> AttributeModifierDisplay.Default
        }
        input.endStructure(descriptor)
        return attributeModifierDisplay
    }

    private const val TYPE: Int = 0
    private const val VALUE: Int = 1
    private const val DEFAULT: Int = 0
    private const val HIDDEN: Int = 1
    private const val OVERRIDE: Int = 2
}
