package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.Serializable

/** Contents of root `minecraft:maps/last_id` saved data. */
@Serializable
data class MapIndexData(
    val map: Int = -1,
)
