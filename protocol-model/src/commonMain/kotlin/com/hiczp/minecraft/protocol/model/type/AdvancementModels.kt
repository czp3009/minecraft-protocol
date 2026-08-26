package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class AdvancementType {
    TASK,
    CHALLENGE,
    GOAL,
}

@Serializable(with = AdvancementDisplayInfoSerializer::class)
data class AdvancementDisplayInfo(
    val title: TextComponent,
    val description: TextComponent,
    val icon: ItemStackTemplate,
    val type: AdvancementType,
    val background: Identifier? = null,
    val showToast: Boolean = false,
    val hidden: Boolean = false,
    val x: Float,
    val y: Float,
)

@Serializable
data class Advancement(
    val parent: Identifier?,
    val display: AdvancementDisplayInfo?,
    val requirements: List<List<String>>,
    val sendsTelemetryEvent: Boolean,
)

@Serializable
data class AdvancementHolder(
    val id: Identifier,
    val advancement: Advancement,
)

@Serializable
data class AdvancementProgress(
    val criteria: Map<String, Long?>,
)

internal object AdvancementDisplayInfoSerializer :
    KSerializer<AdvancementDisplayInfo> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.AdvancementDisplayInfo",
    ) {
        element<TextComponent>("title")
        element<TextComponent>("description")
        element<ItemStackTemplate>("icon")
        element<AdvancementType>("type")
        element<Int>("flags")
        element<Identifier>("background", isOptional = true)
        element<Float>("x")
        element<Float>("y")
    }

    override fun serialize(encoder: Encoder, value: AdvancementDisplayInfo) {
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(
            descriptor,
            TITLE,
            TextComponent.serializer(),
            value.title,
        )
        output.encodeSerializableElement(
            descriptor,
            DESCRIPTION,
            TextComponent.serializer(),
            value.description,
        )
        output.encodeSerializableElement(
            descriptor,
            ICON,
            ItemStackTemplate.serializer(),
            value.icon,
        )
        output.encodeSerializableElement(
            descriptor,
            TYPE,
            AdvancementType.serializer(),
            value.type,
        )
        var flags = 0
        if (value.background != null) flags = flags or HAS_BACKGROUND
        if (value.showToast) flags = flags or SHOW_TOAST
        if (value.hidden) flags = flags or HIDDEN
        output.encodeIntElement(descriptor, FLAGS, flags)
        value.background?.let {
            output.encodeSerializableElement(
                descriptor,
                BACKGROUND,
                Identifier.serializer(),
                it,
            )
        }
        output.encodeFloatElement(descriptor, X, value.x)
        output.encodeFloatElement(descriptor, Y, value.y)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): AdvancementDisplayInfo {
        val input = decoder.beginStructure(descriptor)
        val title = input.decodeSerializableElement(
            descriptor,
            TITLE,
            TextComponent.serializer(),
        )
        val description = input.decodeSerializableElement(
            descriptor,
            DESCRIPTION,
            TextComponent.serializer(),
        )
        val icon = input.decodeSerializableElement(
            descriptor,
            ICON,
            ItemStackTemplate.serializer(),
        )
        val advancementType = input.decodeSerializableElement(
            descriptor,
            TYPE,
            AdvancementType.serializer(),
        )
        val flags = input.decodeIntElement(descriptor, FLAGS)
        val background = if (flags and HAS_BACKGROUND != 0) {
            input.decodeSerializableElement(
                descriptor,
                BACKGROUND,
                Identifier.serializer(),
            )
        } else {
            null
        }
        val x = input.decodeFloatElement(descriptor, X)
        val y = input.decodeFloatElement(descriptor, Y)
        input.endStructure(descriptor)
        return AdvancementDisplayInfo(
            title,
            description,
            icon,
            advancementType,
            background,
            flags and SHOW_TOAST != 0,
            flags and HIDDEN != 0,
            x,
            y,
        )
    }

    private const val TITLE: Int = 0
    private const val DESCRIPTION: Int = 1
    private const val ICON: Int = 2
    private const val TYPE: Int = 3
    private const val FLAGS: Int = 4
    private const val BACKGROUND: Int = 5
    private const val X: Int = 6
    private const val Y: Int = 7

    private const val HAS_BACKGROUND: Int = 0x01
    private const val SHOW_TOAST: Int = 0x02
    private const val HIDDEN: Int = 0x04
}
