package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of root `minecraft:wandering_trader` saved data. */
@Serializable
data class WanderingTraderData(
    @SerialName("spawn_delay")
    val spawnDelay: Int = 24_000,
    @SerialName("spawn_chance")
    val spawnChance: Int = 25,
)
