@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.model.wire.NetworkNbt
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

@Serializable
data class AdventureModePredicate(
    val predicates: List<BlockPredicate>,
)

@Serializable
data class BlockPredicate(
    val blocks: RegistryHolderSet? = null,
    val properties: StatePropertiesPredicate? = null,
    @NetworkNbt
    val nbt: NbtCompound? = null,
    val components: DataComponentMatchers = DataComponentMatchers(),
)

@Serializable
data class StatePropertiesPredicate(
    val properties: List<StatePropertyMatcher>,
)

@Serializable
data class StatePropertyMatcher(
    val name: String,
    val expected: StatePropertyValue,
)

@Serializable(with = StatePropertyValueSerializer::class)
sealed interface StatePropertyValue {
    @Serializable
    data class Exact(
        val value: String,
    ) : StatePropertyValue

    @Serializable
    data class Range(
        val minimum: String? = null,
        val maximum: String? = null,
    ) : StatePropertyValue
}

@Serializable
data class DataComponentMatchers(
    val exact: List<DataComponent> = emptyList(),
    @MaxCollectionSize(64)
    val partial: List<PartialDataComponentMatcher> = emptyList(),
) {
    init {
        require(partial.map { it.type }.distinct().size == partial.size) {
            "Partial data-component matcher types must be unique"
        }
    }
}

@Serializable
data class PartialDataComponentMatcher(
    val type: DataComponentPredicateType,
    val predicate: NbtTag,
)

/**
 * Vanilla encodes a concrete predicate registry ID as the left side of an
 * Either and an "any value of this component type" check as the right side.
 */
@Serializable(with = DataComponentPredicateTypeSerializer::class)
sealed interface DataComponentPredicateType {
    data class Concrete(
        val registryId: Int,
    ) : DataComponentPredicateType {
        init {
            require(registryId >= 0) { "A registry ID must be non-negative" }
        }
    }

    data class AnyValue(
        val component: DataComponentType,
    ) : DataComponentPredicateType
}

internal object StatePropertyValueSerializer :
    KSerializer<StatePropertyValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.StatePropertyValue",
    ) {
        element<Boolean>("exact")
        element(
            "exactValue",
            StatePropertyValue.Exact.serializer().descriptor,
            isOptional = true,
        )
        element(
            "rangeValue",
            StatePropertyValue.Range.serializer().descriptor,
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: StatePropertyValue) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is StatePropertyValue.Exact -> {
                output.encodeBooleanElement(descriptor, IS_EXACT, true)
                output.encodeSerializableElement(
                    descriptor,
                    EXACT,
                    StatePropertyValue.Exact.serializer(),
                    value,
                )
            }

            is StatePropertyValue.Range -> {
                output.encodeBooleanElement(descriptor, IS_EXACT, false)
                output.encodeSerializableElement(
                    descriptor,
                    RANGE,
                    StatePropertyValue.Range.serializer(),
                    value,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): StatePropertyValue {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "StatePropertyValue requires ordered decoding",
            )
        }
        val result = if (
            input.decodeBooleanElement(descriptor, IS_EXACT)
        ) {
            input.decodeSerializableElement(
                descriptor,
                EXACT,
                StatePropertyValue.Exact.serializer(),
            )
        } else {
            input.decodeSerializableElement(
                descriptor,
                RANGE,
                StatePropertyValue.Range.serializer(),
            )
        }
        input.endStructure(descriptor)
        return result
    }

    private const val IS_EXACT: Int = 0
    private const val EXACT: Int = 1
    private const val RANGE: Int = 2
}

internal object DataComponentPredicateTypeSerializer :
    KSerializer<DataComponentPredicateType> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.DataComponentPredicateType",
    ) {
        element<Boolean>("concrete")
        element<Int>("registryId", annotations = listOf(VarInt()))
    }

    override fun serialize(
        encoder: Encoder,
        value: DataComponentPredicateType,
    ) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is DataComponentPredicateType.Concrete -> {
                output.encodeBooleanElement(descriptor, CONCRETE, true)
                output.encodeIntElement(
                    descriptor,
                    REGISTRY_ID,
                    value.registryId,
                )
            }

            is DataComponentPredicateType.AnyValue -> {
                output.encodeBooleanElement(descriptor, CONCRETE, false)
                output.encodeIntElement(
                    descriptor,
                    REGISTRY_ID,
                    value.component.protocolId,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): DataComponentPredicateType {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "DataComponentPredicateType requires ordered decoding",
            )
        }
        val concrete = input.decodeBooleanElement(descriptor, CONCRETE)
        val registryId = input.decodeIntElement(descriptor, REGISTRY_ID)
        val result = if (concrete) {
            if (registryId < 0) {
                throw SerializationException(
                    "Negative predicate registry ID $registryId",
                )
            }
            DataComponentPredicateType.Concrete(registryId)
        } else {
            DataComponentPredicateType.AnyValue(
                DataComponentType.fromProtocolId(registryId)
                    ?: throw SerializationException(
                        "Unknown data component type ID $registryId",
                    ),
            )
        }
        input.endStructure(descriptor)
        return result
    }

    private const val CONCRETE: Int = 0
    private const val REGISTRY_ID: Int = 1
}
