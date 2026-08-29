@file:UseSerializers(
    NbtBlockPositionSerializer::class,
    NbtEntityRotationSerializer::class,
    NbtEntityVector3dSerializer::class,
    NbtUuidSerializer::class,
)

package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtTag
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.uuid.Uuid

/**
 * The complete top-level player NBT written by the repository-selected release.
 *
 * Registry-dependent payloads such as Item Stacks, effects, equipment, passengers, and tracked Ender Pearls remain
 * raw NBT compounds. Their enclosing file structure and all registry-independent state remain strongly typed.
 */
@Serializable
data class PlayerData(
    @SerialName("Pos")
    val position: EntityVector3d,
    @SerialName("Motion")
    val motion: EntityVector3d,
    @SerialName("Rotation")
    val entityRotation: EntityRotation,
    @SerialName("fall_distance")
    val fallDistance: Double,
    @SerialName("Fire")
    val remainingFireTicks: Short,
    @SerialName("Air")
    val airSupply: Short,
    @SerialName("OnGround")
    val onGround: Boolean,
    @SerialName("Invulnerable")
    val invulnerable: Boolean,
    @SerialName("PortalCooldown")
    val portalCooldown: Int,
    @SerialName("UUID")
    val uuid: Uuid,
    @SerialName("CustomName")
    val customName: NbtTag? = null,
    @SerialName("CustomNameVisible")
    val customNameVisible: Boolean = false,
    @SerialName("Silent")
    val silent: Boolean = false,
    @SerialName("NoGravity")
    val noGravity: Boolean = false,
    @SerialName("Glowing")
    val glowing: Boolean = false,
    @SerialName("TicksFrozen")
    val ticksFrozen: Int = 0,
    @SerialName("HasVisualFire")
    val hasVisualFire: Boolean = false,
    @SerialName("Tags")
    val tags: List<String> = emptyList(),
    @SerialName("data")
    val customData: NbtCompound? = null,
    @SerialName("Passengers")
    val passengers: List<NbtCompound> = emptyList(),
    @SerialName("Health")
    val health: Float,
    @SerialName("HurtTime")
    val hurtTime: Short,
    @SerialName("DeathTime")
    val deathTime: Short,
    @SerialName("AbsorptionAmount")
    val absorptionAmount: Float,
    @SerialName("current_impulse_context_reset_grace_time")
    val currentImpulseContextResetGraceTime: Int,
    @SerialName("current_explosion_impact_pos")
    val currentExplosionImpactPosition: EntityVector3d? = null,
    val attributes: List<Attribute>,
    @SerialName("active_effects")
    val activeEffects: List<NbtCompound> = emptyList(),
    @SerialName("FallFlying")
    val fallFlying: Boolean,
    @SerialName("sleeping_pos")
    val sleepingPosition: BlockPosition? = null,
    @SerialName("Brain")
    val brain: NbtCompound,
    @SerialName("last_hurt_by_player")
    val lastHurtByPlayer: Uuid? = null,
    @SerialName("last_hurt_by_player_memory_time")
    val lastHurtByPlayerMemoryTime: Int? = null,
    @SerialName("last_hurt_by_mob")
    val lastHurtByMob: Uuid? = null,
    @SerialName("ticks_since_last_hurt_by_mob")
    val ticksSinceLastHurtByMob: Int? = null,
    val equipment: NbtCompound? = null,
    @SerialName("locator_bar_icon")
    val locatorBarIcon: NbtTag? = null,
    @SerialName("DataVersion")
    val dataVersion: Int,
    @SerialName("Inventory")
    val inventory: List<NbtCompound>,
    @SerialName("SelectedItemSlot")
    val selectedItemSlot: Int,
    @SerialName("SleepTimer")
    val sleepTimer: Short,
    @SerialName("XpP")
    val experienceProgress: Float,
    @SerialName("XpLevel")
    val experienceLevel: Int,
    @SerialName("XpTotal")
    val totalExperience: Int,
    @SerialName("XpSeed")
    val enchantmentSeed: Int,
    @SerialName("Score")
    val score: Int,
    val foodLevel: Int,
    val foodTickTimer: Int,
    val foodSaturationLevel: Float,
    val foodExhaustionLevel: Float,
    val abilities: Abilities,
    @SerialName("EnderItems")
    val enderItems: List<NbtCompound>,
    @SerialName("LastDeathLocation")
    val lastDeathLocation: GlobalPosition? = null,
    @SerialName("warden_spawn_tracker")
    val wardenSpawnTracker: WardenSpawnTracker,
    val playerGameType: Int,
    val previousPlayerGameType: Int? = null,
    val seenCredits: Boolean,
    @SerialName("entered_nether_pos")
    val enteredNetherPosition: EntityVector3d? = null,
    @SerialName("last_explosion_impact_pos")
    val lastExplosionImpactPosition: EntityVector3d? = null,
    @SerialName("RootVehicle")
    val rootVehicle: RootVehicle? = null,
    val recipeBook: RecipeBook,
    @SerialName("Dimension")
    val dimension: String,
    val respawn: Respawn? = null,
    @SerialName("spawn_extra_particles_on_fall")
    val spawnExtraParticlesOnFall: Boolean,
    @SerialName("raid_omen_position")
    val raidOmenPosition: BlockPosition? = null,
    @SerialName("ender_pearls")
    val enderPearls: List<NbtCompound> = emptyList(),
    @SerialName("ShoulderEntityLeft")
    val shoulderEntityLeft: NbtCompound? = null,
    @SerialName("ShoulderEntityRight")
    val shoulderEntityRight: NbtCompound? = null,
) {
    init {
        require(dimension.isNotBlank()) { "A Player dimension must not be blank" }
    }

    /** One selected-release persisted Attribute instance. */
    @Serializable
    data class Attribute(
        val id: String,
        @SerialName("base")
        val baseValue: Double,
        val modifiers: List<Modifier> = emptyList(),
    ) {
        init {
            require(id.isNotBlank()) { "A Player Attribute identifier must not be blank" }
        }
    }

    @Serializable
    data class Modifier(
        val id: String,
        val amount: Double,
        val operation: AttributeOperation,
    ) {
        init {
            require(id.isNotBlank()) { "A Player Attribute Modifier identifier must not be blank" }
        }
    }

    @Serializable
    enum class AttributeOperation {
        @SerialName("add_value")
        ADD_VALUE,

        @SerialName("add_multiplied_base")
        ADD_MULTIPLIED_BASE,

        @SerialName("add_multiplied_total")
        ADD_MULTIPLIED_TOTAL,
    }

    @Serializable
    data class Abilities(
        val invulnerable: Boolean,
        val flying: Boolean,
        @SerialName("mayfly")
        val mayFly: Boolean,
        val instabuild: Boolean,
        val mayBuild: Boolean,
        @SerialName("flySpeed")
        val flyingSpeed: Float,
        @SerialName("walkSpeed")
        val walkingSpeed: Float,
    )

    @Serializable
    data class GlobalPosition(
        val dimension: String,
        @SerialName("pos")
        val blockPosition: BlockPosition,
    ) {
        init {
            require(dimension.isNotBlank()) { "A global position dimension must not be blank" }
        }
    }

    @Serializable
    data class WardenSpawnTracker(
        @SerialName("ticks_since_last_warning")
        val ticksSinceLastWarning: Int,
        @SerialName("warning_level")
        val warningLevel: Int,
        @SerialName("cooldown_ticks")
        val cooldownTicks: Int,
    ) {
        init {
            require(ticksSinceLastWarning >= 0) { "Warden warning elapsed ticks must not be negative" }
            require(warningLevel >= 0) { "Warden warning level must not be negative" }
            require(cooldownTicks >= 0) { "Warden warning cooldown must not be negative" }
        }
    }

    @Serializable
    data class RootVehicle(
        @SerialName("Attach")
        val attachedEntityUuid: Uuid,
        @SerialName("Entity")
        val entity: NbtCompound,
    )

    @Serializable
    data class RecipeBook(
        val isGuiOpen: Boolean = false,
        val isFilteringCraftable: Boolean = false,
        val isFurnaceGuiOpen: Boolean = false,
        val isFurnaceFilteringCraftable: Boolean = false,
        val isBlastingFurnaceGuiOpen: Boolean = false,
        val isBlastingFurnaceFilteringCraftable: Boolean = false,
        val isSmokerGuiOpen: Boolean = false,
        val isSmokerFilteringCraftable: Boolean = false,
        val recipes: List<String>,
        val toBeDisplayed: List<String>,
    )

    @Serializable
    data class Respawn(
        val dimension: String,
        @SerialName("pos")
        val blockPosition: BlockPosition,
        val yaw: Float,
        val pitch: Float,
        val forced: Boolean = false,
    ) {
        init {
            require(dimension.isNotBlank()) { "A Player respawn dimension must not be blank" }
            require(yaw in -180.0f..180.0f) { "Player respawn yaw must be in -180..180" }
            require(pitch in -90.0f..90.0f) { "Player respawn pitch must be in -90..90" }
        }
    }
}
