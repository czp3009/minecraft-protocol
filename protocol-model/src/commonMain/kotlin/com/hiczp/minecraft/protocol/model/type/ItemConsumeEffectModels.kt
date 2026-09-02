@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class ItemUseAnimation {
    NONE,
    EAT,
    DRINK,
    BLOCK,
    BOW,
    TRIDENT,
    CROSSBOW,
    SPYGLASS,
    TOOT_HORN,
    BRUSH,
    BUNDLE,
    SPEAR,
}

@Serializable
data class MobEffectInstance(
    @VarInt
    val effectRegistryId: Int,
    val details: MobEffectDetails,
)

@Serializable(with = MobEffectDetailsSerializer::class)
data class MobEffectDetails(
    val amplifier: Int,
    val duration: Int,
    val ambient: Boolean,
    val showParticles: Boolean,
    val showIcon: Boolean,
    val hiddenEffect: MobEffectDetails? = null,
) {
    init {
        require(amplifier in 0..255) {
            "A mob-effect amplifier must be in 0..255"
        }
    }
}

@Serializable(with = ConsumeEffectSerializer::class)
sealed interface ConsumeEffect {
    @Serializable
    data class ApplyStatusEffects(
        val effects: List<MobEffectInstance>,
        val probability: Float,
    ) : ConsumeEffect

    @Serializable
    data class RemoveStatusEffects(
        val effects: RegistryHolderSet,
    ) : ConsumeEffect

    @Serializable
    data object ClearAllStatusEffects : ConsumeEffect

    @Serializable
    data class TeleportRandomly(
        val diameter: Float,
    ) : ConsumeEffect

    @Serializable
    data class PlaySound(
        val sound: SoundEventHolder,
    ) : ConsumeEffect
}

@Serializable
private data class MobEffectDetailsWire(
    @VarInt
    val amplifier: Int,
    @VarInt
    val duration: Int,
    val ambient: Boolean,
    val showParticles: Boolean,
    val showIcon: Boolean,
    val hiddenEffect: MobEffectDetailsWire? = null,
)

internal object MobEffectDetailsSerializer : KSerializer<MobEffectDetails> {
    override val descriptor: SerialDescriptor = MobEffectDetailsWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: MobEffectDetails) {
        MobEffectDetailsWire.serializer().serialize(
            encoder,
            value.toWire(),
        )
    }

    override fun deserialize(decoder: Decoder): MobEffectDetails =
        MobEffectDetailsWire.serializer().deserialize(decoder).toModel()

    private fun MobEffectDetails.toWire(): MobEffectDetailsWire =
        MobEffectDetailsWire(
            amplifier = amplifier,
            duration = duration,
            ambient = ambient,
            showParticles = showParticles,
            showIcon = showIcon,
            hiddenEffect = hiddenEffect?.toWire(),
        )

    private fun MobEffectDetailsWire.toModel(): MobEffectDetails =
        MobEffectDetails(
            amplifier = amplifier,
            duration = duration,
            ambient = ambient,
            showParticles = showParticles,
            showIcon = showIcon,
            hiddenEffect = hiddenEffect?.toModel(),
        )
}

internal object ConsumeEffectSerializer : KSerializer<ConsumeEffect> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ConsumeEffect",
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element(
            "value",
            buildClassSerialDescriptor("minecraft.ConsumeEffectValue"),
        )
    }

    override fun serialize(encoder: Encoder, value: ConsumeEffect) {
        val valueSerializer = serializerFor(value)
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, TYPE, typeId(value))
        @Suppress("UNCHECKED_CAST")
        output.encodeSerializableElement(
            descriptor,
            VALUE,
            valueSerializer as SerializationStrategy<ConsumeEffect>,
            value,
        )
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ConsumeEffect {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "ConsumeEffect requires ordered decoding",
            )
        }
        val typeId = input.decodeIntElement(descriptor, TYPE)

        @Suppress("UNCHECKED_CAST")
        val consumeEffect = input.decodeSerializableElement(
            descriptor,
            VALUE,
            serializerFor(typeId) as DeserializationStrategy<ConsumeEffect>,
        )
        input.endStructure(descriptor)
        return consumeEffect
    }

    private fun typeId(value: ConsumeEffect): Int = when (value) {
        is ConsumeEffect.ApplyStatusEffects -> APPLY_STATUS_EFFECTS
        is ConsumeEffect.RemoveStatusEffects -> REMOVE_STATUS_EFFECTS
        ConsumeEffect.ClearAllStatusEffects -> CLEAR_ALL_STATUS_EFFECTS
        is ConsumeEffect.TeleportRandomly -> TELEPORT_RANDOMLY
        is ConsumeEffect.PlaySound -> PLAY_SOUND
    }

    private fun serializerFor(
        value: ConsumeEffect,
    ): KSerializer<out ConsumeEffect> = when (value) {
        is ConsumeEffect.ApplyStatusEffects ->
            ConsumeEffect.ApplyStatusEffects.serializer()

        is ConsumeEffect.RemoveStatusEffects ->
            ConsumeEffect.RemoveStatusEffects.serializer()

        ConsumeEffect.ClearAllStatusEffects ->
            ConsumeEffect.ClearAllStatusEffects.serializer()

        is ConsumeEffect.TeleportRandomly ->
            ConsumeEffect.TeleportRandomly.serializer()

        is ConsumeEffect.PlaySound -> ConsumeEffect.PlaySound.serializer()
    }

    private fun serializerFor(
        typeId: Int,
    ): KSerializer<out ConsumeEffect> = when (typeId) {
        APPLY_STATUS_EFFECTS ->
            ConsumeEffect.ApplyStatusEffects.serializer()

        REMOVE_STATUS_EFFECTS ->
            ConsumeEffect.RemoveStatusEffects.serializer()

        CLEAR_ALL_STATUS_EFFECTS ->
            ConsumeEffect.ClearAllStatusEffects.serializer()

        TELEPORT_RANDOMLY -> ConsumeEffect.TeleportRandomly.serializer()
        PLAY_SOUND -> ConsumeEffect.PlaySound.serializer()
        else -> throw SerializationException(
            "Unknown consume-effect type ID $typeId",
        )
    }

    private const val TYPE: Int = 0
    private const val VALUE: Int = 1
    private const val APPLY_STATUS_EFFECTS: Int = 0
    private const val REMOVE_STATUS_EFFECTS: Int = 1
    private const val CLEAR_ALL_STATUS_EFFECTS: Int = 2
    private const val TELEPORT_RANDOMLY: Int = 3
    private const val PLAY_SOUND: Int = 4
}
