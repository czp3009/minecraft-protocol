package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The common root used by selected-release files below a world's saved-data directories. */
@Serializable
data class SavedDataFile<T>(
    @SerialName("DataVersion")
    val dataVersion: Int,
    val data: T,
)
