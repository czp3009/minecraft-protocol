package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtTagTreeSerializer
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TypedWorldFilesTest {
    @Test
    fun mutableWorldUsesTheSameLogicalFilesForTypedTreeTextAndRawOperations() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)
        val player = "00000000-0000-0000-0000-000000000000"
        val regionPosition = RegionPosition(0, -1)
        val localChunkPosition = LocalChunkPosition(3, 30)
        val chunkPosition = regionPosition.chunk(localChunkPosition)
        val levelDat = testLevelDat()
        val playerStatistics = typedStatistics()
        val playerAdvancements = typedAdvancements()
        val worldBorderData = typedWorldBorderData()
        val chunkTicketsData = typedChunkTicketsData()
        val raidsData = typedRaidsData()
        val enderDragonFightData = typedEnderDragonFightData()
        try {
            minecraftWorldAccess.writeLevelData(LevelDat.serializer(), levelDat)
            minecraftWorldAccess.writeLevelDataAs(levelDat)
            assertEquals(levelDat, minecraftWorldAccess.readLevelData(LevelDat.serializer()))
            assertEquals(levelDat, minecraftWorldAccess.readLevelDataAs<LevelDat>())
            assertIs<NbtCompound>(minecraftWorldAccess.readLevelDataDocument().root["Data"])
            val levelRoot = minecraftWorldAccess.readLevelData(
                MapSerializer(String.serializer(), NbtTagTreeSerializer),
            )
            assertIs<NbtCompound>(levelRoot["Data"])

            minecraftWorldAccess.writePlayerData(player, LevelDat.serializer(), levelDat)
            minecraftWorldAccess.writePlayerDataAs(player, levelDat)
            assertEquals(levelDat, minecraftWorldAccess.readPlayerData(player, LevelDat.serializer()))
            assertEquals(levelDat, minecraftWorldAccess.readPlayerDataAs<LevelDat>(player))
            assertIs<NbtCompound>(minecraftWorldAccess.readPlayerDataDocument(player)?.root)

            val savedDataScope = SavedDataScope.Dimension(DimensionDirectory.Overworld)
            minecraftWorldAccess.writeSavedData("example:typed", LevelDat.serializer(), levelDat, savedDataScope)
            minecraftWorldAccess.writeSavedData("example:typed", levelDat, savedDataScope)
            assertEquals(
                levelDat,
                minecraftWorldAccess.readSavedData("example:typed", LevelDat.serializer(), savedDataScope),
            )
            assertEquals(levelDat, minecraftWorldAccess.readSavedData<LevelDat>("example:typed", savedDataScope))
            assertIs<NbtCompound>(minecraftWorldAccess.readSavedDataDocument("example:typed", savedDataScope)?.root)

            minecraftWorldAccess.writeWorldBorderData(worldBorderData)
            minecraftWorldAccess.writeChunkTicketsData(chunkTicketsData)
            minecraftWorldAccess.writeRaidsData(raidsData)
            minecraftWorldAccess.writeEnderDragonFightData(enderDragonFightData)
            assertEquals(worldBorderData, minecraftWorldAccess.readWorldBorderData())
            assertEquals(chunkTicketsData, minecraftWorldAccess.readChunkTicketsData())
            assertEquals(raidsData, minecraftWorldAccess.readRaidsData())
            assertEquals(enderDragonFightData, minecraftWorldAccess.readEnderDragonFightData())

            minecraftWorldAccess.openRegion(regionPosition).use { regionHandle ->
                regionHandle.writeChunkNbt(
                    localChunkPosition = localChunkPosition,
                    serializationStrategy = LevelDat.serializer(),
                    value = levelDat,
                    compression = Compression.NONE,
                )
                regionHandle.writeChunkNbt(localChunkPosition, levelDat, Compression.NONE)
                assertEquals(levelDat, regionHandle.readChunkNbt(localChunkPosition, LevelDat.serializer()))
                assertIs<NbtCompound>(regionHandle.readChunkNbtDocument(chunkPosition)?.root)
                assertEquals(levelDat, regionHandle.readChunkNbt<LevelDat>(localChunkPosition = localChunkPosition))
            }

            minecraftWorldAccess.writeStatistics(player, PlayerStatistics.serializer(), playerStatistics)
            minecraftWorldAccess.writeStatistics(player, playerStatistics)
            assertEquals(playerStatistics, minecraftWorldAccess.readStatistics(player, PlayerStatistics.serializer()))
            assertEquals(playerStatistics, minecraftWorldAccess.readStatistics<PlayerStatistics>(player))
            assertEquals(
                playerStatistics,
                minecraftWorldAccess.readStatistics(player, JsonElement.serializer()).let { jsonElement ->
                    kotlinx.serialization.json.Json.decodeFromJsonElement<PlayerStatistics>(
                        jsonElement,
                    )
                },
            )
            assertEquals(
                minecraftWorldAccess.readStatisticsText(player),
                minecraftWorldAccess.readStatistics(player) { source -> source.readUtf8() },
            )

            minecraftWorldAccess.writeAdvancements(player, PlayerAdvancements.serializer(), playerAdvancements)
            minecraftWorldAccess.writeAdvancements(player, playerAdvancements)
            assertEquals(
                playerAdvancements,
                minecraftWorldAccess.readAdvancements(player, PlayerAdvancements.serializer()),
            )
            assertEquals(playerAdvancements, minecraftWorldAccess.readAdvancements<PlayerAdvancements>(player))
            minecraftWorldAccess.writeAdvancements(
                player,
                JsonElement.serializer(),
                minecraftWorldAccess.readAdvancements(player, JsonElement.serializer()),
            )
            assertEquals(
                playerAdvancements,
                minecraftWorldAccess.readAdvancements(player, PlayerAdvancements.serializer()),
            )
            val advancementText = minecraftWorldAccess.readAdvancementsText(player)
            minecraftWorldAccess.writeAdvancements(player) { sink ->
                sink.writeUtf8(advancementText)
            }
            assertEquals(
                playerAdvancements,
                minecraftWorldAccess.readAdvancements(player, PlayerAdvancements.serializer()),
            )
        } finally {
            minecraftWorldAccess.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun liveReaderProvidesExplicitAndReifiedTypedReads() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val nbtFileStore = NbtFileStore(fakeFileSystem)
        val utf8JsonFileStore = Utf8JsonFileStore(fakeFileSystem)
        val player = "00000000-0000-0000-0000-000000000000"
        val regionPosition = RegionPosition(0, -1)
        val localChunkPosition = LocalChunkPosition(3, 30)
        val chunkPosition = regionPosition.chunk(localChunkPosition)
        val levelDat = testLevelDat()
        val playerStatistics = typedStatistics()
        val playerAdvancements = typedAdvancements()
        val worldBorderData = typedWorldBorderData()
        val chunkTicketsData = typedChunkTicketsData()
        val raidsData = typedRaidsData()
        val enderDragonFightData = typedEnderDragonFightData()
        LevelDataStore(minecraftWorldPaths, nbtFileStore).write(LevelDat.serializer(), levelDat)
        PlayerDataStore(minecraftWorldPaths, nbtFileStore).write(player, LevelDat.serializer(), levelDat)
        val overworldSavedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionDirectory.Overworld),
            nbtFileStore,
        )
        overworldSavedDataStore.write("example:typed", LevelDat.serializer(), levelDat)
        overworldSavedDataStore.write(
            WORLD_BORDER_IDENTIFIER,
            SavedDataFile.serializer(WorldBorderData.serializer()),
            worldBorderData,
        )
        overworldSavedDataStore.write(
            CHUNK_TICKETS_IDENTIFIER,
            SavedDataFile.serializer(ChunkTicketsData.serializer()),
            chunkTicketsData,
        )
        overworldSavedDataStore.write(
            RAIDS_IDENTIFIER,
            SavedDataFile.serializer(RaidsData.serializer()),
            raidsData,
        )
        SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionDirectory.End),
            nbtFileStore,
        ).write(
            ENDER_DRAGON_FIGHT_IDENTIFIER,
            SavedDataFile.serializer(EnderDragonFightData.serializer()),
            enderDragonFightData,
        )
        val regionStorage = CoordinatedRegionStore(minecraftWorldPaths, fileSystem = fakeFileSystem)
        try {
            regionStorage.writeChunkNbt(chunkPosition, LevelDat.serializer(), levelDat, Compression.NONE)
            assertEquals(levelDat, regionStorage.readChunkNbt(chunkPosition, LevelDat.serializer()))
            regionStorage.writeChunkNbt(regionPosition, localChunkPosition, levelDat, Compression.NONE)
            assertEquals(levelDat, regionStorage.readChunkNbt<LevelDat>(regionPosition, localChunkPosition))
            regionStorage.openRegion(regionPosition).use { regionHandle ->
                regionStorage.writeChunkNbt(regionHandle.entry, localChunkPosition, levelDat, Compression.NONE)
                assertEquals(
                    levelDat,
                    regionStorage.readChunkNbt<LevelDat>(regionHandle.entry, localChunkPosition),
                )
            }
        } finally {
            regionStorage.close()
        }
        utf8JsonFileStore.writeJson(
            minecraftWorldPaths.statistics(player),
            PlayerStatistics.serializer(),
            playerStatistics,
        )
        utf8JsonFileStore.writeJson(
            minecraftWorldPaths.advancement(player),
            PlayerAdvancements.serializer(),
            playerAdvancements,
        )

        val reader = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertEquals(levelDat, reader.readLevelData(LevelDat.serializer()))
        assertEquals(levelDat, reader.readLevelDataAs<LevelDat>())
        assertEquals(levelDat, reader.readPlayerData(player, LevelDat.serializer()))
        assertEquals(levelDat, reader.readPlayerDataAs<LevelDat>(player))
        val savedDataScope = SavedDataScope.Dimension(DimensionDirectory.Overworld)
        assertEquals(levelDat, reader.readSavedData("example:typed", LevelDat.serializer(), savedDataScope))
        assertEquals(levelDat, reader.readSavedData<LevelDat>("example:typed", savedDataScope))
        assertEquals(worldBorderData, reader.readWorldBorderData())
        assertEquals(chunkTicketsData, reader.readChunkTicketsData())
        assertEquals(raidsData, reader.readRaidsData())
        assertEquals(enderDragonFightData, reader.readEnderDragonFightData())
        reader.openRegion(regionPosition).use { liveRegionHandle ->
            assertEquals(levelDat, liveRegionHandle.readChunkNbt(chunkPosition, LevelDat.serializer()))
            assertEquals(levelDat, liveRegionHandle.readChunkNbt<LevelDat>(localChunkPosition = localChunkPosition))
        }
        assertEquals(playerStatistics, reader.readStatistics(player, PlayerStatistics.serializer()))
        assertEquals(playerStatistics, reader.readStatistics<PlayerStatistics>(player))
        assertEquals(playerAdvancements, reader.readAdvancements(player, PlayerAdvancements.serializer()))
        assertEquals(playerAdvancements, reader.readAdvancements<PlayerAdvancements>(player))
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun treeFallbacksRoundTripUnknownFieldsWithoutTypedSchemaLoss() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)
        val player = "00000000-0000-0000-0000-000000000000"
        val levelDocument = NbtDocument(
            NbtCompound(
                mapOf(
                    "Data" to NbtCompound(
                        mapOf("future_mod_field" to NbtInt(7)),
                    ),
                ),
            ),
        )
        val statisticsJson = buildJsonObject {
            put("DataVersion", JsonPrimitive(4_903))
            put("future_mod_field", buildJsonObject { put("value", JsonPrimitive(7)) })
        }
        try {
            minecraftWorldAccess.writeLevelDataDocument(levelDocument)
            assertEquals(levelDocument, minecraftWorldAccess.readLevelDataDocument())

            minecraftWorldAccess.writeStatistics(player, JsonElement.serializer(), statisticsJson)
            assertEquals(statisticsJson, minecraftWorldAccess.readStatistics(player, JsonElement.serializer()))
        } finally {
            minecraftWorldAccess.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }
}

private fun typedStatistics(): PlayerStatistics = PlayerStatistics(
    stats = mapOf(
        "minecraft:mined" to mapOf("minecraft:stone" to 42),
        "example:custom" to mapOf("example:value" to 7),
    ),
    dataVersion = 4_903,
)

private fun typedAdvancements(): PlayerAdvancements = PlayerAdvancements(
    dataVersion = 4_903,
    advancements = mapOf(
        "minecraft:story/root" to PlayerAdvancements.Progress(
            criteria = mapOf("crafting_table" to "2026-08-18 00:00:00 +0000"),
            done = true,
        ),
    ),
)

private fun typedWorldBorderData(): SavedDataFile<WorldBorderData> = SavedDataFile(
    dataVersion = 4_903,
    data = WorldBorderData(
        centerX = 0.0,
        centerZ = 0.0,
        damagePerBlock = 0.2,
        safeZone = 5.0,
        warningBlocks = 5,
        warningTime = 300,
        size = 59_999_968.0,
        lerpTime = 0,
        lerpTarget = 59_999_968.0,
    ),
)

private fun typedChunkTicketsData(): SavedDataFile<ChunkTicketsData> =
    SavedDataFile(dataVersion = 4_903, data = ChunkTicketsData())

private fun typedRaidsData(): SavedDataFile<RaidsData> =
    SavedDataFile(dataVersion = 4_903, data = RaidsData(nextId = 1, tick = 0))

private fun typedEnderDragonFightData(): SavedDataFile<EnderDragonFightData> = SavedDataFile(
    dataVersion = 4_903,
    data = EnderDragonFightData(
        needsStateScanning = true,
        dragonKilled = false,
        previouslyKilled = false,
        respawnTime = 0,
    ),
)
