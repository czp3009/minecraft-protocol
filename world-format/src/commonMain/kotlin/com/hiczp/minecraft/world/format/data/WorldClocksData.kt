package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Contents of root `minecraft:world_clocks` saved data, keyed by world-clock identifier. */
@JvmInline
@Serializable
value class WorldClocksData(
    val clocks: Map<String, Clock>,
) {
    @Serializable
    data class Clock(
        @SerialName("total_ticks")
        val totalTicks: Long,
        @SerialName("partial_tick")
        val partialTick: Float = 0F,
        val rate: Float = 1F,
        val paused: Boolean = false,
    )
}
