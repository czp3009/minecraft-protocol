@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtIntArray
import kotlinx.serialization.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
        val dataPacks: DataPacks = DataPacks(
            enabled = listOf("vanilla"),
            disabled = emptyList(),
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
        data class DataPacks(
            @SerialName("Enabled")
            val enabled: List<String>,
            @SerialName("Disabled")
            val disabled: List<String>,
        )
    }
}

/** Player advancement progress stored in `players/advancements/<uuid>.json`. */
@Serializable(with = PlayerAdvancementsSerializer::class)
data class PlayerAdvancements(
    val dataVersion: Int,
    val advancements: Map<String, Progress>,
) {
    @Serializable
    data class Progress(
        val criteria: Map<String, String>,
        val done: Boolean,
    )
}

/** Player statistics stored in `players/stats/<uuid>.json`. */
@Serializable
data class PlayerStatistics(
    val stats: Map<String, Map<String, Int>>,
    @SerialName("DataVersion")
    val dataVersion: Int,
)

internal object PlayerAdvancementsSerializer : KSerializer<PlayerAdvancements> {
    private const val DATA_VERSION = "DataVersion"
    private val progressSerializer = PlayerAdvancements.Progress.serializer()

    override val descriptor: SerialDescriptor = MapSerializer(
        String.serializer(),
        progressSerializer,
    ).descriptor

    override fun serialize(encoder: Encoder, value: PlayerAdvancements) {
        val output = encoder.beginCollection(descriptor, value.advancements.size + 1)
        var index = 0
        output.encodeStringElement(descriptor, index++, DATA_VERSION)
        output.encodeIntElement(descriptor, index++, value.dataVersion)
        value.advancements.forEach { (identifier, progress) ->
            output.encodeStringElement(descriptor, index++, identifier)
            output.encodeSerializableElement(
                descriptor,
                index++,
                progressSerializer,
                progress,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PlayerAdvancements {
        val input = decoder.beginStructure(descriptor)
        var dataVersion: Int? = null
        val advancements = linkedMapOf<String, PlayerAdvancements.Progress>()
        var key: String? = null
        while (true) {
            val index = input.decodeElementIndex(descriptor)
            if (index < 0) break
            if (index % 2 == 0) {
                if (key != null) {
                    throw SerializationException("Advancement map key has no value")
                }
                key = input.decodeStringElement(descriptor, index)
                continue
            }
            val identifier = key ?: throw SerializationException("Advancement map value has no key")
            if (identifier == DATA_VERSION) {
                dataVersion = input.decodeIntElement(descriptor, index)
            } else {
                advancements[identifier] = input.decodeSerializableElement(
                    descriptor,
                    index,
                    progressSerializer,
                )
            }
            key = null
        }
        input.endStructure(descriptor)
        if (key != null) throw SerializationException("Advancement map key has no value")
        return PlayerAdvancements(
            dataVersion = dataVersion ?: throw MissingFieldException(DATA_VERSION, descriptor.serialName),
            advancements = advancements,
        )
    }
}
