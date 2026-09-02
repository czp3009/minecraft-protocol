@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.MaxByteLength
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.RemainingBytes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Payloads with a vanilla-defined channel shape, plus lossless unknown data. */
@Serializable
sealed interface CustomPayload {
    @Serializable
    data class Brand(
        @MaxLength(32_767)
        val brand: String,
    ) : CustomPayload

    @Serializable
    data class Unknown(
        val channel: Identifier,
        val data: ByteString,
    ) : CustomPayload
}

internal object ClientboundCustomPayloadSerializer :
    CustomPayloadSerializer(maximumUnknownPayloadSize = 1_048_576)

internal object ServerboundCustomPayloadSerializer :
    CustomPayloadSerializer(maximumUnknownPayloadSize = 32_767)

internal abstract class CustomPayloadSerializer(
    private val maximumUnknownPayloadSize: Int,
) : KSerializer<CustomPayload> {
    final override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.CustomPayload",
    ) {
        element<Identifier>("channel")
        element<String>(
            "brand",
            annotations = listOf(MaxLength(32_767)),
            isOptional = true,
        )
        element<ByteString>(
            "data",
            annotations = listOf(
                RemainingBytes(),
                MaxByteLength(maximumUnknownPayloadSize),
            ),
            isOptional = true,
        )
    }

    final override fun serialize(encoder: Encoder, value: CustomPayload) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is CustomPayload.Brand -> {
                output.encodeSerializableElement(
                    descriptor,
                    CHANNEL,
                    Identifier.serializer(),
                    BRAND_CHANNEL,
                )
                output.encodeStringElement(descriptor, BRAND, value.brand)
            }

            is CustomPayload.Unknown -> {
                output.encodeSerializableElement(
                    descriptor,
                    CHANNEL,
                    Identifier.serializer(),
                    value.channel,
                )
                output.encodeSerializableElement(
                    descriptor,
                    DATA,
                    ByteString.serializer(),
                    value.data,
                )
            }
        }
        output.endStructure(descriptor)
    }

    final override fun deserialize(decoder: Decoder): CustomPayload {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val channel = input.decodeSerializableElement(
                descriptor,
                CHANNEL,
                Identifier.serializer(),
            )
            val customPayload = if (channel == BRAND_CHANNEL) {
                CustomPayload.Brand(input.decodeStringElement(descriptor, BRAND))
            } else {
                CustomPayload.Unknown(
                    channel,
                    input.decodeSerializableElement(
                        descriptor,
                        DATA,
                        ByteString.serializer(),
                    ),
                )
            }
            input.endStructure(descriptor)
            return customPayload
        }

        var channel: Identifier? = null
        var brand: String? = null
        var data: ByteString? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                CHANNEL -> channel = input.decodeSerializableElement(
                    descriptor,
                    CHANNEL,
                    Identifier.serializer(),
                )

                BRAND -> brand = input.decodeStringElement(descriptor, BRAND)
                DATA -> data = input.decodeSerializableElement(
                    descriptor,
                    DATA,
                    ByteString.serializer(),
                )

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected CustomPayload field $index",
                )
            }
        }
        input.endStructure(descriptor)
        return when (
            val actualChannel = channel
                ?: throw SerializationException("Missing custom payload channel")
        ) {
            BRAND_CHANNEL -> CustomPayload.Brand(
                brand ?: throw SerializationException("Missing brand payload"),
            )

            else -> CustomPayload.Unknown(
                actualChannel,
                data ?: throw SerializationException("Missing custom payload data"),
            )
        }
    }

    private companion object {
        const val CHANNEL: Int = 0
        const val BRAND: Int = 1
        const val DATA: Int = 2
        val BRAND_CHANNEL: Identifier = Identifier("minecraft:brand")
    }
}
