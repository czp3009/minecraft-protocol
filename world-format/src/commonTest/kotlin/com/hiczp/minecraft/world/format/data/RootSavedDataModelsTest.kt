package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtLongArray
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.BlockPosition
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    "minecraft:overworld" to NbtCompound(
                        mapOf("type" to NbtString("minecraft:overworld")),
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
