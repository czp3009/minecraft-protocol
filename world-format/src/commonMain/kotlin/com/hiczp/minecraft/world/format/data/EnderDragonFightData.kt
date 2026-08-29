package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.world.format.BlockPosition
import com.hiczp.minecraft.world.format.NbtBlockPositionSerializer
import com.hiczp.minecraft.world.format.NbtUuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Contents of `minecraft:ender_dragon_fight` dimension saved data. */
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
    val respawnCrystals: List<@Serializable(with = NbtUuidSerializer::class) Uuid> = emptyList(),
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
