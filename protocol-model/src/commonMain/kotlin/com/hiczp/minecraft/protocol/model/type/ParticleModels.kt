package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Built-in particle registry order for the selected protocol target. */
enum class ParticleType {
    ANGRY_VILLAGER,
    BLOCK,
    BLOCK_MARKER,
    BUBBLE,
    SULFUR_BUBBLES,
    NOXIOUS_GAS,
    NOXIOUS_GAS_CLOUD,
    GEYSER,
    GEYSER_BASE,
    GEYSER_POOF,
    GEYSER_PLUME,
    CLOUD,
    COPPER_FIRE_FLAME,
    CRIT,
    DAMAGE_INDICATOR,
    DRAGON_BREATH,
    DRIPPING_LAVA,
    FALLING_LAVA,
    LANDING_LAVA,
    DRIPPING_WATER,
    FALLING_WATER,
    DUST,
    DUST_COLOR_TRANSITION,
    EFFECT,
    ELDER_GUARDIAN,
    ENCHANTED_HIT,
    ENCHANT,
    END_ROD,
    ENTITY_EFFECT,
    EXPLOSION_EMITTER,
    EXPLOSION,
    GUST,
    SMALL_GUST,
    GUST_EMITTER_LARGE,
    GUST_EMITTER_SMALL,
    SONIC_BOOM,
    FALLING_DUST,
    FIREWORK,
    FISHING,
    FLAME,
    INFESTED,
    CHERRY_LEAVES,
    PALE_OAK_LEAVES,
    TINTED_LEAVES,
    SCULK_SOUL,
    SCULK_CHARGE,
    SCULK_CHARGE_POP,
    SOUL_FIRE_FLAME,
    SOUL,
    FLASH,
    HAPPY_VILLAGER,
    COMPOSTER,
    HEART,
    INSTANT_EFFECT,
    ITEM,
    VIBRATION,
    TRAIL,
    PAUSE_MOB_GROWTH,
    RESET_MOB_GROWTH,
    ITEM_SLIME,
    ITEM_COBWEB,
    ITEM_SNOWBALL,
    LARGE_SMOKE,
    LAVA,
    MYCELIUM,
    NOTE,
    POOF,
    PORTAL,
    RAIN,
    SMOKE,
    WHITE_SMOKE,
    SNEEZE,
    SPIT,
    SQUID_INK,
    SWEEP_ATTACK,
    TOTEM_OF_UNDYING,
    UNDERWATER,
    SPLASH,
    WITCH,
    BUBBLE_POP,
    CURRENT_DOWN,
    BUBBLE_COLUMN_UP,
    NAUTILUS,
    DOLPHIN,
    CAMPFIRE_COSY_SMOKE,
    CAMPFIRE_SIGNAL_SMOKE,
    DRIPPING_HONEY,
    FALLING_HONEY,
    LANDING_HONEY,
    FALLING_NECTAR,
    FALLING_SPORE_BLOSSOM,
    ASH,
    CRIMSON_SPORE,
    WARPED_SPORE,
    SPORE_BLOSSOM_AIR,
    DRIPPING_OBSIDIAN_TEAR,
    FALLING_OBSIDIAN_TEAR,
    LANDING_OBSIDIAN_TEAR,
    REVERSE_PORTAL,
    WHITE_ASH,
    SMALL_FLAME,
    SNOWFLAKE,
    DRIPPING_DRIPSTONE_LAVA,
    FALLING_DRIPSTONE_LAVA,
    DRIPPING_DRIPSTONE_WATER,
    FALLING_DRIPSTONE_WATER,
    GLOW_SQUID_INK,
    GLOW,
    WAX_ON,
    WAX_OFF,
    ELECTRIC_SPARK,
    SCRAPE,
    SHRIEK,
    EGG_CRACK,
    DUST_PLUME,
    TRIAL_SPAWNER_DETECTED_PLAYER,
    TRIAL_SPAWNER_DETECTED_PLAYER_OMINOUS,
    VAULT_CONNECTION,
    DUST_PILLAR,
    OMINOUS_SPAWNING,
    RAID_OMEN,
    TRIAL_OMEN,
    BLOCK_CRUMBLE,
    FIREFLY,
    SULFUR_CUBE_GOO,
    ;

    val wireName: String
        get() = when (this) {
            TRIAL_SPAWNER_DETECTED_PLAYER ->
                "minecraft:trial_spawner_detection"

            TRIAL_SPAWNER_DETECTED_PLAYER_OMINOUS ->
                "minecraft:trial_spawner_detection_ominous"

            else -> "minecraft:${name.lowercase()}"
        }
}

@Serializable(with = PositionSourceSerializer::class)
sealed interface PositionSource {
    data class Block(val position: BlockPosition) : PositionSource

    data class Entity(
        val entityId: Int,
        val yOffset: Float,
    ) : PositionSource
}

@Serializable(with = ParticleOptionsSerializer::class)
sealed interface ParticleOptions {
    val type: ParticleType

    data class Simple(
        override val type: ParticleType,
    ) : ParticleOptions {
        init {
            require(type !in DATA_PARTICLE_TYPES) {
                "$type carries particle data"
            }
        }
    }

    data class Block(
        override val type: ParticleType,
        val blockStateId: Int,
    ) : ParticleOptions {
        init {
            require(type in BLOCK_PARTICLE_TYPES) { "$type is not a block particle" }
            require(blockStateId >= 0) { "A block-state ID must be non-negative" }
        }
    }

    data class Geyser(
        override val type: ParticleType,
        val waterBlocks: Int,
    ) : ParticleOptions {
        init {
            require(type in GEYSER_PARTICLE_TYPES) { "$type is not a geyser particle" }
        }
    }

    data class GeyserBase(
        override val type: ParticleType,
        val waterBlocks: Int,
        val burstImpulseBase: Float,
    ) : ParticleOptions {
        init {
            require(type in GEYSER_BASE_PARTICLE_TYPES) {
                "$type is not a geyser-base particle"
            }
        }
    }

    data class Power(val power: Float) : ParticleOptions {
        override val type: ParticleType = ParticleType.DRAGON_BREATH
    }

    data class Dust(
        val color: Int,
        val scale: Float,
    ) : ParticleOptions {
        override val type: ParticleType = ParticleType.DUST
    }

    data class DustTransition(
        val fromColor: Int,
        val toColor: Int,
        val scale: Float,
    ) : ParticleOptions {
        override val type: ParticleType = ParticleType.DUST_COLOR_TRANSITION
    }

    data class Spell(
        override val type: ParticleType,
        val color: Int,
        val power: Float,
    ) : ParticleOptions {
        init {
            require(type == ParticleType.EFFECT || type == ParticleType.INSTANT_EFFECT) {
                "$type is not a spell particle"
            }
        }
    }

    data class Color(
        override val type: ParticleType,
        val color: Int,
    ) : ParticleOptions {
        init {
            require(type in COLOR_PARTICLE_TYPES) { "$type is not a color particle" }
        }
    }

    data class SculkCharge(val roll: Float) : ParticleOptions {
        override val type: ParticleType = ParticleType.SCULK_CHARGE
    }

    data class Item(val item: ItemStackTemplate) : ParticleOptions {
        override val type: ParticleType = ParticleType.ITEM
    }

    data class Vibration(
        val destination: PositionSource,
        val arrivalInTicks: Int,
    ) : ParticleOptions {
        override val type: ParticleType = ParticleType.VIBRATION
    }

    data class Trail(
        val target: Vector3d,
        val color: Int,
        val duration: Int,
    ) : ParticleOptions {
        override val type: ParticleType = ParticleType.TRAIL
    }

    data class Shriek(val delay: Int) : ParticleOptions {
        override val type: ParticleType = ParticleType.SHRIEK
    }

    companion object {
        internal val BLOCK_PARTICLE_TYPES: Set<ParticleType> = setOf(
            ParticleType.BLOCK,
            ParticleType.BLOCK_MARKER,
            ParticleType.FALLING_DUST,
            ParticleType.DUST_PILLAR,
            ParticleType.BLOCK_CRUMBLE,
        )
        internal val GEYSER_PARTICLE_TYPES: Set<ParticleType> = setOf(
            ParticleType.GEYSER,
            ParticleType.GEYSER_PLUME,
        )
        internal val GEYSER_BASE_PARTICLE_TYPES: Set<ParticleType> = setOf(
            ParticleType.GEYSER_BASE,
            ParticleType.GEYSER_POOF,
        )
        internal val COLOR_PARTICLE_TYPES: Set<ParticleType> = setOf(
            ParticleType.ENTITY_EFFECT,
            ParticleType.TINTED_LEAVES,
            ParticleType.FLASH,
        )
        internal val DATA_PARTICLE_TYPES: Set<ParticleType> =
            BLOCK_PARTICLE_TYPES +
                    GEYSER_PARTICLE_TYPES +
                    GEYSER_BASE_PARTICLE_TYPES +
                    COLOR_PARTICLE_TYPES +
                    setOf(
                        ParticleType.DRAGON_BREATH,
                        ParticleType.DUST,
                        ParticleType.DUST_COLOR_TRANSITION,
                        ParticleType.EFFECT,
                        ParticleType.INSTANT_EFFECT,
                        ParticleType.SCULK_CHARGE,
                        ParticleType.ITEM,
                        ParticleType.VIBRATION,
                        ParticleType.TRAIL,
                        ParticleType.SHRIEK,
                    )
    }
}

@Serializable
data class ExplosionParticle(
    val particle: ParticleOptions,
    val scaling: Float,
    val speed: Float,
)

@Serializable
data class WeightedExplosionParticle(
    val value: ExplosionParticle,
    @VarInt
    val weight: Int,
) {
    init {
        require(weight >= 0) { "A particle weight must be non-negative" }
    }
}

@Serializable
private data class BlockParticlePayload(
    @VarInt
    val blockStateId: Int,
)

@Serializable
private data class GeyserParticlePayload(
    val waterBlocks: Int,
)

@Serializable
private data class GeyserBaseParticlePayload(
    val waterBlocks: Int,
    val burstImpulseBase: Float,
)

@Serializable
private data class PowerParticlePayload(val power: Float)

@Serializable
private data class DustParticlePayload(val color: Int, val scale: Float)

@Serializable
private data class DustTransitionParticlePayload(
    val fromColor: Int,
    val toColor: Int,
    val scale: Float,
)

@Serializable
private data class SpellParticlePayload(val color: Int, val power: Float)

@Serializable
private data class ColorParticlePayload(val color: Int)

@Serializable
private data class SculkChargeParticlePayload(val roll: Float)

@Serializable
private data class ItemParticlePayload(val item: ItemStackTemplate)

@Serializable
private data class VibrationParticlePayload(
    val destination: PositionSource,
    @VarInt
    val arrivalInTicks: Int,
)

@Serializable
private data class TrailParticlePayload(
    val target: Vector3d,
    val color: Int,
    @VarInt
    val duration: Int,
)

@Serializable
private data class ShriekParticlePayload(
    @VarInt
    val delay: Int,
)

internal object PositionSourceSerializer : KSerializer<PositionSource> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.PositionSource",
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element<BlockPosition>("position", isOptional = true)
        element<Int>("entityId", annotations = listOf(VarInt()), isOptional = true)
        element<Float>("yOffset", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: PositionSource) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is PositionSource.Block -> {
                output.encodeIntElement(descriptor, TYPE, 0)
                output.encodeSerializableElement(
                    descriptor,
                    POSITION,
                    BlockPosition.serializer(),
                    value.position,
                )
            }

            is PositionSource.Entity -> {
                output.encodeIntElement(descriptor, TYPE, 1)
                output.encodeIntElement(descriptor, ENTITY_ID, value.entityId)
                output.encodeFloatElement(descriptor, Y_OFFSET, value.yOffset)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PositionSource {
        val input = decoder.beginStructure(descriptor)
        val result = when (val particleType = input.decodeIntElement(descriptor, TYPE)) {
            0 -> PositionSource.Block(
                input.decodeSerializableElement(
                    descriptor,
                    POSITION,
                    BlockPosition.serializer(),
                ),
            )

            1 -> PositionSource.Entity(
                input.decodeIntElement(descriptor, ENTITY_ID),
                input.decodeFloatElement(descriptor, Y_OFFSET),
            )

            else -> throw SerializationException("Unknown position-source type $particleType")
        }
        input.endStructure(descriptor)
        return result
    }

    private const val TYPE: Int = 0
    private const val POSITION: Int = 1
    private const val ENTITY_ID: Int = 2
    private const val Y_OFFSET: Int = 3
}

internal object ParticleOptionsSerializer : KSerializer<ParticleOptions> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ParticleOptions",
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element<BlockParticlePayload>("block", isOptional = true)
        element<GeyserParticlePayload>("geyser", isOptional = true)
        element<GeyserBaseParticlePayload>("geyserBase", isOptional = true)
        element<PowerParticlePayload>("power", isOptional = true)
        element<DustParticlePayload>("dust", isOptional = true)
        element<DustTransitionParticlePayload>("dustTransition", isOptional = true)
        element<SpellParticlePayload>("spell", isOptional = true)
        element<ColorParticlePayload>("color", isOptional = true)
        element<SculkChargeParticlePayload>("sculkCharge", isOptional = true)
        element<ItemParticlePayload>("item", isOptional = true)
        element<VibrationParticlePayload>("vibration", isOptional = true)
        element<TrailParticlePayload>("trail", isOptional = true)
        element<ShriekParticlePayload>("shriek", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ParticleOptions) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, TYPE, value.type.ordinal)
        when (value) {
            is ParticleOptions.Simple -> Unit
            is ParticleOptions.Block -> output.payload(
                BLOCK,
                BlockParticlePayload.serializer(),
                BlockParticlePayload(value.blockStateId),
            )

            is ParticleOptions.Geyser -> output.payload(
                GEYSER,
                GeyserParticlePayload.serializer(),
                GeyserParticlePayload(value.waterBlocks),
            )

            is ParticleOptions.GeyserBase -> output.payload(
                GEYSER_BASE,
                GeyserBaseParticlePayload.serializer(),
                GeyserBaseParticlePayload(value.waterBlocks, value.burstImpulseBase),
            )

            is ParticleOptions.Power -> output.payload(
                POWER,
                PowerParticlePayload.serializer(),
                PowerParticlePayload(value.power),
            )

            is ParticleOptions.Dust -> output.payload(
                DUST,
                DustParticlePayload.serializer(),
                DustParticlePayload(value.color, value.scale),
            )

            is ParticleOptions.DustTransition -> output.payload(
                DUST_TRANSITION,
                DustTransitionParticlePayload.serializer(),
                DustTransitionParticlePayload(value.fromColor, value.toColor, value.scale),
            )

            is ParticleOptions.Spell -> output.payload(
                SPELL,
                SpellParticlePayload.serializer(),
                SpellParticlePayload(value.color, value.power),
            )

            is ParticleOptions.Color -> output.payload(
                COLOR,
                ColorParticlePayload.serializer(),
                ColorParticlePayload(value.color),
            )

            is ParticleOptions.SculkCharge -> output.payload(
                SCULK_CHARGE,
                SculkChargeParticlePayload.serializer(),
                SculkChargeParticlePayload(value.roll),
            )

            is ParticleOptions.Item -> output.payload(
                ITEM,
                ItemParticlePayload.serializer(),
                ItemParticlePayload(value.item),
            )

            is ParticleOptions.Vibration -> output.payload(
                VIBRATION,
                VibrationParticlePayload.serializer(),
                VibrationParticlePayload(value.destination, value.arrivalInTicks),
            )

            is ParticleOptions.Trail -> output.payload(
                TRAIL,
                TrailParticlePayload.serializer(),
                TrailParticlePayload(value.target, value.color, value.duration),
            )

            is ParticleOptions.Shriek -> output.payload(
                SHRIEK,
                ShriekParticlePayload.serializer(),
                ShriekParticlePayload(value.delay),
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ParticleOptions {
        val input = decoder.beginStructure(descriptor)
        val id = input.decodeIntElement(descriptor, TYPE)
        val particleType = ParticleType.entries.getOrNull(id)
            ?: throw SerializationException("Unknown particle type $id")
        val result = when (particleType) {
            in ParticleOptions.BLOCK_PARTICLE_TYPES -> {
                val blockParticlePayload = input.payload(BLOCK, BlockParticlePayload.serializer())
                ParticleOptions.Block(particleType, blockParticlePayload.blockStateId)
            }

            in ParticleOptions.GEYSER_PARTICLE_TYPES -> {
                val geyserParticlePayload = input.payload(GEYSER, GeyserParticlePayload.serializer())
                ParticleOptions.Geyser(particleType, geyserParticlePayload.waterBlocks)
            }

            in ParticleOptions.GEYSER_BASE_PARTICLE_TYPES -> {
                val geyserBaseParticlePayload = input.payload(
                    GEYSER_BASE,
                    GeyserBaseParticlePayload.serializer(),
                )
                ParticleOptions.GeyserBase(
                    particleType,
                    geyserBaseParticlePayload.waterBlocks,
                    geyserBaseParticlePayload.burstImpulseBase,
                )
            }

            ParticleType.DRAGON_BREATH -> {
                val powerParticlePayload = input.payload(POWER, PowerParticlePayload.serializer())
                ParticleOptions.Power(powerParticlePayload.power)
            }

            ParticleType.DUST -> {
                val dustParticlePayload = input.payload(DUST, DustParticlePayload.serializer())
                ParticleOptions.Dust(dustParticlePayload.color, dustParticlePayload.scale)
            }

            ParticleType.DUST_COLOR_TRANSITION -> {
                val dustTransitionParticlePayload = input.payload(
                    DUST_TRANSITION,
                    DustTransitionParticlePayload.serializer(),
                )
                ParticleOptions.DustTransition(
                    dustTransitionParticlePayload.fromColor,
                    dustTransitionParticlePayload.toColor,
                    dustTransitionParticlePayload.scale,
                )
            }

            ParticleType.EFFECT, ParticleType.INSTANT_EFFECT -> {
                val spellParticlePayload = input.payload(SPELL, SpellParticlePayload.serializer())
                ParticleOptions.Spell(particleType, spellParticlePayload.color, spellParticlePayload.power)
            }

            in ParticleOptions.COLOR_PARTICLE_TYPES -> {
                val colorParticlePayload = input.payload(COLOR, ColorParticlePayload.serializer())
                ParticleOptions.Color(particleType, colorParticlePayload.color)
            }

            ParticleType.SCULK_CHARGE -> {
                val sculkChargeParticlePayload = input.payload(
                    SCULK_CHARGE,
                    SculkChargeParticlePayload.serializer(),
                )
                ParticleOptions.SculkCharge(sculkChargeParticlePayload.roll)
            }

            ParticleType.ITEM -> {
                val itemParticlePayload = input.payload(ITEM, ItemParticlePayload.serializer())
                ParticleOptions.Item(itemParticlePayload.item)
            }

            ParticleType.VIBRATION -> {
                val vibrationParticlePayload = input.payload(
                    VIBRATION,
                    VibrationParticlePayload.serializer(),
                )
                ParticleOptions.Vibration(vibrationParticlePayload.destination, vibrationParticlePayload.arrivalInTicks)
            }

            ParticleType.TRAIL -> {
                val trailParticlePayload = input.payload(TRAIL, TrailParticlePayload.serializer())
                ParticleOptions.Trail(trailParticlePayload.target, trailParticlePayload.color, trailParticlePayload.duration)
            }

            ParticleType.SHRIEK -> {
                val shriekParticlePayload = input.payload(SHRIEK, ShriekParticlePayload.serializer())
                ParticleOptions.Shriek(shriekParticlePayload.delay)
            }

            else -> ParticleOptions.Simple(particleType)
        }
        input.endStructure(descriptor)
        return result
    }

    private fun <T> CompositeEncoder.payload(
        index: Int,
        kSerializer: KSerializer<T>,
        value: T,
    ) {
        encodeSerializableElement(descriptor, index, kSerializer, value)
    }

    private fun <T> CompositeDecoder.payload(
        index: Int,
        kSerializer: KSerializer<T>,
    ): T = decodeSerializableElement(descriptor, index, kSerializer)

    private const val TYPE: Int = 0
    private const val BLOCK: Int = 1
    private const val GEYSER: Int = 2
    private const val GEYSER_BASE: Int = 3
    private const val POWER: Int = 4
    private const val DUST: Int = 5
    private const val DUST_TRANSITION: Int = 6
    private const val SPELL: Int = 7
    private const val COLOR: Int = 8
    private const val SCULK_CHARGE: Int = 9
    private const val ITEM: Int = 10
    private const val VIBRATION: Int = 11
    private const val TRAIL: Int = 12
    private const val SHRIEK: Int = 13
}
