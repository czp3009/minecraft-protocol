package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A literal integer or the identifier of a context-int-provider; this model does not evaluate providers. */
@Serializable(with = ResolvableIntSerializer::class)
sealed interface ResolvableInt {
    data class Constant(val value: Int) : ResolvableInt
    data class Reference(val key: Identifier) : ResolvableInt
}

/** A literal float or the identifier of a context-float-provider; this model does not evaluate providers. */
@Serializable(with = ResolvableFloatSerializer::class)
sealed interface ResolvableFloat {
    data class Constant(val value: Float) : ResolvableFloat
    data class Reference(val key: Identifier) : ResolvableFloat
}

internal object ResolvableIntSerializer : KSerializer<ResolvableInt> {
    override val descriptor = buildClassSerialDescriptor("minecraft.ResolvableInt") {
        element<Boolean>("constant")
        element<Int>("value", isOptional = true)
        element<Identifier>("key", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ResolvableInt) {
        val output = encoder.beginStructure(descriptor)
        output.encodeBooleanElement(descriptor, 0, value is ResolvableInt.Constant)
        when (value) {
            is ResolvableInt.Constant -> output.encodeIntElement(descriptor, 1, value.value)
            is ResolvableInt.Reference -> output.encodeSerializableElement(
                descriptor,
                2,
                Identifier.serializer(),
                value.key
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ResolvableInt {
        val input = decoder.beginStructure(descriptor)
        val result = if (input.decodeBooleanElement(descriptor, 0)) {
            ResolvableInt.Constant(input.decodeIntElement(descriptor, 1))
        } else {
            ResolvableInt.Reference(input.decodeSerializableElement(descriptor, 2, Identifier.serializer()))
        }
        input.endStructure(descriptor)
        return result
    }
}

internal object ResolvableFloatSerializer : KSerializer<ResolvableFloat> {
    override val descriptor = buildClassSerialDescriptor("minecraft.ResolvableFloat") {
        element<Boolean>("constant")
        element<Float>("value", isOptional = true)
        element<Identifier>("key", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ResolvableFloat) {
        val output = encoder.beginStructure(descriptor)
        output.encodeBooleanElement(descriptor, 0, value is ResolvableFloat.Constant)
        when (value) {
            is ResolvableFloat.Constant -> output.encodeFloatElement(descriptor, 1, value.value)
            is ResolvableFloat.Reference -> output.encodeSerializableElement(
                descriptor,
                2,
                Identifier.serializer(),
                value.key
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ResolvableFloat {
        val input = decoder.beginStructure(descriptor)
        val result = if (input.decodeBooleanElement(descriptor, 0)) {
            ResolvableFloat.Constant(input.decodeFloatElement(descriptor, 1))
        } else {
            ResolvableFloat.Reference(input.decodeSerializableElement(descriptor, 2, Identifier.serializer()))
        }
        input.endStructure(descriptor)
        return result
    }
}
