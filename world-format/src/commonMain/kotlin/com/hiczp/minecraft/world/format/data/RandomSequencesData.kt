package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.NbtLongArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of root `minecraft:random_sequences` saved data. */
@Serializable
data class RandomSequencesData(
    val salt: Int,
    @SerialName("include_world_seed")
    val includeWorldSeed: Boolean = true,
    @SerialName("include_sequence_id")
    val includeSequenceId: Boolean = true,
    val sequences: Map<String, Sequence>,
) {
    @Serializable
    data class Sequence(
        val source: NbtLongArray,
    )
}
