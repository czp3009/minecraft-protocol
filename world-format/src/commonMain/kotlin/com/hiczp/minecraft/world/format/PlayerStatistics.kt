package com.hiczp.minecraft.world.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Player statistics stored in `players/stats/<uuid>.json`. */
@Serializable
data class PlayerStatistics(
    val stats: Map<String, Map<String, Int>>,
    @SerialName("DataVersion")
    val dataVersion: Int,
)
