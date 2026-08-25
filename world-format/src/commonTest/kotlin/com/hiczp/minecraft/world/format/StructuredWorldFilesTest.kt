package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtIntArray
import com.hiczp.minecraft.nbt.serialization.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.*
import kotlin.test.*

class StructuredWorldFilesTest {
    private val nbt = NbtFormat(
        NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
    )

    @Test
    fun levelDatUsesTheSelectedReleaseNbtShape() {
        val expected = sampleLevelDat()
        val bytes = nbt.encodeToByteArray(expected)
        val actual = nbt.decodeFromByteArray<LevelDat>(bytes)

        assertEquals(expected, actual)
        assertEquals(expected.hashCode(), actual.hashCode())
        val root = assertIs<NbtCompound>(nbt.encodeToNbtTag(expected))
        val data = assertIs<NbtCompound>(root["Data"])
        assertIs<NbtIntArray>(assertIs<NbtCompound>(data["spawn"])["pos"])
        assertFalse("enabled_features" in data.value)
        assertFalse("removed_features" in data.value)
        assertFalse("singleplayer_uuid" in data.value)
        assertTrue("DataVersion" in data.value)
    }

    @Test
    fun levelDatRoundTripsEveryConditionalSelectedReleaseField() {
        val expected = sampleLevelDat().let { level ->
            level.copy(
                data = level.data.copy(
                    enabledFeatures = listOf("minecraft:vanilla", "example:feature"),
                    removedFeatures = listOf("example:removed_feature"),
                    singleplayerUuid = NbtIntArray(intArrayOf(1, 2, 3, 4)),
                ),
            )
        }
        val tag = assertIs<NbtCompound>(nbt.encodeToNbtTag(expected))
        val data = assertIs<NbtCompound>(tag["Data"])

        assertTrue("enabled_features" in data.value)
        assertTrue("removed_features" in data.value)
        assertIs<NbtIntArray>(data["singleplayer_uuid"])
        assertEquals(expected, nbt.decodeFromNbtTag<LevelDat>(tag))
    }

    @Test
    fun levelDatRequiresEveryUnconditionalOfficialField() {
        val encoded = assertIs<NbtCompound>(
            nbt.encodeToNbtTag(sampleLevelDat()),
        )
        val data = assertIs<NbtCompound>(encoded["Data"])
        val requiredDataFields = setOf(
            "DataVersion",
            "LastPlayed",
            "LevelName",
            "GameType",
            "Time",
            "version",
            "Version",
            "ServerBrands",
            "WasModded",
            "allowCommands",
            "initialized",
            "difficulty_settings",
            "spawn",
        )

        requiredDataFields.forEach { field ->
            assertFailsWith<SerializationException>("Missing Data.$field must fail") {
                nbt.decodeFromNbtTag<LevelDat>(
                    NbtCompound(encoded.value + ("Data" to NbtCompound(data.value - field))),
                )
            }
        }
        assertRequiredNestedFieldsFail(nbt, encoded, data, "Version", setOf("Id", "Name", "Series", "Snapshot"))
        assertRequiredNestedFieldsFail(
            nbt,
            encoded,
            data,
            "difficulty_settings",
            setOf("difficulty", "hardcore", "locked"),
        )
        assertRequiredNestedFieldsFail(
            nbt,
            encoded,
            data,
            "spawn",
            setOf("dimension", "pos", "yaw", "pitch"),
        )
        assertRequiredNestedFieldsFail(nbt, encoded, data, "DataPacks", setOf("Enabled", "Disabled"))

        val withUnknownData = NbtCompound(
            encoded.value + ("Data" to NbtCompound(data.value + ("future" to NbtInt(1)))),
        )

        assertFailsWith<SerializationException> {
            nbt.decodeFromNbtTag<LevelDat>(NbtCompound(encoded.value - "Data"))
        }
        assertFailsWith<SerializationException> {
            nbt.decodeFromNbtTag<LevelDat>(withUnknownData)
        }
        assertFailsWith<SerializationException> {
            nbt.decodeFromNbtTag<LevelDat>(NbtInt(1))
        }
    }

    @Test
    fun advancementRootStreamsHeterogeneousMapEntriesInAnyOrder() {
        val advancements = linkedMapOf<String, PlayerAdvancements.Progress>()
        repeat(2_048) { index ->
            advancements["example:advancement_$index"] = PlayerAdvancements.Progress(
                criteria = mapOf("criterion_$index" to "2026-08-18 00:00:00 +0000"),
                done = index % 2 == 0,
            )
        }
        val expected = PlayerAdvancements(4_903, advancements)
        val encoded = Json.encodeToString(expected)

        assertEquals(expected, Json.decodeFromString<PlayerAdvancements>(encoded))
        val reordered = buildJsonObject {
            put(
                "example:first",
                buildJsonObject {
                    put(
                        "criteria",
                        buildJsonObject {
                            put("criterion", JsonPrimitive("2026-08-18 00:00:00 +0000"))
                        },
                    )
                    put("done", JsonPrimitive(true))
                },
            )
            put("DataVersion", JsonPrimitive(4_903))
        }
        assertEquals(
            PlayerAdvancements(
                dataVersion = 4_903,
                advancements = mapOf(
                    "example:first" to PlayerAdvancements.Progress(
                        criteria = mapOf("criterion" to "2026-08-18 00:00:00 +0000"),
                        done = true,
                    ),
                ),
            ),
            Json.decodeFromJsonElement<PlayerAdvancements>(reordered),
        )
    }

    @Test
    fun advancementRootRequiresDataVersionAndProgressRejectsUnknownFields() {
        val progress = buildJsonObject {
            put("criteria", JsonObject(emptyMap()))
            put("done", JsonPrimitive(false))
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement<PlayerAdvancements>(
                buildJsonObject { put("example:missing_version", progress) },
            )
        }
        val validJson = Json.encodeToString(
            PlayerAdvancements(4_903, emptyMap()),
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PlayerAdvancements>(validJson.dropLast(1))
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement<PlayerAdvancements>(
                buildJsonObject {
                    put("DataVersion", JsonPrimitive(4_903))
                    put(
                        "example:unknown_field",
                        buildJsonObject {
                            put("criteria", JsonObject(emptyMap()))
                            put("done", JsonPrimitive(false))
                            put("future", JsonPrimitive(true))
                        },
                    )
                },
            )
        }
    }

    @Test
    fun statisticsRetainsDynamicTypeAndIdentifierMaps() {
        val expected = PlayerStatistics(
            stats = mapOf(
                "minecraft:mined" to mapOf(
                    "minecraft:stone" to 42,
                    "example:custom_block" to 7,
                ),
                "example:custom_type" to mapOf("example:value" to Int.MAX_VALUE),
            ),
            dataVersion = 4_903,
        )
        val encoded = Json.encodeToString(expected)

        assertEquals(expected, Json.decodeFromString<PlayerStatistics>(encoded))
        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement<PlayerStatistics>(
                buildJsonObject { put("stats", JsonObject(emptyMap())) },
            )
        }
    }
}

private fun assertRequiredNestedFieldsFail(
    nbt: NbtFormat,
    root: NbtCompound,
    data: NbtCompound,
    structureName: String,
    requiredFields: Set<String>,
) {
    val structure = assertIs<NbtCompound>(data[structureName])
    requiredFields.forEach { field ->
        val updatedData = NbtCompound(
            data.value + (structureName to NbtCompound(structure.value - field)),
        )
        val withoutField = NbtCompound(root.value + ("Data" to updatedData))
        assertFailsWith<SerializationException>("Missing Data.$structureName.$field must fail") {
            nbt.decodeFromNbtTag<LevelDat>(withoutField)
        }
    }
}

private fun sampleLevelDat(): LevelDat = LevelDat(
    data = LevelDat.Data(
        dataVersion = 4_903,
        lastPlayed = 1_786_958_771_250,
        levelName = "world",
        gameType = 0,
        time = 2,
        version = 19_133,
        versionInfo = LevelDat.Data.Version(
            id = 4_903,
            name = "26.2",
            series = "main",
            snapshot = false,
        ),
        serverBrands = listOf("vanilla"),
        wasModded = false,
        allowCommands = false,
        initialized = true,
        difficultySettings = LevelDat.Data.DifficultySettings(
            difficulty = "easy",
            hardcore = false,
            locked = false,
        ),
        spawn = LevelDat.Data.Spawn(
            dimension = "minecraft:overworld",
            pos = NbtIntArray(intArrayOf(0, -60, 0)),
            yaw = 0F,
            pitch = 0F,
        ),
        dataPacks = LevelDat.Data.DataPacks(
            enabled = listOf("vanilla"),
            disabled = listOf(
                "minecart_improvements",
                "redstone_experiments",
                "trade_rebalance",
            ),
        ),
    ),
)
