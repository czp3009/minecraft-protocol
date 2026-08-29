package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.BlockPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class RootSavedDataModelsTest {
    private val nbtFormat = NbtFormat(
        NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
    )

    @Test
    fun rootSavedDataModelsRoundTripTheirSelectedReleaseShapes() {
        assertRoundTrip(
            WorldGenSettingsData.serializer(),
            WorldGenSettingsData(
                seed = 42,
                generateStructures = true,
                bonusChest = false,
                dimensions = mapOf(
                    DimensionId.Overworld to WorldGenDimension(
                        type = WorldGenDimensionType.Reference(DimensionTypeId("overworld")),
                        generator = NbtCompound(mapOf("type" to NbtString("minecraft:noise"))),
                    ),
                    DimensionId("moon", "example") to WorldGenDimension(
                        type = WorldGenDimensionType.Inline(
                            NbtCompound(mapOf("height" to NbtInt(384))),
                        ),
                        generator = NbtCompound(mapOf("type" to NbtString("example:moon"))),
                    ),
                ),
            ),
        )
        assertRoundTrip(
            WorldClocksData.serializer(),
            WorldClocksData(
                mapOf("minecraft:overworld" to WorldClocksData.Clock(totalTicks = 7_157)),
            ),
        )
        assertRoundTrip(
            WeatherData.serializer(),
            WeatherData(
                clearWeatherTime = 0,
                rainTime = 74_239,
                thunderTime = 95_838,
                raining = false,
                thundering = false,
            ),
        )
        assertRoundTrip(WanderingTraderData.serializer(), WanderingTraderData(spawnDelay = 18_000))
        assertRoundTrip(
            StopwatchesData.serializer(),
            StopwatchesData(mapOf("minecraft:race" to 1_250L)),
        )
        assertRoundTrip(
            RandomSequencesData.serializer(),
            RandomSequencesData(
                salt = 0,
                sequences = mapOf(
                    "minecraft:gameplay/example" to RandomSequencesData.Sequence(
                        NbtLongArray(longArrayOf(1, 2)),
                    ),
                ),
            ),
        )
        assertRoundTrip(
            ScheduledEventsData.serializer(),
            ScheduledEventsData(
                listOf(
                    ScheduledEventsData.Event(
                        triggerTime = 120,
                        id = "example",
                        callback = ScheduledEventsData.Callback(
                            type = ScheduledEventsData.Type.FUNCTION,
                            id = "minecraft:example",
                        ),
                    ),
                ),
            ),
        )
        assertRoundTrip(
            ScoreboardData.serializer(),
            ScoreboardData(
                objectives = listOf(
                    ScoreboardData.Objective(
                        name = "points",
                        displayName = NbtString("Points"),
                    ),
                ),
                playerScores = listOf(
                    ScoreboardData.PlayerScore(
                        name = "Player",
                        objective = "points",
                        score = 5,
                    ),
                ),
                displaySlots = mapOf("sidebar" to "points"),
                teams = listOf(
                    ScoreboardData.Team(
                        name = "red",
                        color = ScoreboardData.TeamColor.RED,
                        players = listOf("Player"),
                    ),
                ),
            ),
        )
        assertRoundTrip(GameRulesData.serializer(), gameRulesData())
        assertRoundTrip(
            CustomBossEventsData.serializer(),
            CustomBossEventsData(
                mapOf(
                    "minecraft:example" to CustomBossEventsData.Event(
                        name = NbtString("Example"),
                        visible = true,
                        color = CustomBossEventsData.Color.RED,
                        players = setOf(Uuid.fromLongs(1, 2)),
                    ),
                ),
            ),
        )
        assertRoundTrip(MapIndexData.serializer(), MapIndexData(map = 3))
        assertRoundTrip(
            MapData.serializer(),
            MapData(
                dimension = "minecraft:overworld",
                centerX = 192,
                centerZ = 192,
                scale = 2,
                colors = NbtByteArray(byteArrayOf(1, 2, 3)),
                banners = listOf(
                    MapData.Banner(
                        pos = BlockPosition(1, 2, 3),
                        color = MapData.Color.RED,
                        name = NbtString("Home"),
                    ),
                ),
                frames = listOf(MapData.Frame(BlockPosition(4, 5, 6), rotation = 2, entityId = 9)),
            ),
        )
    }

    @Test
    fun worldGenDimensionsUseOfficialMapAndHolderShapes() {
        val inlineDimensionType = NbtCompound(mapOf("height" to NbtInt(384)))
        val referenceGenerator = NbtCompound(mapOf("type" to NbtString("minecraft:noise")))
        val inlineGenerator = NbtCompound(mapOf("type" to NbtString("example:moon")))
        val worldGenSettingsData = WorldGenSettingsData(
            seed = 42,
            generateStructures = true,
            bonusChest = false,
            dimensions = linkedMapOf(
                DimensionId.Overworld to WorldGenDimension(
                    WorldGenDimensionType.Reference(DimensionTypeId("overworld")),
                    referenceGenerator,
                ),
                DimensionId("moon", "example") to WorldGenDimension(
                    WorldGenDimensionType.Inline(inlineDimensionType),
                    inlineGenerator,
                ),
            ),
        )

        val encoded = assertIs<NbtCompound>(
            nbtFormat.encodeToNbtTag(WorldGenSettingsData.serializer(), worldGenSettingsData),
        )
        val dimensions = assertIs<NbtCompound>(encoded["dimensions"])
        val overworld = assertIs<NbtCompound>(dimensions["minecraft:overworld"])
        val moon = assertIs<NbtCompound>(dimensions["example:moon"])

        assertEquals(NbtString("minecraft:overworld"), overworld["type"])
        assertEquals(referenceGenerator, overworld["generator"])
        assertEquals(inlineDimensionType, moon["type"])
        assertEquals(inlineGenerator, moon["generator"])
        assertEquals(
            worldGenSettingsData,
            nbtFormat.decodeFromNbtTag(WorldGenSettingsData.serializer(), encoded),
        )
    }

    @Test
    fun worldGenDimensionsRejectWrongHolderTagsAndMissingGenerators() {
        fun worldGenSettingsWith(
            dimension: NbtCompound,
            dimensionId: String = "minecraft:overworld",
        ): NbtCompound = NbtCompound(
            mapOf(
                "seed" to NbtLong(42),
                "generate_structures" to NbtByte(1),
                "bonus_chest" to NbtByte(0),
                "dimensions" to NbtCompound(mapOf(dimensionId to dimension)),
            ),
        )

        assertFailsWith<SerializationException> {
            nbtFormat.decodeFromNbtTag(
                WorldGenSettingsData.serializer(),
                worldGenSettingsWith(
                    NbtCompound(
                        mapOf(
                            "type" to NbtInt(0),
                            "generator" to NbtCompound(emptyMap()),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<SerializationException> {
            nbtFormat.decodeFromNbtTag(
                WorldGenSettingsData.serializer(),
                worldGenSettingsWith(
                    NbtCompound(mapOf("type" to NbtString("minecraft:overworld"))),
                ),
            )
        }
        assertFailsWith<SerializationException> {
            nbtFormat.decodeFromNbtTag(
                WorldGenSettingsData.serializer(),
                worldGenSettingsWith(
                    NbtCompound(
                        mapOf(
                            "type" to NbtString("minecraft:overworld"),
                            "generator" to NbtCompound(emptyMap()),
                        ),
                    ),
                    "Minecraft:overworld",
                ),
            )
        }
    }

    @Test
    fun worldGenDimensionsRejectIdsThatCollideAfterNamespaceNormalization() {
        val worldGenDimension = NbtCompound(
            mapOf(
                "type" to NbtString("minecraft:overworld"),
                "generator" to NbtCompound(emptyMap()),
            ),
        )
        val encoded = NbtCompound(
            mapOf(
                "seed" to NbtLong(42),
                "generate_structures" to NbtByte(1),
                "bonus_chest" to NbtByte(0),
                "dimensions" to NbtCompound(
                    linkedMapOf(
                        "overworld" to worldGenDimension,
                        "minecraft:overworld" to worldGenDimension,
                    ),
                ),
            ),
        )

        assertFailsWith<SerializationException> {
            nbtFormat.decodeFromNbtTag(WorldGenSettingsData.serializer(), encoded)
        }
    }

    @Test
    fun directMapPayloadModelsDoNotAddAnArtificialProperty() {
        val clocks = WorldClocksData(
            mapOf("minecraft:overworld" to WorldClocksData.Clock(totalTicks = 12)),
        )
        val encodedClocks = nbtFormat.encodeToNbtTag(WorldClocksData.serializer(), clocks)
        val clocksCompound = assertIs<NbtCompound>(encodedClocks)
        assertEquals(setOf("minecraft:overworld"), clocksCompound.value.keys)

        val bossEvents = CustomBossEventsData(
            mapOf("minecraft:example" to CustomBossEventsData.Event(name = NbtString("Example"))),
        )
        val encodedBossEvents = nbtFormat.encodeToNbtTag(CustomBossEventsData.serializer(), bossEvents)
        val bossEventsCompound = assertIs<NbtCompound>(encodedBossEvents)
        assertEquals(setOf("minecraft:example"), bossEventsCompound.value.keys)
    }

    private fun <T> assertRoundTrip(serializer: KSerializer<T>, value: T) {
        val encoded = nbtFormat.encodeToNbtTag(serializer, value)
        assertEquals(value, nbtFormat.decodeFromNbtTag(serializer, encoded))

        val savedDataFile = SavedDataFile(dataVersion = 4_903, data = value)
        val savedDataSerializer = SavedDataFile.serializer(serializer)
        val encodedFile = nbtFormat.encodeToNbtTag(savedDataSerializer, savedDataFile)
        assertEquals(savedDataFile, nbtFormat.decodeFromNbtTag(savedDataSerializer, encodedFile))
    }
}

internal fun gameRulesData(): GameRulesData = GameRulesData(
    advanceTime = true,
    advanceWeather = true,
    allowEnteringNetherUsingPortals = true,
    blockDrops = true,
    blockExplosionDropDecay = true,
    commandBlockOutput = true,
    commandBlocksWork = true,
    drowningDamage = true,
    elytraMovementCheck = true,
    enderPearlsVanishOnDeath = true,
    entityDrops = true,
    fallDamage = true,
    fireDamage = true,
    fireSpreadRadiusAroundPlayer = 128,
    forgiveDeadPlayers = true,
    freezeDamage = true,
    globalSoundEvents = true,
    immediateRespawn = false,
    keepInventory = false,
    lavaSourceConversion = false,
    limitedCrafting = false,
    locatorBar = true,
    logAdminCommands = true,
    maxBlockModifications = 32_768,
    maxCommandForks = 65_536,
    maxCommandSequenceLength = 65_536,
    maxEntityCramming = 24,
    maxSnowAccumulationHeight = 1,
    mobDrops = true,
    mobExplosionDropDecay = true,
    mobGriefing = true,
    naturalHealthRegeneration = true,
    playerMovementCheck = true,
    playersNetherPortalCreativeDelay = 0,
    playersNetherPortalDefaultDelay = 80,
    playersSleepingPercentage = 100,
    projectilesCanBreakBlocks = true,
    pvp = true,
    raids = true,
    randomTickSpeed = 3,
    reducedDebugInfo = false,
    respawnRadius = 10,
    sendCommandFeedback = true,
    showAdvancementMessages = true,
    showDeathMessages = true,
    spawnMobs = true,
    spawnMonsters = true,
    spawnPatrols = true,
    spawnPhantoms = true,
    spawnWanderingTraders = true,
    spawnWardens = true,
    spawnerBlocksWork = true,
    spectatorsGenerateChunks = true,
    spreadVines = true,
    tntExplodes = true,
    tntExplosionDropDecay = false,
    universalAnger = false,
    waterSourceConversion = true,
)
