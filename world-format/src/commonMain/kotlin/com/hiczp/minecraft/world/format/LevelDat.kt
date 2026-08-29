package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtIntArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The complete `level.dat` root written by the repository-selected Minecraft release.
 *
 * This is a selected-release schema, not a historical compatibility model. Unknown or
 * modded fields require the raw NBT APIs.
 */
@Serializable
data class LevelDat(
    @SerialName("Data")
    val data: Data,
) {
    @Serializable
    data class Data(
        @SerialName("DataVersion")
        val dataVersion: Int,
        @SerialName("LastPlayed")
        val lastPlayed: Long,
        @SerialName("LevelName")
        val levelName: String,
        @SerialName("GameType")
        val gameType: Int,
        @SerialName("Time")
        val time: Long,
        val version: Int,
        @SerialName("Version")
        val versionInfo: Version,
        @SerialName("ServerBrands")
        val serverBrands: List<String>,
        @SerialName("WasModded")
        val wasModded: Boolean,
        @SerialName("allowCommands")
        val allowCommands: Boolean,
        @SerialName("initialized")
        val initialized: Boolean,
        @SerialName("difficulty_settings")
        val difficultySettings: DifficultySettings,
        @SerialName("spawn")
        val spawn: Spawn,
        @SerialName("DataPacks")
        val dataPackSelection: DataPackSelection = DataPackSelection(
            enabledDataPackReferences = listOf("vanilla"),
            disabledDataPackReferences = emptyList(),
        ),
        @SerialName("enabled_features")
        val enabledFeatures: List<String> = listOf("minecraft:vanilla"),
        @SerialName("removed_features")
        val removedFeatures: List<String> = emptyList(),
        @SerialName("singleplayer_uuid")
        val singleplayerUuid: NbtIntArray? = null,
    ) {
        @Serializable
        data class Version(
            @SerialName("Id")
            val id: Int,
            @SerialName("Name")
            val name: String,
            @SerialName("Series")
            val series: String,
            @SerialName("Snapshot")
            val snapshot: Boolean,
        )

        @Serializable
        data class DifficultySettings(
            val difficulty: String,
            val hardcore: Boolean,
            val locked: Boolean,
        )

        @Serializable
        data class Spawn(
            val dimension: String,
            val pos: NbtIntArray,
            val yaw: Float,
            val pitch: Float,
        )

        @Serializable
        data class DataPackSelection(
            @SerialName("Enabled")
            val enabledDataPackReferences: List<String>,
            @SerialName("Disabled")
            val disabledDataPackReferences: List<String>,
        )
    }
}
