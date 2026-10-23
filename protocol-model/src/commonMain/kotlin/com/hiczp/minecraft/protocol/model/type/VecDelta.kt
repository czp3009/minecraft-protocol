package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.FixedLength
import com.hiczp.minecraft.protocol.model.wire.MinElementBytes
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Relative entity positions in fixed-point protocol units; the enclosing packet carries the step count. */
sealed interface VecDelta {
    val stepCount: Int

    @Serializable
    data class Linear(val xa: Short, val ya: Short, val za: Short) : VecDelta {
        override val stepCount: Int get() = 0
    }

    /** A nonempty sequence distinguishes this representation from the unstepped three-short form. */
    data class Stepped(val steps: List<DeltaStep>) : VecDelta {
        init {
            require(steps.isNotEmpty()) { "A stepped delta must contain at least one step" }
        }

        override val stepCount: Int get() = steps.size

        @Serializable(with = DeltaStepSerializer::class)
        data class DeltaStep(val xa: Short, val ya: Short, val za: Short, val ticks: Int)
    }
}

@Serializable
private data class DeltaStepSurrogate(@VarInt val ticks: Int, val xa: Short, val ya: Short, val za: Short)

internal object DeltaStepSerializer : KSerializer<VecDelta.Stepped.DeltaStep> {
    override val descriptor = DeltaStepSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: VecDelta.Stepped.DeltaStep) =
        encoder.encodeSerializableValue(
            DeltaStepSurrogate.serializer(),
            DeltaStepSurrogate(value.ticks, value.xa, value.ya, value.za)
        )

    override fun deserialize(decoder: Decoder): VecDelta.Stepped.DeltaStep {
        val surrogate = decoder.decodeSerializableValue(DeltaStepSurrogate.serializer())
        return VecDelta.Stepped.DeltaStep(surrogate.xa, surrogate.ya, surrogate.za, surrogate.ticks)
    }
}

internal class VecDeltaSerializer(private val stepCount: Int) : KSerializer<VecDelta> {
    override val descriptor = buildClassSerialDescriptor("minecraft.VecDelta") {
        element<VecDelta.Linear>("linear", isOptional = true)
        element<List<VecDelta.Stepped.DeltaStep>>(
            "steps",
            annotations = listOf(FixedLength(stepCount), MinElementBytes(7)),
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: VecDelta) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is VecDelta.Linear -> output.encodeSerializableElement(descriptor, 0, VecDelta.Linear.serializer(), value)
            is VecDelta.Stepped -> output.encodeSerializableElement(
                descriptor, 1, ListSerializer(VecDelta.Stepped.DeltaStep.serializer()), value.steps,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): VecDelta {
        val input = decoder.beginStructure(descriptor)
        val result = if (stepCount == 0) {
            input.decodeSerializableElement(descriptor, 0, VecDelta.Linear.serializer())
        } else {
            VecDelta.Stepped(
                input.decodeSerializableElement(
                    descriptor,
                    1,
                    ListSerializer(VecDelta.Stepped.DeltaStep.serializer())
                )
            )
        }
        input.endStructure(descriptor)
        return result
    }
}
