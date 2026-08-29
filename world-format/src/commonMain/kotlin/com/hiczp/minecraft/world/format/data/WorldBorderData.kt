package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of `minecraft:world_border` dimension saved data. */
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
