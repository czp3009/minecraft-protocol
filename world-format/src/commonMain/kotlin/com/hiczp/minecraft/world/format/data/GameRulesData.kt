package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of root `minecraft:game_rules` saved data for the repository-selected release. */
@Serializable
data class GameRulesData(
    @SerialName("minecraft:advance_time")
    val advanceTime: Boolean,
    @SerialName("minecraft:advance_weather")
    val advanceWeather: Boolean,
    @SerialName("minecraft:allow_entering_nether_using_portals")
    val allowEnteringNetherUsingPortals: Boolean,
    @SerialName("minecraft:block_drops")
    val blockDrops: Boolean,
    @SerialName("minecraft:block_explosion_drop_decay")
    val blockExplosionDropDecay: Boolean,
    @SerialName("minecraft:command_block_output")
    val commandBlockOutput: Boolean,
    @SerialName("minecraft:command_blocks_work")
    val commandBlocksWork: Boolean,
    @SerialName("minecraft:drowning_damage")
    val drowningDamage: Boolean,
    @SerialName("minecraft:elytra_movement_check")
    val elytraMovementCheck: Boolean,
    @SerialName("minecraft:ender_pearls_vanish_on_death")
    val enderPearlsVanishOnDeath: Boolean,
    @SerialName("minecraft:entity_drops")
    val entityDrops: Boolean,
    @SerialName("minecraft:fall_damage")
    val fallDamage: Boolean,
    @SerialName("minecraft:fire_damage")
    val fireDamage: Boolean,
    @SerialName("minecraft:fire_spread_radius_around_player")
    val fireSpreadRadiusAroundPlayer: Int,
    @SerialName("minecraft:forgive_dead_players")
    val forgiveDeadPlayers: Boolean,
    @SerialName("minecraft:freeze_damage")
    val freezeDamage: Boolean,
    @SerialName("minecraft:global_sound_events")
    val globalSoundEvents: Boolean,
    @SerialName("minecraft:immediate_respawn")
    val immediateRespawn: Boolean,
    @SerialName("minecraft:keep_inventory")
    val keepInventory: Boolean,
    @SerialName("minecraft:lava_source_conversion")
    val lavaSourceConversion: Boolean,
    @SerialName("minecraft:limited_crafting")
    val limitedCrafting: Boolean,
    @SerialName("minecraft:locator_bar")
    val locatorBar: Boolean,
    @SerialName("minecraft:log_admin_commands")
    val logAdminCommands: Boolean,
    @SerialName("minecraft:max_block_modifications")
    val maxBlockModifications: Int,
    @SerialName("minecraft:max_command_forks")
    val maxCommandForks: Int,
    @SerialName("minecraft:max_command_sequence_length")
    val maxCommandSequenceLength: Int,
    @SerialName("minecraft:max_entity_cramming")
    val maxEntityCramming: Int,
    @SerialName("minecraft:max_minecart_speed")
    val maxMinecartSpeed: Int? = null,
    @SerialName("minecraft:max_snow_accumulation_height")
    val maxSnowAccumulationHeight: Int,
    @SerialName("minecraft:mob_drops")
    val mobDrops: Boolean,
    @SerialName("minecraft:mob_explosion_drop_decay")
    val mobExplosionDropDecay: Boolean,
    @SerialName("minecraft:mob_griefing")
    val mobGriefing: Boolean,
    @SerialName("minecraft:natural_health_regeneration")
    val naturalHealthRegeneration: Boolean,
    @SerialName("minecraft:player_movement_check")
    val playerMovementCheck: Boolean,
    @SerialName("minecraft:players_nether_portal_creative_delay")
    val playersNetherPortalCreativeDelay: Int,
    @SerialName("minecraft:players_nether_portal_default_delay")
    val playersNetherPortalDefaultDelay: Int,
    @SerialName("minecraft:players_sleeping_percentage")
    val playersSleepingPercentage: Int,
    @SerialName("minecraft:projectiles_can_break_blocks")
    val projectilesCanBreakBlocks: Boolean,
    @SerialName("minecraft:pvp")
    val pvp: Boolean,
    @SerialName("minecraft:raids")
    val raids: Boolean,
    @SerialName("minecraft:random_tick_speed")
    val randomTickSpeed: Int,
    @SerialName("minecraft:reduced_debug_info")
    val reducedDebugInfo: Boolean,
    @SerialName("minecraft:respawn_radius")
    val respawnRadius: Int,
    @SerialName("minecraft:send_command_feedback")
    val sendCommandFeedback: Boolean,
    @SerialName("minecraft:show_advancement_messages")
    val showAdvancementMessages: Boolean,
    @SerialName("minecraft:show_death_messages")
    val showDeathMessages: Boolean,
    @SerialName("minecraft:spawn_mobs")
    val spawnMobs: Boolean,
    @SerialName("minecraft:spawn_monsters")
    val spawnMonsters: Boolean,
    @SerialName("minecraft:spawn_patrols")
    val spawnPatrols: Boolean,
    @SerialName("minecraft:spawn_phantoms")
    val spawnPhantoms: Boolean,
    @SerialName("minecraft:spawn_wandering_traders")
    val spawnWanderingTraders: Boolean,
    @SerialName("minecraft:spawn_wardens")
    val spawnWardens: Boolean,
    @SerialName("minecraft:spawner_blocks_work")
    val spawnerBlocksWork: Boolean,
    @SerialName("minecraft:spectators_generate_chunks")
    val spectatorsGenerateChunks: Boolean,
    @SerialName("minecraft:spread_vines")
    val spreadVines: Boolean,
    @SerialName("minecraft:tnt_explodes")
    val tntExplodes: Boolean,
    @SerialName("minecraft:tnt_explosion_drop_decay")
    val tntExplosionDropDecay: Boolean,
    @SerialName("minecraft:universal_anger")
    val universalAnger: Boolean,
    @SerialName("minecraft:water_source_conversion")
    val waterSourceConversion: Boolean,
)
