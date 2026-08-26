@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.VarLong
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
import kotlin.jvm.JvmInline

@Serializable
enum class DisplaySlot {
    LIST,
    SIDEBAR,
    BELOW_NAME,
    TEAM_BLACK,
    TEAM_DARK_BLUE,
    TEAM_DARK_GREEN,
    TEAM_DARK_AQUA,
    TEAM_DARK_RED,
    TEAM_DARK_PURPLE,
    TEAM_GOLD,
    TEAM_GRAY,
    TEAM_DARK_GRAY,
    TEAM_BLUE,
    TEAM_GREEN,
    TEAM_AQUA,
    TEAM_RED,
    TEAM_LIGHT_PURPLE,
    TEAM_YELLOW,
    TEAM_WHITE,
}

@Serializable
data class ClockNetworkState(
    @VarLong
    val totalTicks: Long,
    val partialTick: Float,
    val rate: Float,
)

@Serializable
enum class AttributeModifierOperation {
    ADD_VALUE,
    ADD_MULTIPLIED_BASE,
    ADD_MULTIPLIED_TOTAL,
}

@Serializable
data class AttributeModifier(
    val id: Identifier,
    val amount: Double,
    @ZeroFallbackEnum
    val operation: AttributeModifierOperation,
)

@Serializable
data class AttributeSnapshot(
    @VarInt
    val attributeTypeId: Int,
    val baseValue: Double,
    val modifiers: List<AttributeModifier>,
)

@Serializable
@JvmInline
value class MobEffectFlags(val bits: Byte) {
    val ambient: Boolean
        get() = bits.toInt() and 0x01 != 0
    val visible: Boolean
        get() = bits.toInt() and 0x02 != 0
    val showIcon: Boolean
        get() = bits.toInt() and 0x04 != 0
    val blend: Boolean
        get() = bits.toInt() and 0x08 != 0
}

@Serializable
enum class SoundSource {
    MASTER,
    MUSIC,
    RECORDS,
    WEATHER,
    BLOCKS,
    HOSTILE,
    NEUTRAL,
    PLAYERS,
    AMBIENT,
    VOICE,
    UI,
}

@Serializable(with = StopSoundSerializer::class)
data class StopSound(
    val source: SoundSource?,
    val sound: Identifier?,
)

internal object StopSoundSerializer : KSerializer<StopSound> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.StopSound",
    ) {
        element<Byte>("flags")
        element<SoundSource>("source", isOptional = true)
        element<Identifier>("sound", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: StopSound) {
        val flags =
            (if (value.source != null) HAS_SOURCE else 0) or
                    (if (value.sound != null) HAS_SOUND else 0)
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, FLAGS, flags.toByte())
        value.source?.let {
            output.encodeSerializableElement(
                descriptor,
                SOURCE,
                SoundSource.serializer(),
                it,
            )
        }
        value.sound?.let {
            output.encodeSerializableElement(
                descriptor,
                SOUND,
                Identifier.serializer(),
                it,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): StopSound {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val flags = input.decodeByteElement(descriptor, FLAGS).toInt()
            val soundSource = if (flags and HAS_SOURCE != 0) {
                input.decodeSerializableElement(
                    descriptor,
                    SOURCE,
                    SoundSource.serializer(),
                )
            } else {
                null
            }
            val sound = if (flags and HAS_SOUND != 0) {
                input.decodeSerializableElement(
                    descriptor,
                    SOUND,
                    Identifier.serializer(),
                )
            } else {
                null
            }
            input.endStructure(descriptor)
            return StopSound(soundSource, sound)
        }

        var flags: Int? = null
        var soundSource: SoundSource? = null
        var sound: Identifier? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                FLAGS -> flags =
                    input.decodeByteElement(descriptor, FLAGS).toInt()

                SOURCE -> soundSource = input.decodeSerializableElement(
                    descriptor,
                    SOURCE,
                    SoundSource.serializer(),
                )

                SOUND -> sound = input.decodeSerializableElement(
                    descriptor,
                    SOUND,
                    Identifier.serializer(),
                )

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected StopSound field $index",
                )
            }
        }
        input.endStructure(descriptor)
        val actualFlags = flags ?: throw SerializationException(
            "Missing StopSound flags",
        )
        if (actualFlags and HAS_SOURCE != 0 && soundSource == null) {
            throw SerializationException("Missing StopSound source")
        }
        if (actualFlags and HAS_SOUND != 0 && sound == null) {
            throw SerializationException("Missing StopSound identifier")
        }
        return StopSound(
            source = soundSource.takeIf { actualFlags and HAS_SOURCE != 0 },
            sound = sound.takeIf { actualFlags and HAS_SOUND != 0 },
        )
    }

    private const val FLAGS: Int = 0
    private const val SOURCE: Int = 1
    private const val SOUND: Int = 2
    private const val HAS_SOURCE: Int = 0x01
    private const val HAS_SOUND: Int = 0x02
}

@Serializable
data class Vector3i(
    @VarInt
    val x: Int,
    @VarInt
    val y: Int,
    @VarInt
    val z: Int,
)
