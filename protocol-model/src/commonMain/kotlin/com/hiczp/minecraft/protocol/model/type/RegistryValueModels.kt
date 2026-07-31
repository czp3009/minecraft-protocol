@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The registry-independent wire shape used by vanilla's HolderSet codec.
 *
 * Registry IDs are interpreted by the field that contains this value.
 */
@Serializable(with = RegistryHolderSetSerializer::class)
sealed interface RegistryHolderSet {
    data class Named(
        val tag: Identifier,
    ) : RegistryHolderSet

    data class Direct(
        val registryIds: List<Int>,
    ) : RegistryHolderSet {
        init {
            require(registryIds.all { it >= 0 }) {
                "Registry IDs must be non-negative"
            }
        }
    }
}

/**
 * A vanilla Holder<SoundEvent>: either a registry reference or an inline sound.
 */
@Serializable(with = SoundEventHolderSerializer::class)
sealed interface SoundEventHolder {
    data class Reference(
        val registryId: Int,
    ) : SoundEventHolder {
        init {
            require(registryId >= 0) { "A registry ID must be non-negative" }
        }
    }

    @Serializable
    data class Direct(
        val sound: Identifier,
        val fixedRange: Float? = null,
    ) : SoundEventHolder
}

internal object RegistryHolderSetSerializer :
    KSerializer<RegistryHolderSet> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.RegistryHolderSet",
    ) {
        element<Int>("countOrNamed", annotations = listOf(VarInt()))
        element(
            "tag",
            Identifier.serializer().descriptor,
            isOptional = true,
        )
        element<Int>(
            "registryId",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: RegistryHolderSet) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is RegistryHolderSet.Named -> {
                output.encodeIntElement(descriptor, COUNT_OR_NAMED, 0)
                output.encodeSerializableElement(
                    descriptor,
                    TAG,
                    Identifier.serializer(),
                    value.tag,
                )
            }

            is RegistryHolderSet.Direct -> {
                if (value.registryIds.size == Int.MAX_VALUE) {
                    throw SerializationException(
                        "Registry holder set is too large",
                    )
                }
                output.encodeIntElement(
                    descriptor,
                    COUNT_OR_NAMED,
                    value.registryIds.size + 1,
                )
                value.registryIds.forEach { registryId ->
                    output.encodeIntElement(
                        descriptor,
                        REGISTRY_ID,
                        registryId,
                    )
                }
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): RegistryHolderSet {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "RegistryHolderSet requires ordered decoding",
            )
        }
        val countOrNamed = input.decodeIntElement(
            descriptor,
            COUNT_OR_NAMED,
        )
        val result = when {
            countOrNamed == 0 -> RegistryHolderSet.Named(
                input.decodeSerializableElement(
                    descriptor,
                    TAG,
                    Identifier.serializer(),
                ),
            )

            countOrNamed > 0 -> RegistryHolderSet.Direct(
                List(countOrNamed - 1) {
                    val registryId = input.decodeIntElement(
                        descriptor,
                        REGISTRY_ID,
                    )
                    if (registryId < 0) {
                        throw SerializationException(
                            "Negative registry ID $registryId",
                        )
                    }
                    registryId
                },
            )

            else -> throw SerializationException(
                "Invalid holder-set count prefix $countOrNamed",
            )
        }
        input.endStructure(descriptor)
        return result
    }

    private const val COUNT_OR_NAMED: Int = 0
    private const val TAG: Int = 1
    private const val REGISTRY_ID: Int = 2
}

internal object SoundEventHolderSerializer : KSerializer<SoundEventHolder> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.SoundEventHolder",
    ) {
        element<Int>("holderId", annotations = listOf(VarInt()))
        element(
            "direct",
            SoundEventHolder.Direct.serializer().descriptor,
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: SoundEventHolder) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is SoundEventHolder.Reference -> {
                if (value.registryId == Int.MAX_VALUE) {
                    throw SerializationException(
                        "Sound-event registry ID overflows its holder ID",
                    )
                }
                output.encodeIntElement(
                    descriptor,
                    HOLDER_ID,
                    value.registryId + 1,
                )
            }

            is SoundEventHolder.Direct -> {
                output.encodeIntElement(descriptor, HOLDER_ID, 0)
                output.encodeSerializableElement(
                    descriptor,
                    DIRECT,
                    SoundEventHolder.Direct.serializer(),
                    value,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): SoundEventHolder {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "SoundEventHolder requires ordered decoding",
            )
        }
        val holderId = input.decodeIntElement(descriptor, HOLDER_ID)
        val result = when {
            holderId == 0 -> input.decodeSerializableElement(
                descriptor,
                DIRECT,
                SoundEventHolder.Direct.serializer(),
            )

            holderId > 0 -> SoundEventHolder.Reference(holderId - 1)
            else -> throw SerializationException(
                "Invalid sound holder ID $holderId",
            )
        }
        input.endStructure(descriptor)
        return result
    }

    private const val HOLDER_ID: Int = 0
    private const val DIRECT: Int = 1
}
