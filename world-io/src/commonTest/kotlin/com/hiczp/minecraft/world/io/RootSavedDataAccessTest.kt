package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeId
import com.hiczp.minecraft.world.format.MinecraftWorldFormat
import com.hiczp.minecraft.world.format.SavedDataId
import com.hiczp.minecraft.world.format.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class RootSavedDataAccessTest {
    @Test
    fun everyProvidedRootSavedDataModelUsesTheSymmetricMutableAndLiveApis() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val worldGenSettings = savedDataFile(
            WorldGenSettingsData(
                seed = 42,
                generateStructures = true,
                bonusChest = false,
                dimensions = mapOf(
                    DimensionId.Overworld to WorldGenDimension(
                        type = WorldGenDimensionType.Reference(DimensionTypeId("overworld")),
                        generator = NbtCompound(mapOf("type" to NbtString("minecraft:noise"))),
                    ),
                ),
            ),
        )
        val worldClocks = savedDataFile(WorldClocksData(emptyMap()))
        val weather = savedDataFile(WeatherData(0, 1, 2, raining = false, thundering = false))
        val wanderingTrader = savedDataFile(WanderingTraderData())
        val stopwatches = savedDataFile(StopwatchesData(emptyMap()))
        val scoreboard = savedDataFile(ScoreboardData())
        val scheduledEvents = savedDataFile(ScheduledEventsData(emptyList()))
        val randomSequences = savedDataFile(RandomSequencesData(salt = 0, sequences = emptyMap()))
        val gameRules = savedDataFile(rootGameRulesData())
        val customBossEvents = savedDataFile(CustomBossEventsData(emptyMap()))
        val mapIndex = savedDataFile(MapIndexData())
        val map = savedDataFile(
            MapData(
                dimension = "minecraft:overworld",
                centerX = 0,
                centerZ = 0,
                colors = NbtByteArray(byteArrayOf(0)),
            ),
        )

        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)
        try {
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("world_gen_settings"),
                worldGenSettings,
                SavedDataFile.serializer(WorldGenSettingsData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("world_clocks"),
                worldClocks,
                SavedDataFile.serializer(WorldClocksData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("weather"),
                weather,
                SavedDataFile.serializer(WeatherData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("wandering_trader"),
                wanderingTrader,
                SavedDataFile.serializer(WanderingTraderData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("stopwatches"),
                stopwatches,
                SavedDataFile.serializer(StopwatchesData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("scoreboard"),
                scoreboard,
                SavedDataFile.serializer(ScoreboardData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("scheduled_events"),
                scheduledEvents,
                SavedDataFile.serializer(ScheduledEventsData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("random_sequences"),
                randomSequences,
                SavedDataFile.serializer(RandomSequencesData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("game_rules"),
                gameRules,
                SavedDataFile.serializer(GameRulesData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("custom_boss_events"),
                customBossEvents,
                SavedDataFile.serializer(CustomBossEventsData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("maps/last_id"),
                mapIndex,
                SavedDataFile.serializer(MapIndexData.serializer()),
            )
            assertMutableReadWrite(
                minecraftWorldAccess,
                SavedDataId("maps/0"),
                map,
                SavedDataFile.serializer(MapData.serializer()),
            )
        } finally {
            minecraftWorldAccess.close()
        }

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("world_gen_settings"), worldGenSettings)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("world_clocks"), worldClocks)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("weather"), weather)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("wandering_trader"), wanderingTrader)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("stopwatches"), stopwatches)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("scoreboard"), scoreboard)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("scheduled_events"), scheduledEvents)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("random_sequences"), randomSequences)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("game_rules"), gameRules)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("custom_boss_events"), customBossEvents)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("maps/last_id"), mapIndex)
        assertLiveRead(liveMinecraftWorldAccess, SavedDataId("maps/0"), map)
        fakeFileSystem.checkNoOpenFiles()
    }

    private suspend inline fun <reified T> assertMutableReadWrite(
        minecraftWorldAccess: MinecraftWorldAccess,
        savedDataId: SavedDataId,
        value: T,
        kSerializer: KSerializer<T>,
    ) {
        minecraftWorldAccess.data.write(savedDataId, value, kSerializer)
        assertEquals(value, minecraftWorldAccess.data.read(savedDataId, kSerializer))
        minecraftWorldAccess.data.write(savedDataId, value)
        assertEquals(value, minecraftWorldAccess.data.read<T>(savedDataId))
    }

    private inline fun <reified T> assertLiveRead(
        liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
        savedDataId: SavedDataId,
        value: T,
    ) {
        assertEquals(value, liveMinecraftWorldAccess.data.read<T>(savedDataId))
    }
}

private fun <T> savedDataFile(data: T): SavedDataFile<T> = SavedDataFile(
    dataVersion = MinecraftWorldFormat.WORLD_VERSION,
    data = data,
)

private fun rootGameRulesData(): GameRulesData = GameRulesData(
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
