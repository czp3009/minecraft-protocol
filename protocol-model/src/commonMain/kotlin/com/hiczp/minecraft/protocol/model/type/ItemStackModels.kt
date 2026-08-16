@file:OptIn(
    ExperimentalSerializationApi::class,
    InternalDataComponentRegistryApi::class,
)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtList
import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The selected target's `minecraft:data_component_type` registry in
 * protocol-ID order.
 */
@Serializable
enum class DataComponentType(
    val wireName: String,
) {
    CUSTOM_DATA("minecraft:custom_data"),
    MAX_STACK_SIZE("minecraft:max_stack_size"),
    MAX_DAMAGE("minecraft:max_damage"),
    DAMAGE("minecraft:damage"),
    UNBREAKABLE("minecraft:unbreakable"),
    USE_EFFECTS("minecraft:use_effects"),
    CUSTOM_NAME("minecraft:custom_name"),
    MINIMUM_ATTACK_CHARGE("minecraft:minimum_attack_charge"),
    DAMAGE_TYPE("minecraft:damage_type"),
    ITEM_NAME("minecraft:item_name"),
    ITEM_MODEL("minecraft:item_model"),
    LORE("minecraft:lore"),
    RARITY("minecraft:rarity"),
    ENCHANTMENTS("minecraft:enchantments"),
    CAN_PLACE_ON("minecraft:can_place_on"),
    CAN_BREAK("minecraft:can_break"),
    ATTRIBUTE_MODIFIERS("minecraft:attribute_modifiers"),
    CUSTOM_MODEL_DATA("minecraft:custom_model_data"),
    TOOLTIP_DISPLAY("minecraft:tooltip_display"),
    REPAIR_COST("minecraft:repair_cost"),
    CREATIVE_SLOT_LOCK("minecraft:creative_slot_lock"),
    ENCHANTMENT_GLINT_OVERRIDE("minecraft:enchantment_glint_override"),
    INTANGIBLE_PROJECTILE("minecraft:intangible_projectile"),
    FOOD("minecraft:food"),
    CONSUMABLE("minecraft:consumable"),
    USE_REMAINDER("minecraft:use_remainder"),
    USE_COOLDOWN("minecraft:use_cooldown"),
    DAMAGE_RESISTANT("minecraft:damage_resistant"),
    TOOL("minecraft:tool"),
    WEAPON("minecraft:weapon"),
    ATTACK_RANGE("minecraft:attack_range"),
    ENCHANTABLE("minecraft:enchantable"),
    EQUIPPABLE("minecraft:equippable"),
    REPAIRABLE("minecraft:repairable"),
    GLIDER("minecraft:glider"),
    TOOLTIP_STYLE("minecraft:tooltip_style"),
    DEATH_PROTECTION("minecraft:death_protection"),
    BLOCKS_ATTACKS("minecraft:blocks_attacks"),
    PIERCING_WEAPON("minecraft:piercing_weapon"),
    KINETIC_WEAPON("minecraft:kinetic_weapon"),
    SWING_ANIMATION("minecraft:swing_animation"),
    ADDITIONAL_TRADE_COST("minecraft:additional_trade_cost"),
    STORED_ENCHANTMENTS("minecraft:stored_enchantments"),
    DYE("minecraft:dye"),
    DYED_COLOR("minecraft:dyed_color"),
    MAP_COLOR("minecraft:map_color"),
    MAP_ID("minecraft:map_id"),
    MAP_DECORATIONS("minecraft:map_decorations"),
    MAP_POST_PROCESSING("minecraft:map_post_processing"),
    CHARGED_PROJECTILES("minecraft:charged_projectiles"),
    BUNDLE_CONTENTS("minecraft:bundle_contents"),
    POTION_CONTENTS("minecraft:potion_contents"),
    POTION_DURATION_SCALE("minecraft:potion_duration_scale"),
    SUSPICIOUS_STEW_EFFECTS("minecraft:suspicious_stew_effects"),
    WRITABLE_BOOK_CONTENT("minecraft:writable_book_content"),
    WRITTEN_BOOK_CONTENT("minecraft:written_book_content"),
    TRIM("minecraft:trim"),
    DEBUG_STICK_STATE("minecraft:debug_stick_state"),
    ENTITY_DATA("minecraft:entity_data"),
    BUCKET_ENTITY_DATA("minecraft:bucket_entity_data"),
    BLOCK_ENTITY_DATA("minecraft:block_entity_data"),
    INSTRUMENT("minecraft:instrument"),
    PROVIDES_TRIM_MATERIAL("minecraft:provides_trim_material"),
    OMINOUS_BOTTLE_AMPLIFIER("minecraft:ominous_bottle_amplifier"),
    JUKEBOX_PLAYABLE("minecraft:jukebox_playable"),
    PROVIDES_BANNER_PATTERNS("minecraft:provides_banner_patterns"),
    RECIPES("minecraft:recipes"),
    LODESTONE_TRACKER("minecraft:lodestone_tracker"),
    FIREWORK_EXPLOSION("minecraft:firework_explosion"),
    FIREWORKS("minecraft:fireworks"),
    PROFILE("minecraft:profile"),
    NOTE_BLOCK_SOUND("minecraft:note_block_sound"),
    BANNER_PATTERNS("minecraft:banner_patterns"),
    BASE_COLOR("minecraft:base_color"),
    POT_DECORATIONS("minecraft:pot_decorations"),
    CONTAINER("minecraft:container"),
    BLOCK_STATE("minecraft:block_state"),
    BEES("minecraft:bees"),
    SULFUR_CUBE_CONTENT("minecraft:sulfur_cube_content"),
    LOCK("minecraft:lock"),
    CONTAINER_LOOT("minecraft:container_loot"),
    BREAK_SOUND("minecraft:break_sound"),
    VILLAGER_VARIANT("minecraft:villager/variant"),
    WOLF_VARIANT("minecraft:wolf/variant"),
    WOLF_SOUND_VARIANT("minecraft:wolf/sound_variant"),
    WOLF_COLLAR("minecraft:wolf/collar"),
    FOX_VARIANT("minecraft:fox/variant"),
    SALMON_SIZE("minecraft:salmon/size"),
    PARROT_VARIANT("minecraft:parrot/variant"),
    TROPICAL_FISH_PATTERN("minecraft:tropical_fish/pattern"),
    TROPICAL_FISH_BASE_COLOR("minecraft:tropical_fish/base_color"),
    TROPICAL_FISH_PATTERN_COLOR("minecraft:tropical_fish/pattern_color"),
    MOOSHROOM_VARIANT("minecraft:mooshroom/variant"),
    RABBIT_VARIANT("minecraft:rabbit/variant"),
    PIG_VARIANT("minecraft:pig/variant"),
    PIG_SOUND_VARIANT("minecraft:pig/sound_variant"),
    COW_VARIANT("minecraft:cow/variant"),
    COW_SOUND_VARIANT("minecraft:cow/sound_variant"),
    CHICKEN_VARIANT("minecraft:chicken/variant"),
    CHICKEN_SOUND_VARIANT("minecraft:chicken/sound_variant"),
    ZOMBIE_NAUTILUS_VARIANT("minecraft:zombie_nautilus/variant"),
    FROG_VARIANT("minecraft:frog/variant"),
    HORSE_VARIANT("minecraft:horse/variant"),
    PAINTING_VARIANT("minecraft:painting/variant"),
    LLAMA_VARIANT("minecraft:llama/variant"),
    AXOLOTL_VARIANT("minecraft:axolotl/variant"),
    CAT_VARIANT("minecraft:cat/variant"),
    CAT_SOUND_VARIANT("minecraft:cat/sound_variant"),
    CAT_COLLAR("minecraft:cat/collar"),
    SHEEP_COLOR("minecraft:sheep/color"),
    SHULKER_COLOR("minecraft:shulker/color"),
    ;

    val protocolId: Int
        get() = ordinal

    val identifier: Identifier
        get() = Identifier(wireName)

    companion object {
        fun fromProtocolId(protocolId: Int): DataComponentType? =
            entries.getOrNull(protocolId)
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
internal annotation class DataComponentInfo(val type: DataComponentType)

@RequiresOptIn(
    message = "Generated data-component serializer dispatch is an internal protocol implementation API.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
annotation class InternalDataComponentRegistryApi

@Serializable(with = DataComponentSerializer::class)
sealed interface DataComponent {
    @Serializable
    @DataComponentInfo(DataComponentType.CUSTOM_DATA)
    data class CustomData(
        @NetworkNbt
        val data: NbtCompound,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MAX_STACK_SIZE)
    data class MaxStackSize(
        @VarInt
        val value: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MAX_DAMAGE)
    data class MaxDamage(
        @VarInt
        val value: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.DAMAGE)
    data class Damage(
        @VarInt
        val value: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.UNBREAKABLE)
    data object Unbreakable : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.USE_EFFECTS)
    data class UseEffects(
        val canSprint: Boolean,
        val interactVibrations: Boolean,
        val speedMultiplier: Float,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CUSTOM_NAME)
    data class CustomName(
        val name: TextComponent,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MINIMUM_ATTACK_CHARGE)
    data class MinimumAttackCharge(
        val value: Float,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.DAMAGE_TYPE)
    data class DamageType(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ITEM_NAME)
    data class ItemName(
        val name: TextComponent,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ITEM_MODEL)
    data class ItemModel(
        val model: Identifier,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.LORE)
    data class Lore(
        @MaxCollectionSize(256)
        val lines: List<TextComponent>,
    ) : DataComponent

    @Serializable
    enum class RarityValue {
        COMMON,
        UNCOMMON,
        RARE,
        EPIC,
    }

    @Serializable
    @DataComponentInfo(DataComponentType.RARITY)
    data class Rarity(
        @ZeroFallbackEnum
        val value: RarityValue,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ENCHANTMENTS)
    data class Enchantments(
        @VarIntElements
        val levelsByEnchantmentId: Map<Int, Int>,
    ) : DataComponent {
        init {
            require(levelsByEnchantmentId.values.all { it in 0..255 }) {
                "Enchantment levels must be in 0..255"
            }
        }
    }

    @Serializable
    @DataComponentInfo(DataComponentType.CAN_PLACE_ON)
    data class CanPlaceOn(
        val predicate: AdventureModePredicate,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CAN_BREAK)
    data class CanBreak(
        val predicate: AdventureModePredicate,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ATTRIBUTE_MODIFIERS)
    data class AttributeModifiers(
        val modifiers: List<ItemAttributeModifier>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CUSTOM_MODEL_DATA)
    data class CustomModelData(
        val floats: List<Float> = emptyList(),
        val flags: List<Boolean> = emptyList(),
        val strings: List<String> = emptyList(),
        val colors: List<Int> = emptyList(),
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.TOOLTIP_DISPLAY)
    data class TooltipDisplay(
        val hideTooltip: Boolean,
        val hiddenComponents: Set<DataComponentType>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.REPAIR_COST)
    data class RepairCost(
        @VarInt
        val value: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CREATIVE_SLOT_LOCK)
    data object CreativeSlotLock : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ENCHANTMENT_GLINT_OVERRIDE)
    data class EnchantmentGlintOverride(
        val enabled: Boolean,
    ) : DataComponent

    /**
     * Components without an explicit vanilla stream codec use their persistent
     * Codec through network NBT. The raw NBT value is the physical wire model.
     */
    @Serializable
    @DataComponentInfo(DataComponentType.INTANGIBLE_PROJECTILE)
    data class IntangibleProjectile(
        @NetworkNbt
        val data: NbtCompound = NbtCompound(emptyMap()),
    ) : DataComponent {
        init {
            require(data.value.isEmpty()) {
                "An intangible-projectile component must contain an empty compound"
            }
        }
    }

    @Serializable
    @DataComponentInfo(DataComponentType.FOOD)
    data class Food(
        @VarInt
        val nutrition: Int,
        val saturation: Float,
        val canAlwaysEat: Boolean,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CONSUMABLE)
    data class Consumable(
        val consumeSeconds: Float,
        @ZeroFallbackEnum
        val animation: ItemUseAnimation,
        val sound: SoundEventHolder,
        val hasConsumeParticles: Boolean,
        val onConsumeEffects: List<ConsumeEffect>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.USE_REMAINDER)
    data class UseRemainder(
        val convertInto: ItemStackTemplate,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.USE_COOLDOWN)
    data class UseCooldown(
        val seconds: Float,
        val cooldownGroup: Identifier? = null,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.DAMAGE_RESISTANT)
    data class DamageResistant(
        val types: RegistryHolderSet,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.TOOL)
    data class Tool(
        val rules: List<ToolRule>,
        val defaultMiningSpeed: Float,
        @VarInt
        val damagePerBlock: Int,
        val canDestroyBlocksInCreative: Boolean,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.WEAPON)
    data class Weapon(
        @VarInt
        val itemDamagePerAttack: Int,
        val disableBlockingForSeconds: Float,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ATTACK_RANGE)
    data class AttackRange(
        val minimumReach: Float,
        val maximumReach: Float,
        val minimumCreativeReach: Float,
        val maximumCreativeReach: Float,
        val hitboxMargin: Float,
        val mobFactor: Float,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ENCHANTABLE)
    data class Enchantable(
        @VarInt
        val value: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.EQUIPPABLE)
    data class Equippable(
        @ZeroFallbackEnum
        val slot: EquipmentSlot,
        val equipSound: SoundEventHolder,
        val assetId: Identifier? = null,
        val cameraOverlay: Identifier? = null,
        val allowedEntities: RegistryHolderSet? = null,
        val dispensable: Boolean,
        val swappable: Boolean,
        val damageOnHurt: Boolean,
        val equipOnInteract: Boolean,
        val canBeSheared: Boolean,
        val shearingSound: SoundEventHolder,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.REPAIRABLE)
    data class Repairable(
        val items: RegistryHolderSet,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.GLIDER)
    data object Glider : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.TOOLTIP_STYLE)
    data class TooltipStyle(
        val style: Identifier,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.DEATH_PROTECTION)
    data class DeathProtection(
        val effects: List<ConsumeEffect>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BLOCKS_ATTACKS)
    data class BlocksAttacks(
        val blockDelaySeconds: Float,
        val disableCooldownScale: Float,
        val damageReductions: List<BlocksAttackDamageReduction>,
        val itemDamage: ItemDamageFunction,
        val bypassedBy: RegistryHolderSet? = null,
        val blockSound: SoundEventHolder? = null,
        val disableSound: SoundEventHolder? = null,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PIERCING_WEAPON)
    data class PiercingWeapon(
        val dealsKnockback: Boolean,
        val dismounts: Boolean,
        val sound: SoundEventHolder? = null,
        val hitSound: SoundEventHolder? = null,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.KINETIC_WEAPON)
    data class KineticWeapon(
        @VarInt
        val contactCooldownTicks: Int,
        @VarInt
        val delayTicks: Int,
        val dismountConditions: KineticWeaponCondition? = null,
        val knockbackConditions: KineticWeaponCondition? = null,
        val damageConditions: KineticWeaponCondition? = null,
        val forwardMovement: Float,
        val damageMultiplier: Float,
        val sound: SoundEventHolder? = null,
        val hitSound: SoundEventHolder? = null,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.SWING_ANIMATION)
    data class SwingAnimation(
        @ZeroFallbackEnum
        val type: SwingAnimationType,
        @VarInt
        val duration: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ADDITIONAL_TRADE_COST)
    data class AdditionalTradeCost(
        @VarInt
        val value: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.STORED_ENCHANTMENTS)
    data class StoredEnchantments(
        @VarIntElements
        val levelsByEnchantmentId: Map<Int, Int>,
    ) : DataComponent {
        init {
            require(levelsByEnchantmentId.values.all { it in 0..255 }) {
                "Enchantment levels must be in 0..255"
            }
        }
    }

    @Serializable
    @DataComponentInfo(DataComponentType.DYE)
    data class Dye(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.DYED_COLOR)
    data class DyedColor(
        val rgb: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MAP_COLOR)
    data class MapColor(
        val rgb: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MAP_ID)
    data class MapId(
        @VarInt
        val id: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MAP_DECORATIONS)
    data class MapDecorations(
        @NetworkNbt
        val data: NbtCompound,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MAP_POST_PROCESSING)
    data class MapPostProcessingValue(
        @ZeroFallbackEnum
        val operation: MapPostProcessing,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CHARGED_PROJECTILES)
    data class ChargedProjectiles(
        @MaxCollectionSize(1024)
        val projectiles: List<ItemStackTemplate>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BUNDLE_CONTENTS)
    data class BundleContents(
        val items: List<ItemStackTemplate>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.POTION_CONTENTS)
    data class PotionContents(
        @VarInt
        val potionRegistryId: Int? = null,
        val customColor: Int? = null,
        val customEffects: List<MobEffectInstance> = emptyList(),
        val customName: String? = null,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.POTION_DURATION_SCALE)
    data class PotionDurationScale(
        val value: Float,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.SUSPICIOUS_STEW_EFFECTS)
    data class SuspiciousStewEffects(
        val effects: List<SuspiciousStewEffect>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.WRITABLE_BOOK_CONTENT)
    data class WritableBookContent(
        @MaxCollectionSize(100)
        val pages: List<WritableBookPage>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.WRITTEN_BOOK_CONTENT)
    data class WrittenBookContent(
        val title: WrittenBookTitle,
        val author: String,
        @VarInt
        val generation: Int,
        val pages: List<WrittenBookPage>,
        val resolved: Boolean,
    ) : DataComponent {
        init {
            require(generation in 0..3) {
                "A written-book generation must be in 0..3"
            }
        }
    }

    @Serializable
    @DataComponentInfo(DataComponentType.TRIM)
    data class Trim(
        val material: TrimMaterialHolder,
        val pattern: TrimPatternHolder,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.DEBUG_STICK_STATE)
    data class DebugStickState(
        @NetworkNbt
        val data: NbtCompound,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ENTITY_DATA)
    data class EntityData(
        val value: TypedEntityData,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BUCKET_ENTITY_DATA)
    data class BucketEntityData(
        @NetworkNbt
        val data: NbtCompound,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BLOCK_ENTITY_DATA)
    data class BlockEntityData(
        val value: TypedEntityData,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.INSTRUMENT)
    data class Instrument(
        val instrument: InstrumentHolder,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PROVIDES_TRIM_MATERIAL)
    data class ProvidesTrimMaterial(
        val material: TrimMaterialHolder,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.OMINOUS_BOTTLE_AMPLIFIER)
    data class OminousBottleAmplifier(
        @VarInt
        val value: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.JUKEBOX_PLAYABLE)
    data class JukeboxPlayable(
        val song: JukeboxSongHolder,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PROVIDES_BANNER_PATTERNS)
    data class ProvidesBannerPatterns(
        val patterns: RegistryHolderSet,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.RECIPES)
    data class Recipes(
        @NetworkNbt
        val data: NbtList,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.LODESTONE_TRACKER)
    data class LodestoneTracker(
        val target: GlobalPosition? = null,
        val tracked: Boolean,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.FIREWORK_EXPLOSION)
    data class FireworkExplosionValue(
        val explosion: FireworkExplosion,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.FIREWORKS)
    data class Fireworks(
        @VarInt
        val flightDuration: Int,
        @MaxCollectionSize(256)
        val explosions: List<FireworkExplosion>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PROFILE)
    data class Profile(
        val identity: ProfileIdentity,
        val skin: PlayerSkinPatch = PlayerSkinPatch(),
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.NOTE_BLOCK_SOUND)
    data class NoteBlockSound(
        val sound: Identifier,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BANNER_PATTERNS)
    data class BannerPatterns(
        val layers: List<BannerPatternLayer>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BASE_COLOR)
    data class BaseColor(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.POT_DECORATIONS)
    data class PotDecorations(
        @MaxCollectionSize(4)
        @VarIntElements
        val itemRegistryIds: List<Int>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CONTAINER)
    data class Container(
        @MaxCollectionSize(256)
        val items: List<ItemStackTemplate?>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BLOCK_STATE)
    data class BlockState(
        val properties: Map<String, String>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BEES)
    data class Bees(
        val occupants: List<BeeOccupant>,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.SULFUR_CUBE_CONTENT)
    data class SulfurCubeContent(
        val absorbedBlock: ItemStackTemplate,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.LOCK)
    data class Lock(
        @NetworkNbt
        val predicate: NbtCompound,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CONTAINER_LOOT)
    data class ContainerLoot(
        @NetworkNbt
        val data: NbtCompound,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.BREAK_SOUND)
    data class BreakSound(
        val sound: SoundEventHolder,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.VILLAGER_VARIANT)
    data class VillagerVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.WOLF_VARIANT)
    data class WolfVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.WOLF_SOUND_VARIANT)
    data class WolfSoundVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.WOLF_COLLAR)
    data class WolfCollar(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.FOX_VARIANT)
    data class FoxVariantValue(
        @ZeroFallbackEnum
        val variant: FoxVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.SALMON_SIZE)
    data class SalmonSize(
        @ClampEnum
        val variant: SalmonVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PARROT_VARIANT)
    data class ParrotVariantValue(
        @ClampEnum
        val variant: ParrotVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.TROPICAL_FISH_PATTERN)
    data class TropicalFishPatternValue(
        @VarInt
        val pattern: TropicalFishPattern,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.TROPICAL_FISH_BASE_COLOR)
    data class TropicalFishBaseColor(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.TROPICAL_FISH_PATTERN_COLOR)
    data class TropicalFishPatternColor(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.MOOSHROOM_VARIANT)
    data class MooshroomVariantValue(
        @ClampEnum
        val variant: MooshroomVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.RABBIT_VARIANT)
    data class RabbitVariantValue(
        @VarInt
        val variant: RabbitVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PIG_VARIANT)
    data class PigVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PIG_SOUND_VARIANT)
    data class PigSoundVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.COW_VARIANT)
    data class CowVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.COW_SOUND_VARIANT)
    data class CowSoundVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CHICKEN_VARIANT)
    data class ChickenVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CHICKEN_SOUND_VARIANT)
    data class ChickenSoundVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.ZOMBIE_NAUTILUS_VARIANT)
    data class ZombieNautilusVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.FROG_VARIANT)
    data class FrogVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.HORSE_VARIANT)
    data class HorseVariantValue(
        @WrappedEnum
        val variant: HorseVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.PAINTING_VARIANT)
    data class PaintingVariant(
        val variant: PaintingVariantHolder,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.LLAMA_VARIANT)
    data class LlamaVariantValue(
        @ClampEnum
        val variant: LlamaVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.AXOLOTL_VARIANT)
    data class AxolotlVariantValue(
        @ZeroFallbackEnum
        val variant: AxolotlVariant,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CAT_VARIANT)
    data class CatVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CAT_SOUND_VARIANT)
    data class CatSoundVariant(
        @VarInt
        val registryId: Int,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.CAT_COLLAR)
    data class CatCollar(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.SHEEP_COLOR)
    data class SheepColor(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent

    @Serializable
    @DataComponentInfo(DataComponentType.SHULKER_COLOR)
    data class ShulkerColor(
        @ZeroFallbackEnum
        val color: DyeColor,
    ) : DataComponent
}

@Serializable(with = DataComponentPatchSerializer::class)
data class DataComponentPatch(
    val added: List<DataComponent> = emptyList(),
    val removed: Set<DataComponentType> = emptySet(),
) {
    companion object {
        val EMPTY: DataComponentPatch = DataComponentPatch()
    }
}

@Serializable(with = ItemStackSerializer::class)
sealed interface ItemStack {
    @Serializable
    data object Empty : ItemStack

    @Serializable
    data class Present(
        val count: Int,
        val itemId: Int,
        val components: DataComponentPatch = DataComponentPatch.EMPTY,
    ) : ItemStack {
        init {
            require(count > 0) { "A present item stack must have a positive count" }
        }
    }

    companion object {
        val EMPTY: ItemStack = Empty

        fun of(
            itemId: Int,
            count: Int = 1,
            components: DataComponentPatch = DataComponentPatch.EMPTY,
        ): ItemStack = Present(count, itemId, components)
    }
}

@Serializable(with = ItemStackTemplateSerializer::class)
data class ItemStackTemplate(
    val itemId: Int,
    val count: Int = 1,
    val components: DataComponentPatch = DataComponentPatch.EMPTY,
) {
    init {
        require(count > 0) { "An item stack template must have a positive count" }
    }
}

internal object DataComponentSerializer :
    DataComponentSerializerBase(delimitedValue = false)

internal object DelimitedDataComponentSerializer :
    DataComponentSerializerBase(delimitedValue = true)

internal abstract class DataComponentSerializerBase(
    delimitedValue: Boolean,
) : KSerializer<DataComponent> {
    final override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        if (delimitedValue) {
            "minecraft.DelimitedDataComponent"
        } else {
            "minecraft.DataComponent"
        },
    ) {
        element<Int>("type", annotations = listOf(VarInt()))
        element(
            "value",
            buildClassSerialDescriptor("minecraft.DataComponentValue"),
            annotations = if (delimitedValue) {
                listOf(ByteLengthPrefixed())
            } else {
                emptyList()
            },
        )
    }

    final override fun serialize(encoder: Encoder, value: DataComponent) {
        val type = GeneratedDataComponentSerializers.type(value)
        val valueSerializer = GeneratedDataComponentSerializers.serializer(type)
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, TYPE, type.protocolId)
        @Suppress("UNCHECKED_CAST")
        output.encodeSerializableElement(
            descriptor,
            VALUE,
            valueSerializer as SerializationStrategy<DataComponent>,
            value,
        )
        output.endStructure(descriptor)
    }

    final override fun deserialize(decoder: Decoder): DataComponent {
        val input = decoder.beginStructure(descriptor)
        var type: DataComponentType? = null
        var value: DataComponent? = null
        if (input.decodeSequentially()) {
            type = decodeType(input.decodeIntElement(descriptor, TYPE))
            val valueSerializer = GeneratedDataComponentSerializers.serializer(type)
            @Suppress("UNCHECKED_CAST")
            value = input.decodeSerializableElement(
                descriptor,
                VALUE,
                valueSerializer as DeserializationStrategy<DataComponent>,
            )
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    TYPE -> type = decodeType(input.decodeIntElement(descriptor, TYPE))
                    VALUE -> {
                        val actualType = type ?: throw SerializationException(
                            "Data component type must precede its value",
                        )
                        @Suppress("UNCHECKED_CAST")
                        value = input.decodeSerializableElement(
                            descriptor,
                            VALUE,
                            GeneratedDataComponentSerializers.serializer(
                                actualType,
                            ) as
                                    DeserializationStrategy<DataComponent>,
                        )
                    }

                    -1 -> break
                    else -> throw SerializationException(
                        "Unexpected DataComponent field $index",
                    )
                }
            }
        }
        input.endStructure(descriptor)
        val result = value ?: throw SerializationException(
            "Missing data component value for ${type?.wireName ?: "unknown type"}",
        )
        if (GeneratedDataComponentSerializers.type(result) != type) {
            throw SerializationException("Data component type/value mismatch")
        }
        return result
    }

    private fun decodeType(protocolId: Int): DataComponentType {
        return DataComponentType.fromProtocolId(protocolId)
            ?: throw SerializationException(
                "Unknown data component type ID $protocolId",
            )
    }

    private companion object {
        const val TYPE: Int = 0
        const val VALUE: Int = 1
    }
}

internal object DataComponentPatchSerializer :
    DataComponentPatchSerializerBase(DataComponentSerializer)

internal object DelimitedDataComponentPatchSerializer :
    DataComponentPatchSerializerBase(DelimitedDataComponentSerializer)

internal abstract class DataComponentPatchSerializerBase(
    private val componentSerializer: KSerializer<DataComponent>,
) : KSerializer<DataComponentPatch> {
    final override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        if (componentSerializer === DelimitedDataComponentSerializer) {
            "minecraft.DelimitedDataComponentPatch"
        } else {
            "minecraft.DataComponentPatch"
        },
    ) {
        element<Int>("addedCount", annotations = listOf(VarInt()))
        element<Int>("removedCount", annotations = listOf(VarInt()))
        element("added", componentSerializer.descriptor, isOptional = true)
        element<Int>("removed", annotations = listOf(VarInt()), isOptional = true)
    }

    final override fun serialize(encoder: Encoder, value: DataComponentPatch) {
        val normalized = normalizePatch(value.added, value.removed)
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, ADDED_COUNT, normalized.added.size)
        output.encodeIntElement(descriptor, REMOVED_COUNT, normalized.removed.size)
        normalized.added.forEach { component ->
            output.encodeSerializableElement(
                descriptor,
                ADDED,
                componentSerializer,
                component,
            )
        }
        normalized.removed.forEach { type ->
            output.encodeIntElement(descriptor, REMOVED, type.protocolId)
        }
        output.endStructure(descriptor)
    }

    final override fun deserialize(decoder: Decoder): DataComponentPatch {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "DataComponentPatch requires ordered sequential decoding",
            )
        }
        val addedCount = input.decodeIntElement(descriptor, ADDED_COUNT)
        val removedCount = input.decodeIntElement(descriptor, REMOVED_COUNT)
        if (addedCount < 0 || removedCount < 0) {
            throw SerializationException(
                "Negative data component counts: $addedCount/$removedCount",
            )
        }
        val entries = linkedMapOf<DataComponentType, DataComponent?>()
        repeat(addedCount) {
            val component = input.decodeSerializableElement(
                descriptor,
                ADDED,
                componentSerializer,
            )
            entries[componentType(component)] = component
        }
        repeat(removedCount) {
            val protocolId = input.decodeIntElement(descriptor, REMOVED)
            val type = DataComponentType.fromProtocolId(protocolId)
                ?: throw SerializationException(
                    "Unknown removed data component type ID $protocolId",
                )
            entries[type] = null
        }
        input.endStructure(descriptor)
        return DataComponentPatch(
            added = entries.values.filterNotNull(),
            removed = entries.filterValues { it == null }.keys,
        )
    }

    private companion object {
        const val ADDED_COUNT: Int = 0
        const val REMOVED_COUNT: Int = 1
        const val ADDED: Int = 2
        const val REMOVED: Int = 3
    }
}

internal object ItemStackSerializer :
    ItemStackSerializerBase(
        patchSerializer = DataComponentPatchSerializer,
        validateUntrusted = false,
    )

internal object UntrustedItemStackSerializer :
    ItemStackSerializerBase(
        patchSerializer = DelimitedDataComponentPatchSerializer,
        validateUntrusted = true,
    )

internal abstract class ItemStackSerializerBase(
    private val patchSerializer: KSerializer<DataComponentPatch>,
    private val validateUntrusted: Boolean,
) : KSerializer<ItemStack> {
    final override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        if (validateUntrusted) {
            "minecraft.UntrustedItemStack"
        } else {
            "minecraft.ItemStack"
        },
    ) {
        element<Int>("count", annotations = listOf(VarInt()))
        element<Int>(
            "itemId",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element(
            "components",
            patchSerializer.descriptor,
            isOptional = true,
        )
    }

    final override fun serialize(encoder: Encoder, value: ItemStack) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            ItemStack.Empty -> output.encodeIntElement(descriptor, COUNT, 0)
            is ItemStack.Present -> {
                validateCount(value.count)
                output.encodeIntElement(descriptor, COUNT, value.count)
                output.encodeIntElement(descriptor, ITEM_ID, value.itemId)
                output.encodeSerializableElement(
                    descriptor,
                    COMPONENTS,
                    patchSerializer,
                    value.components,
                )
            }
        }
        output.endStructure(descriptor)
    }

    final override fun deserialize(decoder: Decoder): ItemStack {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException("ItemStack requires ordered decoding")
        }
        val count = input.decodeIntElement(descriptor, COUNT)
        val result = if (count <= 0) {
            ItemStack.Empty
        } else {
            validateCount(count)
            ItemStack.Present(
                count = count,
                itemId = input.decodeIntElement(descriptor, ITEM_ID),
                components = input.decodeSerializableElement(
                    descriptor,
                    COMPONENTS,
                    patchSerializer,
                ),
            )
        }
        input.endStructure(descriptor)
        return result
    }

    private fun validateCount(count: Int) {
        if (validateUntrusted && count !in 1..99) {
            throw SerializationException(
                "Untrusted item stack count must be in 1..99: $count",
            )
        }
    }

    private companion object {
        const val COUNT: Int = 0
        const val ITEM_ID: Int = 1
        const val COMPONENTS: Int = 2
    }
}

internal object ItemStackTemplateSerializer : KSerializer<ItemStackTemplate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ItemStackTemplate",
    ) {
        element<Int>("itemId", annotations = listOf(VarInt()))
        element<Int>("count", annotations = listOf(VarInt()))
        element("components", DataComponentPatchSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: ItemStackTemplate) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, ITEM_ID, value.itemId)
        output.encodeIntElement(descriptor, COUNT, value.count)
        output.encodeSerializableElement(
            descriptor,
            COMPONENTS,
            DataComponentPatchSerializer,
            value.components,
        )
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ItemStackTemplate {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "ItemStackTemplate requires ordered decoding",
            )
        }
        val result = ItemStackTemplate(
            itemId = input.decodeIntElement(descriptor, ITEM_ID),
            count = input.decodeIntElement(descriptor, COUNT),
            components = input.decodeSerializableElement(
                descriptor,
                COMPONENTS,
                DataComponentPatchSerializer,
            ),
        )
        input.endStructure(descriptor)
        return result
    }

    private const val ITEM_ID: Int = 0
    private const val COUNT: Int = 1
    private const val COMPONENTS: Int = 2
}

private fun componentType(value: DataComponent): DataComponentType =
    GeneratedDataComponentSerializers.type(value)

private fun normalizePatch(
    added: List<DataComponent>,
    removed: Set<DataComponentType>,
): DataComponentPatch {
    val entries = linkedMapOf<DataComponentType, DataComponent?>()
    added.forEach { entries[componentType(it)] = it }
    removed.forEach { entries[it] = null }
    return DataComponentPatch(
        added = entries.values.filterNotNull(),
        removed = entries.filterValues { it == null }.keys,
    )
}
