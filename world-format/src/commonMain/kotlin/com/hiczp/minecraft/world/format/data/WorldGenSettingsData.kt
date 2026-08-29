package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.NbtCompound
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of root `minecraft:world_gen_settings` saved data. */
@Serializable
data class WorldGenSettingsData(
    val seed: Long,
    @SerialName("generate_structures")
    val generateStructures: Boolean,
    @SerialName("bonus_chest")
    val bonusChest: Boolean,
    val dimensions: Map<String, NbtCompound>,
    @SerialName("legacy_custom_options")
    val legacyCustomOptions: String? = null,
)
