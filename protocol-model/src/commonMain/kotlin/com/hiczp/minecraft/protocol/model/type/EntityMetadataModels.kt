@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class Vector3f(
    val x: Float,
    val y: Float,
    val z: Float,
)

@Serializable
data class Quaternionf(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
)

@Serializable
data class VillagerData(
    @VarInt
    val typeId: Int,
    @VarInt
    val professionId: Int,
    @VarInt
    val level: Int,
) {
    init {
        require(typeId >= 0) { "A villager type ID must be non-negative" }
        require(professionId >= 0) { "A villager profession ID must be non-negative" }
    }
}

@Serializable
enum class EntityPose {
    STANDING,
    FALL_FLYING,
    SLEEPING,
    SWIMMING,
    SPIN_ATTACK,
    CROUCHING,
    LONG_JUMPING,
    DYING,
    CROAKING,
    USING_TONGUE,
    SITTING,
    ROARING,
    SNIFFING,
    EMERGING,
    DIGGING,
    SLIDING,
    SHOOTING,
    INHALING,
}

@Serializable
enum class SnifferState {
    IDLING,
    FEELING_HAPPY,
    SCENTING,
    SNIFFING,
    SEARCHING,
    DIGGING,
    RISING,
}

@Serializable
enum class ArmadilloState {
    IDLE,
    ROLLING,
    SCARED,
    UNROLLING,
}

@Serializable
enum class CopperGolemState {
    IDLE,
    GETTING_ITEM,
    GETTING_NO_ITEM,
    DROPPING_ITEM,
    DROPPING_NO_ITEM,
}

@Serializable
enum class CopperWeatherState {
    UNAFFECTED,
    EXPOSED,
    WEATHERED,
    OXIDIZED,
}

@Serializable
enum class HumanoidArm {
    LEFT,
    RIGHT,
}

enum class EntityVariantRegistry {
    CAT,
    CAT_SOUND,
    COW,
    COW_SOUND,
    WOLF,
    WOLF_SOUND,
    FROG,
    PIG,
    PIG_SOUND,
    CHICKEN,
    CHICKEN_SOUND,
    ZOMBIE_NAUTILUS,
}

@Serializable
data class ResolvableProfile(
    val identity: ProfileIdentity,
    val skin: PlayerSkinPatch = PlayerSkinPatch(),
)

@Serializable(with = EntityDataValueSerializer::class)
sealed interface EntityDataValue {
    data class ByteValue(val value: Byte) : EntityDataValue

    data class IntValue(val value: Int) : EntityDataValue

    data class LongValue(val value: Long) : EntityDataValue

    data class FloatValue(val value: Float) : EntityDataValue

    data class StringValue(val value: String) : EntityDataValue

    data class ComponentValue(val value: TextComponent) : EntityDataValue

    data class OptionalComponent(val value: TextComponent?) : EntityDataValue

    data class ItemStackValue(val value: ItemStack) : EntityDataValue

    data class BooleanValue(val value: Boolean) : EntityDataValue

    data class Rotations(val value: Vector3f) : EntityDataValue

    data class BlockPositionValue(val value: BlockPosition) : EntityDataValue

    data class OptionalBlockPosition(val value: BlockPosition?) : EntityDataValue

    data class Direction(val value: BlockFace) : EntityDataValue

    data class OptionalLivingEntityReference(val value: Uuid?) : EntityDataValue

    data class BlockState(val stateId: Int) : EntityDataValue {
        init {
            require(stateId >= 0) { "A block-state ID must be non-negative" }
        }
    }

    data class OptionalBlockState(val stateId: Int?) : EntityDataValue {
        init {
            require(stateId == null || stateId > 0) {
                "An optional block-state ID must be positive when present"
            }
        }
    }

    data class Particle(val value: ParticleOptions) : EntityDataValue

    data class Particles(val values: List<ParticleOptions>) : EntityDataValue

    data class Villager(val value: VillagerData) : EntityDataValue

    data class OptionalUnsignedInt(val value: Int?) : EntityDataValue {
        init {
            require(value == null || value >= 0) {
                "An optional unsigned integer must be non-negative"
            }
        }
    }

    data class Pose(val value: EntityPose) : EntityDataValue

    data class RegistryVariant(
        val registry: EntityVariantRegistry,
        val registryId: Int,
    ) : EntityDataValue {
        init {
            require(registryId >= 0) { "A variant registry ID must be non-negative" }
        }
    }

    data class OptionalGlobalPosition(val value: GlobalPosition?) :
        EntityDataValue

    data class PaintingVariant(val value: PaintingVariantHolder) :
        EntityDataValue

    data class Sniffer(val value: SnifferState) : EntityDataValue

    data class Armadillo(val value: ArmadilloState) : EntityDataValue

    data class CopperGolem(val value: CopperGolemState) : EntityDataValue

    data class CopperWeather(val value: CopperWeatherState) : EntityDataValue

    data class Vector(val value: Vector3f) : EntityDataValue

    data class Quaternion(val value: Quaternionf) : EntityDataValue

    data class Profile(val value: ResolvableProfile) : EntityDataValue

    data class Arm(val value: HumanoidArm) : EntityDataValue
}

@Serializable
data class EntityMetadataEntry(
    val index: Int,
    val value: EntityDataValue,
) {
    init {
        require(index in 0..254) { "An entity metadata index must be in 0..254" }
    }
}

@Serializable(with = EntityMetadataSerializer::class)
data class EntityMetadata(
    val entries: List<EntityMetadataEntry>,
) {
    init {
        require(entries.size <= 255) {
            "Entity metadata cannot contain more than 255 entries"
        }
    }
}

internal object EntityMetadataSerializer : KSerializer<EntityMetadata> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.EntityMetadata",
    ) {
        element<Int>("index", annotations = listOf(UnsignedByte()))
        element<EntityDataValue>("value", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: EntityMetadata) {
        val output = encoder.beginStructure(descriptor)
        for (entry in value.entries) {
            output.encodeIntElement(descriptor, INDEX, entry.index)
            output.encodeSerializableElement(
                descriptor,
                VALUE,
                EntityDataValue.serializer(),
                entry.value,
            )
        }
        output.encodeIntElement(descriptor, INDEX, EOF)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): EntityMetadata {
        val input = decoder.beginStructure(descriptor)
        val entries = buildList {
            while (true) {
                val index = input.decodeIntElement(descriptor, INDEX)
                if (index == EOF) break
                add(
                    EntityMetadataEntry(
                        index,
                        input.decodeSerializableElement(
                            descriptor,
                            VALUE,
                            EntityDataValue.serializer(),
                        ),
                    ),
                )
            }
        }
        input.endStructure(descriptor)
        return EntityMetadata(entries)
    }

    private const val INDEX: Int = 0
    private const val VALUE: Int = 1
    private const val EOF: Int = 255
}

internal object EntityDataValueSerializer : KSerializer<EntityDataValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.EntityDataValue",
    ) {
        element<Int>("serializerId", annotations = listOf(VarInt()))
        element<Byte>("byte", isOptional = true)
        element<Int>("varInt", annotations = listOf(VarInt()), isOptional = true)
        element<Long>("varLong", annotations = listOf(VarLong()), isOptional = true)
        element<Float>("float", isOptional = true)
        element<String>("string", isOptional = true)
        element<TextComponent>("component", isOptional = true)
        element<TextComponent?>("optionalComponent", isOptional = true)
        element<ItemStack>("itemStack", isOptional = true)
        element<Boolean>("boolean", isOptional = true)
        element<Vector3f>("vector3f", isOptional = true)
        element<BlockPosition>("blockPosition", isOptional = true)
        element<BlockPosition?>("optionalBlockPosition", isOptional = true)
        element<BlockFace>(
            "direction",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
        element<Uuid?>("optionalUuid", isOptional = true)
        element<Int>(
            "blockState",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element<Int>(
            "optionalBlockState",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element<ParticleOptions>("particle", isOptional = true)
        element<List<ParticleOptions>>("particles", isOptional = true)
        element<VillagerData>("villagerData", isOptional = true)
        element<Int>(
            "optionalUnsignedInt",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element<EntityPose>(
            "pose",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
        element<Int>(
            "registryId",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element<GlobalPosition?>("optionalGlobalPosition", isOptional = true)
        element<PaintingVariantHolder>("paintingVariant", isOptional = true)
        element<SnifferState>(
            "snifferState",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
        element<ArmadilloState>(
            "armadilloState",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
        element<CopperGolemState>(
            "copperGolemState",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
        element<CopperWeatherState>(
            "copperWeatherState",
            annotations = listOf(ClampEnum()),
            isOptional = true,
        )
        element<Quaternionf>("quaternion", isOptional = true)
        element<ResolvableProfile>("profile", isOptional = true)
        element<HumanoidArm>(
            "arm",
            annotations = listOf(ZeroFallbackEnum()),
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: EntityDataValue) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is EntityDataValue.ByteValue -> output.primitive(BYTE, 0) {
                encodeByteElement(descriptor, BYTE, value.value)
            }

            is EntityDataValue.IntValue -> output.primitive(VAR_INT, 1) {
                encodeIntElement(descriptor, VAR_INT, value.value)
            }

            is EntityDataValue.LongValue -> output.primitive(VAR_LONG, 2) {
                encodeLongElement(descriptor, VAR_LONG, value.value)
            }

            is EntityDataValue.FloatValue -> output.primitive(FLOAT, 3) {
                encodeFloatElement(descriptor, FLOAT, value.value)
            }

            is EntityDataValue.StringValue -> output.primitive(STRING, 4) {
                encodeStringElement(descriptor, STRING, value.value)
            }

            is EntityDataValue.ComponentValue -> output.serializable(
                5,
                COMPONENT,
                TextComponent.serializer(),
                value.value,
            )

            is EntityDataValue.OptionalComponent -> output.nullable(
                6,
                OPTIONAL_COMPONENT,
                TextComponent.serializer(),
                value.value,
            )

            is EntityDataValue.ItemStackValue -> output.serializable(
                7,
                ITEM_STACK,
                ItemStack.serializer(),
                value.value,
            )

            is EntityDataValue.BooleanValue -> output.primitive(BOOLEAN, 8) {
                encodeBooleanElement(descriptor, BOOLEAN, value.value)
            }

            is EntityDataValue.Rotations -> output.serializable(
                9,
                VECTOR3F,
                Vector3f.serializer(),
                value.value,
            )

            is EntityDataValue.BlockPositionValue -> output.serializable(
                10,
                BLOCK_POSITION,
                BlockPosition.serializer(),
                value.value,
            )

            is EntityDataValue.OptionalBlockPosition -> output.nullable(
                11,
                OPTIONAL_BLOCK_POSITION,
                BlockPosition.serializer(),
                value.value,
            )

            is EntityDataValue.Direction -> output.serializable(
                12,
                DIRECTION,
                BlockFace.serializer(),
                value.value,
            )

            is EntityDataValue.OptionalLivingEntityReference -> output.nullable(
                13,
                OPTIONAL_UUID,
                Uuid.serializer(),
                value.value,
            )

            is EntityDataValue.BlockState -> output.primitive(BLOCK_STATE, 14) {
                encodeIntElement(descriptor, BLOCK_STATE, value.stateId)
            }

            is EntityDataValue.OptionalBlockState -> output.primitive(
                OPTIONAL_BLOCK_STATE,
                15,
            ) {
                encodeIntElement(
                    descriptor,
                    OPTIONAL_BLOCK_STATE,
                    value.stateId ?: 0,
                )
            }

            is EntityDataValue.Particle -> output.serializable(
                16,
                PARTICLE,
                ParticleOptions.serializer(),
                value.value,
            )

            is EntityDataValue.Particles -> output.serializable(
                17,
                PARTICLES,
                ListSerializer(ParticleOptions.serializer()),
                value.values,
            )

            is EntityDataValue.Villager -> output.serializable(
                18,
                VILLAGER,
                VillagerData.serializer(),
                value.value,
            )

            is EntityDataValue.OptionalUnsignedInt -> output.primitive(
                OPTIONAL_UNSIGNED_INT,
                19,
            ) {
                encodeIntElement(
                    descriptor,
                    OPTIONAL_UNSIGNED_INT,
                    value.value?.plus(1) ?: 0,
                )
            }

            is EntityDataValue.Pose -> output.serializable(
                20,
                POSE,
                EntityPose.serializer(),
                value.value,
            )

            is EntityDataValue.RegistryVariant -> output.primitive(
                REGISTRY_ID,
                value.registry.serializerId,
            ) {
                encodeIntElement(descriptor, REGISTRY_ID, value.registryId)
            }

            is EntityDataValue.OptionalGlobalPosition -> output.nullable(
                33,
                OPTIONAL_GLOBAL_POSITION,
                GlobalPosition.serializer(),
                value.value,
            )

            is EntityDataValue.PaintingVariant -> output.serializable(
                34,
                PAINTING_VARIANT,
                PaintingVariantHolder.serializer(),
                value.value,
            )

            is EntityDataValue.Sniffer -> output.serializable(
                35,
                SNIFFER,
                SnifferState.serializer(),
                value.value,
            )

            is EntityDataValue.Armadillo -> output.serializable(
                36,
                ARMADILLO,
                ArmadilloState.serializer(),
                value.value,
            )

            is EntityDataValue.CopperGolem -> output.serializable(
                37,
                COPPER_GOLEM,
                CopperGolemState.serializer(),
                value.value,
            )

            is EntityDataValue.CopperWeather -> output.serializable(
                38,
                COPPER_WEATHER,
                CopperWeatherState.serializer(),
                value.value,
            )

            is EntityDataValue.Vector -> output.serializable(
                39,
                VECTOR3F,
                Vector3f.serializer(),
                value.value,
            )

            is EntityDataValue.Quaternion -> output.serializable(
                40,
                QUATERNION,
                Quaternionf.serializer(),
                value.value,
            )

            is EntityDataValue.Profile -> output.serializable(
                41,
                PROFILE,
                ResolvableProfile.serializer(),
                value.value,
            )

            is EntityDataValue.Arm -> output.serializable(
                42,
                ARM,
                HumanoidArm.serializer(),
                value.value,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): EntityDataValue {
        val input = decoder.beginStructure(descriptor)
        val type = input.decodeIntElement(descriptor, SERIALIZER_ID)
        val value = when (type) {
            0 -> EntityDataValue.ByteValue(
                input.decodeByteElement(descriptor, BYTE),
            )

            1 -> EntityDataValue.IntValue(
                input.decodeIntElement(descriptor, VAR_INT),
            )

            2 -> EntityDataValue.LongValue(
                input.decodeLongElement(descriptor, VAR_LONG),
            )

            3 -> EntityDataValue.FloatValue(
                input.decodeFloatElement(descriptor, FLOAT),
            )

            4 -> EntityDataValue.StringValue(
                input.decodeStringElement(descriptor, STRING),
            )

            5 -> EntityDataValue.ComponentValue(
                input.serializable(COMPONENT, TextComponent.serializer()),
            )

            6 -> EntityDataValue.OptionalComponent(
                input.nullable(OPTIONAL_COMPONENT, TextComponent.serializer()),
            )

            7 -> EntityDataValue.ItemStackValue(
                input.serializable(ITEM_STACK, ItemStack.serializer()),
            )

            8 -> EntityDataValue.BooleanValue(
                input.decodeBooleanElement(descriptor, BOOLEAN),
            )

            9 -> EntityDataValue.Rotations(
                input.serializable(VECTOR3F, Vector3f.serializer()),
            )

            10 -> EntityDataValue.BlockPositionValue(
                input.serializable(BLOCK_POSITION, BlockPosition.serializer()),
            )

            11 -> EntityDataValue.OptionalBlockPosition(
                input.nullable(
                    OPTIONAL_BLOCK_POSITION,
                    BlockPosition.serializer(),
                ),
            )

            12 -> EntityDataValue.Direction(
                input.serializable(DIRECTION, BlockFace.serializer()),
            )

            13 -> EntityDataValue.OptionalLivingEntityReference(
                input.nullable(OPTIONAL_UUID, Uuid.serializer()),
            )

            14 -> EntityDataValue.BlockState(
                input.decodeIntElement(descriptor, BLOCK_STATE),
            )

            15 -> EntityDataValue.OptionalBlockState(
                input.decodeIntElement(descriptor, OPTIONAL_BLOCK_STATE)
                    .takeUnless { it == 0 },
            )

            16 -> EntityDataValue.Particle(
                input.serializable(PARTICLE, ParticleOptions.serializer()),
            )

            17 -> EntityDataValue.Particles(
                input.serializable(
                    PARTICLES,
                    ListSerializer(ParticleOptions.serializer()),
                ),
            )

            18 -> EntityDataValue.Villager(
                input.serializable(VILLAGER, VillagerData.serializer()),
            )

            19 -> EntityDataValue.OptionalUnsignedInt(
                input.decodeIntElement(descriptor, OPTIONAL_UNSIGNED_INT)
                    .let { if (it == 0) null else it - 1 },
            )

            20 -> EntityDataValue.Pose(
                input.serializable(POSE, EntityPose.serializer()),
            )

            in 21..32 -> EntityDataValue.RegistryVariant(
                variantRegistryFromSerializerId(type),
                input.decodeIntElement(descriptor, REGISTRY_ID),
            )

            33 -> EntityDataValue.OptionalGlobalPosition(
                input.nullable(
                    OPTIONAL_GLOBAL_POSITION,
                    GlobalPosition.serializer(),
                ),
            )

            34 -> EntityDataValue.PaintingVariant(
                input.serializable(
                    PAINTING_VARIANT,
                    PaintingVariantHolder.serializer(),
                ),
            )

            35 -> EntityDataValue.Sniffer(
                input.serializable(SNIFFER, SnifferState.serializer()),
            )

            36 -> EntityDataValue.Armadillo(
                input.serializable(ARMADILLO, ArmadilloState.serializer()),
            )

            37 -> EntityDataValue.CopperGolem(
                input.serializable(
                    COPPER_GOLEM,
                    CopperGolemState.serializer(),
                ),
            )

            38 -> EntityDataValue.CopperWeather(
                input.serializable(
                    COPPER_WEATHER,
                    CopperWeatherState.serializer(),
                ),
            )

            39 -> EntityDataValue.Vector(
                input.serializable(VECTOR3F, Vector3f.serializer()),
            )

            40 -> EntityDataValue.Quaternion(
                input.serializable(QUATERNION, Quaternionf.serializer()),
            )

            41 -> EntityDataValue.Profile(
                input.serializable(PROFILE, ResolvableProfile.serializer()),
            )

            42 -> EntityDataValue.Arm(
                input.serializable(ARM, HumanoidArm.serializer()),
            )

            else -> throw SerializationException(
                "Unknown entity metadata serializer $type",
            )
        }
        input.endStructure(descriptor)
        return value
    }

    private inline fun CompositeEncoder.primitive(
        payloadIndex: Int,
        serializerId: Int,
        payload: CompositeEncoder.() -> Unit,
    ) {
        encodeIntElement(descriptor, SERIALIZER_ID, serializerId)
        payload()
    }

    private fun <T> CompositeEncoder.serializable(
        serializerId: Int,
        index: Int,
        serializer: KSerializer<T>,
        value: T,
    ) {
        encodeIntElement(descriptor, SERIALIZER_ID, serializerId)
        encodeSerializableElement(descriptor, index, serializer, value)
    }

    private fun <T : Any> CompositeEncoder.nullable(
        serializerId: Int,
        index: Int,
        serializer: KSerializer<T>,
        value: T?,
    ) {
        encodeIntElement(descriptor, SERIALIZER_ID, serializerId)
        encodeNullableSerializableElement(
            descriptor,
            index,
            serializer,
            value,
        )
    }

    private fun <T> CompositeDecoder.serializable(
        index: Int,
        serializer: KSerializer<T>,
    ): T = decodeSerializableElement(descriptor, index, serializer)

    private fun <T : Any> CompositeDecoder.nullable(
        index: Int,
        serializer: KSerializer<T>,
    ): T? = decodeNullableSerializableElement(
        descriptor,
        index,
        serializer.nullable,
    )

    private val EntityVariantRegistry.serializerId: Int
        get() = ordinal + 21

    private fun variantRegistryFromSerializerId(
        id: Int,
    ): EntityVariantRegistry = EntityVariantRegistry.entries[id - 21]

    private const val SERIALIZER_ID: Int = 0
    private const val BYTE: Int = 1
    private const val VAR_INT: Int = 2
    private const val VAR_LONG: Int = 3
    private const val FLOAT: Int = 4
    private const val STRING: Int = 5
    private const val COMPONENT: Int = 6
    private const val OPTIONAL_COMPONENT: Int = 7
    private const val ITEM_STACK: Int = 8
    private const val BOOLEAN: Int = 9
    private const val VECTOR3F: Int = 10
    private const val BLOCK_POSITION: Int = 11
    private const val OPTIONAL_BLOCK_POSITION: Int = 12
    private const val DIRECTION: Int = 13
    private const val OPTIONAL_UUID: Int = 14
    private const val BLOCK_STATE: Int = 15
    private const val OPTIONAL_BLOCK_STATE: Int = 16
    private const val PARTICLE: Int = 17
    private const val PARTICLES: Int = 18
    private const val VILLAGER: Int = 19
    private const val OPTIONAL_UNSIGNED_INT: Int = 20
    private const val POSE: Int = 21
    private const val REGISTRY_ID: Int = 22
    private const val OPTIONAL_GLOBAL_POSITION: Int = 23
    private const val PAINTING_VARIANT: Int = 24
    private const val SNIFFER: Int = 25
    private const val ARMADILLO: Int = 26
    private const val COPPER_GOLEM: Int = 27
    private const val COPPER_WEATHER: Int = 28
    private const val QUATERNION: Int = 29
    private const val PROFILE: Int = 30
    private const val ARM: Int = 31
}
