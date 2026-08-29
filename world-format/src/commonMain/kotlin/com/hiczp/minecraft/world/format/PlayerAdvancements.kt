@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.world.format

import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Player advancement progress stored in `players/advancements/<uuid>.json`. */
@Serializable(with = PlayerAdvancementsSerializer::class)
data class PlayerAdvancements(
    val dataVersion: Int,
    val advancements: Map<String, Progress>,
) {
    @Serializable
    data class Progress(
        val criteria: Map<String, String>,
        val done: Boolean,
    )
}

/** Encodes dynamic advancement IDs beside the fixed `DataVersion` member in the root JSON object. */
internal object PlayerAdvancementsSerializer : KSerializer<PlayerAdvancements> {
    private const val DATA_VERSION = "DataVersion"
    private val progressSerializer = PlayerAdvancements.Progress.serializer()

    override val descriptor: SerialDescriptor = MapSerializer(
        String.serializer(),
        progressSerializer,
    ).descriptor

    override fun serialize(encoder: Encoder, value: PlayerAdvancements) {
        val output = encoder.beginCollection(descriptor, value.advancements.size + 1)
        var index = 0
        output.encodeStringElement(descriptor, index++, DATA_VERSION)
        output.encodeIntElement(descriptor, index++, value.dataVersion)
        value.advancements.forEach { (identifier, progress) ->
            output.encodeStringElement(descriptor, index++, identifier)
            output.encodeSerializableElement(
                descriptor,
                index++,
                progressSerializer,
                progress,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PlayerAdvancements {
        val input = decoder.beginStructure(descriptor)
        var dataVersion: Int? = null
        val advancements = linkedMapOf<String, PlayerAdvancements.Progress>()
        var key: String? = null
        while (true) {
            val index = input.decodeElementIndex(descriptor)
            if (index < 0) break
            if (index % 2 == 0) {
                if (key != null) {
                    throw SerializationException("Advancement map key has no value")
                }
                key = input.decodeStringElement(descriptor, index)
                continue
            }
            val identifier = key ?: throw SerializationException("Advancement map value has no key")
            if (identifier == DATA_VERSION) {
                dataVersion = input.decodeIntElement(descriptor, index)
            } else {
                advancements[identifier] = input.decodeSerializableElement(
                    descriptor,
                    index,
                    progressSerializer,
                )
            }
            key = null
        }
        input.endStructure(descriptor)
        if (key != null) throw SerializationException("Advancement map key has no value")
        return PlayerAdvancements(
            dataVersion = dataVersion ?: throw MissingFieldException(DATA_VERSION, descriptor.serialName),
            advancements = advancements,
        )
    }
}
