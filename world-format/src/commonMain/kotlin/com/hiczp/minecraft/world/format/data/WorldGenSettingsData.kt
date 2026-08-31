package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.NbtTagTreeSerializer
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Contents of root `minecraft:world_gen_settings` saved data. */
@Serializable
data class WorldGenSettingsData(
    val seed: Long,
    @SerialName("generate_structures")
    val generateStructures: Boolean,
    @SerialName("bonus_chest")
    val bonusChest: Boolean,
    @Serializable(with = WorldGenDimensionsSerializer::class)
    val dimensions: Map<DimensionId, WorldGenDimension>,
    @SerialName("legacy_custom_options")
    val legacyCustomOptions: String? = null,
)

/** One level stem stored in `world_gen_settings.dat`. */
@Serializable
data class WorldGenDimension(
    @Serializable(with = WorldGenDimensionTypeSerializer::class)
    val type: WorldGenDimensionType,
    val generator: NbtCompound,
)

object WorldGenDimensionsSerializer : KSerializer<Map<DimensionId, WorldGenDimension>> {
    private val serializer = MapSerializer(String.serializer(), WorldGenDimension.serializer())

    override val descriptor: SerialDescriptor = serializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<DimensionId, WorldGenDimension>) {
        encoder.encodeSerializableValue(serializer, value.mapKeys { (dimensionId) -> dimensionId.toString() })
    }

    override fun deserialize(decoder: Decoder): Map<DimensionId, WorldGenDimension> = buildMap {
        decoder.decodeSerializableValue(serializer).forEach { (value, worldGenDimension) ->
            val dimensionId = try {
                DimensionId.parse(value)
            } catch (failure: IllegalArgumentException) {
                throw SerializationException("Invalid dimension ID: $value", failure)
            }
            if (put(dimensionId, worldGenDimension) != null) {
                throw SerializationException("Duplicate dimension ID after namespace normalization: $dimensionId")
            }
        }
    }
}

/** The official holder shape accepted for a level stem's dimension type. */
sealed interface WorldGenDimensionType {
    data class Reference(val dimensionTypeId: DimensionTypeId) : WorldGenDimensionType

    data class Inline(val dimensionTypeData: NbtCompound) : WorldGenDimensionType
}

object WorldGenDimensionTypeSerializer : KSerializer<WorldGenDimensionType> {
    override val descriptor: SerialDescriptor = NbtTagTreeSerializer.descriptor

    override fun serialize(encoder: Encoder, value: WorldGenDimensionType) {
        val nbtTag = when (value) {
            is WorldGenDimensionType.Reference -> NbtString(value.dimensionTypeId.toString())
            is WorldGenDimensionType.Inline -> value.dimensionTypeData
        }
        encoder.encodeSerializableValue(NbtTagTreeSerializer, nbtTag)
    }

    override fun deserialize(decoder: Decoder): WorldGenDimensionType = when (
        val nbtTag = decoder.decodeSerializableValue(NbtTagTreeSerializer)
    ) {
        is NbtString -> try {
            WorldGenDimensionType.Reference(DimensionTypeId.parse(nbtTag.value))
        } catch (failure: IllegalArgumentException) {
            throw SerializationException("Invalid dimension-type reference: ${nbtTag.value}", failure)
        }

        is NbtCompound -> WorldGenDimensionType.Inline(nbtTag)
        else -> throw SerializationException(
            "A world-gen dimension type must be TAG_String or TAG_Compound, got ${nbtTag::class.simpleName}",
        )
    }
}
