package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Absolute positions used by an entity synchronization packet, including optional intermediate tick offsets. */
@Serializable(with = PositionPathSerializer::class)
sealed interface PositionPath {
    val endPosition: Vector3d

    data class Linear(override val endPosition: Vector3d) : PositionPath

    /** The endpoint is derived from the final step, as in the official stream decoder. */
    data class Stepped(val steps: List<PositionStep>) : PositionPath {
        init {
            require(steps.isNotEmpty()) { "A stepped position path must contain an endpoint" }
        }

        override val endPosition: Vector3d
            get() = steps.last().position
    }
}

/** An absolute position and the tick offset carried in an entity's position path. */
@Serializable
data class PositionStep(val position: Vector3d, @VarInt val tickOffset: Int)

internal object PositionPathSerializer : KSerializer<PositionPath> {
    override val descriptor = buildClassSerialDescriptor("minecraft.PositionPath") {
        element<Int>("type", annotations = listOf(VarInt()))
        element<Vector3d>("endPosition", isOptional = true)
        element<List<PositionStep>>("steps", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: PositionPath) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is PositionPath.Linear -> {
                output.encodeIntElement(descriptor, 0, 0)
                output.encodeSerializableElement(descriptor, 1, Vector3d.serializer(), value.endPosition)
            }

            is PositionPath.Stepped -> {
                output.encodeIntElement(descriptor, 0, 1)
                output.encodeSerializableElement(descriptor, 2, ListSerializer(PositionStep.serializer()), value.steps)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PositionPath {
        val input = decoder.beginStructure(descriptor)
        val result = if (input.decodeIntElement(descriptor, 0) == 1) {
            PositionPath.Stepped(
                input.decodeSerializableElement(
                    descriptor,
                    2,
                    ListSerializer(PositionStep.serializer())
                )
            )
        } else {
            PositionPath.Linear(input.decodeSerializableElement(descriptor, 1, Vector3d.serializer()))
        }
        input.endStructure(descriptor)
        return result
    }
}
