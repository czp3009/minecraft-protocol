package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.Serializable

/** Contents of root `minecraft:stopwatches` saved data, measured in elapsed milliseconds. */
@Serializable
data class StopwatchesData(
    val stopwatches: Map<String, Long>,
)
