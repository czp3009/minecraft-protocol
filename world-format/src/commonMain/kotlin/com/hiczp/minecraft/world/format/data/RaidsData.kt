package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.world.format.BlockPosition
import com.hiczp.minecraft.world.format.NbtBlockPositionSerializer
import com.hiczp.minecraft.world.format.NbtUuidSetSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Contents of `minecraft:raids` dimension saved data. */
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
