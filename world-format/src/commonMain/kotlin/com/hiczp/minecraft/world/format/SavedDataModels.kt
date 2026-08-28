package com.hiczp.minecraft.world.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** The common root used by selected-release files below a world's saved-data directories. */
@Serializable
data class SavedDataFile<T>(
    @SerialName("DataVersion")
    val dataVersion: Int,
    val data: T,
)

/** Contents of `minecraft:world_border` saved data. */
@Serializable
data class WorldBorderData(
    @SerialName("center_x")
    val centerX: Double,
    @SerialName("center_z")
    val centerZ: Double,
    @SerialName("damage_per_block")
    val damagePerBlock: Double,
    @SerialName("safe_zone")
    val safeZone: Double,
    @SerialName("warning_blocks")
    val warningBlocks: Int,
    @SerialName("warning_time")
    val warningTime: Int,
    val size: Double,
    @SerialName("lerp_time")
    val lerpTime: Long,
    @SerialName("lerp_target")
    val lerpTarget: Double,
)

/** Contents of `minecraft:chunk_tickets` saved data. */
@Serializable
data class ChunkTicketsData(
    val tickets: List<Ticket> = emptyList(),
) {
    @Serializable
    data class Ticket(
        @SerialName("chunk_pos")
        @Serializable(with = NbtChunkPositionSerializer::class)
        val chunkPosition: ChunkPosition,
        val type: String,
        val level: Int,
        @SerialName("ticks_left")
        val ticksLeft: Long = 0,
    ) {
        init {
            require(type.isNotBlank()) { "A Chunk ticket type must not be blank" }
            require(level >= 0) { "A Chunk ticket level must not be negative" }
        }
    }
}

/** Contents of `minecraft:raids` saved data. */
@Serializable
data class RaidsData(
    val raids: List<Raid> = emptyList(),
    @SerialName("next_id")
    val nextId: Int,
    val tick: Int,
) {
    @Serializable
    data class Raid(
        val id: Int,
        val started: Boolean,
        val active: Boolean,
        @SerialName("ticks_active")
        val ticksActive: Long,
        @SerialName("raid_omen_level")
        val raidOmenLevel: Int,
        @SerialName("groups_spawned")
        val groupsSpawned: Int,
        @SerialName("cooldown_ticks")
        val cooldownTicks: Int,
        @SerialName("post_raid_ticks")
        val postRaidTicks: Int,
        @SerialName("total_health")
        val totalHealth: Float,
        @SerialName("group_count")
        val groupCount: Int,
        val status: Status,
        @Serializable(with = NbtBlockPositionSerializer::class)
        val center: BlockPosition,
        @SerialName("heroes_of_the_village")
        @Serializable(with = NbtUuidSetSerializer::class)
        val heroesOfTheVillage: Set<Uuid>,
    )

    @Serializable
    enum class Status {
        @SerialName("ongoing")
        ONGOING,

        @SerialName("victory")
        VICTORY,

        @SerialName("loss")
        LOSS,

        @SerialName("stopped")
        STOPPED,
    }
}

/** Contents of `minecraft:ender_dragon_fight` saved data. */
@Serializable
data class EnderDragonFightData(
    @SerialName("needs_state_scanning")
    val needsStateScanning: Boolean,
    @SerialName("dragon_killed")
    val dragonKilled: Boolean,
    @SerialName("previously_killed")
    val previouslyKilled: Boolean,
    @SerialName("respawn_stage")
    val respawnStage: RespawnStage? = null,
    @SerialName("respawn_time")
    val respawnTime: Int,
    @SerialName("dragon_uuid")
    @Serializable(with = NbtUuidSerializer::class)
    val dragonUuid: Uuid? = null,
    @SerialName("exit_portal_location")
    @Serializable(with = NbtBlockPositionSerializer::class)
    val exitPortalLocation: BlockPosition? = null,
    val gateways: List<Int> = emptyList(),
    @SerialName("respawn_crystals")
    @Serializable(with = NbtUuidListSerializer::class)
    val respawnCrystals: List<Uuid> = emptyList(),
) {
    @Serializable
    enum class RespawnStage {
        @SerialName("start")
        START,

        @SerialName("preparing_to_summon_pillars")
        PREPARING_TO_SUMMON_PILLARS,

        @SerialName("summoning_pillars")
        SUMMONING_PILLARS,

        @SerialName("summoning_dragon")
        SUMMONING_DRAGON,

        @SerialName("end")
        END,
    }
}
