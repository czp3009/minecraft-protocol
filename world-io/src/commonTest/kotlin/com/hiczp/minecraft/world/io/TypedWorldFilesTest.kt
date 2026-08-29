package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtTagTreeSerializer
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class TypedWorldFilesTest {
    @Test
    fun missingStandardPlayerFilesReturnNullAcrossEveryReadForm() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val player = "00000000-0000-0000-0000-000000000000"
        val savedDataId = SavedDataId("missing", namespace = "example")
        fakeFileSystem.createDirectories(minecraftWorldPaths.root)

        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)
        try {
            assertNull(minecraftWorldAccess.data.readDocument(savedDataId))
            assertNull(minecraftWorldAccess.data.read(savedDataId, LevelDat.serializer()))
            assertNull(minecraftWorldAccess.data.read<LevelDat>(savedDataId))
            assertNull(minecraftWorldAccess.data.read(savedDataId) { source -> source.readUtf8() })

            assertNull(minecraftWorldAccess.players.readDataDocument(player))
            assertNull(minecraftWorldAccess.players.readData(player, PlayerData.serializer()))
            assertNull(minecraftWorldAccess.players.readData(player))
            assertNull(minecraftWorldAccess.players.readData<PlayerData>(player))
            assertNull(minecraftWorldAccess.players.readData(player) { source -> source.readUtf8() })

            assertNull(minecraftWorldAccess.players.readStatisticsJson(player))
            assertNull(minecraftWorldAccess.players.readStatistics(player, PlayerStatistics.serializer()))
            assertNull(minecraftWorldAccess.players.readStatistics(player))
            assertNull(minecraftWorldAccess.players.readStatistics<PlayerStatistics>(player))
            assertNull(minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() })

            assertNull(minecraftWorldAccess.players.readAdvancementsJson(player))
            assertNull(minecraftWorldAccess.players.readAdvancements(player, PlayerAdvancements.serializer()))
            assertNull(minecraftWorldAccess.players.readAdvancements(player))
            assertNull(minecraftWorldAccess.players.readAdvancements<PlayerAdvancements>(player))
            assertNull(minecraftWorldAccess.players.readAdvancements(player) { source -> source.readUtf8() })

            val overworld = minecraftWorldAccess.dimensions.overworld
            assertNull(overworld.data.readDocument(savedDataId))
            assertNull(overworld.data.read(savedDataId, LevelDat.serializer()))
            assertNull(overworld.data.read<LevelDat>(savedDataId))
            assertNull(overworld.data.read(savedDataId) { source -> source.readUtf8() })
        } finally {
            minecraftWorldAccess.close()
        }

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertNull(liveMinecraftWorldAccess.data.readDocument(savedDataId))
        assertNull(liveMinecraftWorldAccess.data.read(savedDataId, LevelDat.serializer()))
        assertNull(liveMinecraftWorldAccess.data.read<LevelDat>(savedDataId))
        assertNull(liveMinecraftWorldAccess.data.read(savedDataId) { source -> source.readUtf8() })
        assertNull(liveMinecraftWorldAccess.players.readDataDocument(player))
        assertNull(liveMinecraftWorldAccess.players.readData(player, PlayerData.serializer()))
        assertNull(liveMinecraftWorldAccess.players.readData<PlayerData>(player))
        assertNull(liveMinecraftWorldAccess.players.readData(player) { source -> source.readUtf8() })
        assertNull(liveMinecraftWorldAccess.players.readStatisticsJson(player))
        assertNull(liveMinecraftWorldAccess.players.readStatistics(player, PlayerStatistics.serializer()))
        assertNull(liveMinecraftWorldAccess.players.readStatistics<PlayerStatistics>(player))
        assertNull(liveMinecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() })
        assertNull(liveMinecraftWorldAccess.players.readAdvancementsJson(player))
        assertNull(liveMinecraftWorldAccess.players.readAdvancements(player, PlayerAdvancements.serializer()))
        assertNull(liveMinecraftWorldAccess.players.readAdvancements<PlayerAdvancements>(player))
        assertNull(liveMinecraftWorldAccess.players.readAdvancements(player) { source -> source.readUtf8() })
        val liveOverworld = liveMinecraftWorldAccess.dimensions.overworld
        assertNull(liveOverworld.data.readDocument(savedDataId))
        assertNull(liveOverworld.data.read(savedDataId, LevelDat.serializer()))
        assertNull(liveOverworld.data.read<LevelDat>(savedDataId))
        assertNull(liveOverworld.data.read(savedDataId) { source -> source.readUtf8() })
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun mutableWorldUsesTheSameLogicalFilesForTypedTreeAndRawOperations() = runTest {
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
            minecraftWorldAccess.writeLevelData(levelDat, LevelDat.serializer())
            minecraftWorldAccess.writeLevelData(levelDat)
            assertEquals(levelDat, minecraftWorldAccess.readLevelData(LevelDat.serializer()))
            assertEquals(levelDat, minecraftWorldAccess.readLevelData<LevelDat>())
            assertIs<NbtCompound>(minecraftWorldAccess.readLevelDataDocument().root["Data"])
            val levelRoot = minecraftWorldAccess.readLevelData(
                MapSerializer(String.serializer(), NbtTagTreeSerializer),
            )
            assertIs<NbtCompound>(levelRoot["Data"])

            val rootSavedDataId = SavedDataId("typed/root", namespace = "example")
            minecraftWorldAccess.data.write(rootSavedDataId, levelDat, LevelDat.serializer())
            minecraftWorldAccess.data.write(rootSavedDataId, levelDat)
            assertEquals(levelDat, minecraftWorldAccess.data.read(rootSavedDataId, LevelDat.serializer()))
            assertEquals(levelDat, minecraftWorldAccess.data.read<LevelDat>(rootSavedDataId))
            val rootSavedDataDocument = checkNotNull(minecraftWorldAccess.data.readDocument(rootSavedDataId))
            minecraftWorldAccess.data.writeDocument(rootSavedDataId, rootSavedDataDocument)
            val rootSavedDataBytes = checkNotNull(
                minecraftWorldAccess.data.read(rootSavedDataId) { source -> source.readByteArray() },
            )
            minecraftWorldAccess.data.write(rootSavedDataId) { sink -> sink.write(rootSavedDataBytes) }
            assertEquals(levelDat, minecraftWorldAccess.data.read<LevelDat>(rootSavedDataId))

            minecraftWorldAccess.players.writeData(player, levelDat, LevelDat.serializer())
            minecraftWorldAccess.players.writeData(player, levelDat)
            assertEquals(levelDat, minecraftWorldAccess.players.readData(player, LevelDat.serializer()))
            assertEquals(levelDat, minecraftWorldAccess.players.readData<LevelDat>(player))
            assertIs<NbtCompound>(minecraftWorldAccess.players.readDataDocument(player)?.root)

            val dimensionId = DimensionId("moons/blue", namespace = "example")
            val savedDataId = SavedDataId("typed/state", namespace = "example")
            val customDimension = minecraftWorldAccess.dimensions[dimensionId]
            customDimension.data.write(savedDataId, levelDat, LevelDat.serializer())
            customDimension.data.write(savedDataId, levelDat)
            assertEquals(
                levelDat,
                customDimension.data.read(savedDataId, LevelDat.serializer()),
            )
            assertEquals(levelDat, customDimension.data.read<LevelDat>(savedDataId))
            val savedDataDocument = checkNotNull(customDimension.data.readDocument(savedDataId))
            assertIs<NbtCompound>(savedDataDocument.root)
            customDimension.data.writeDocument(savedDataId, savedDataDocument)
            val savedDataBytes = checkNotNull(
                customDimension.data.read(savedDataId) { source -> source.readByteArray() },
            )
            customDimension.data.write(savedDataId) { sink -> sink.write(savedDataBytes) }
            assertEquals(levelDat, customDimension.data.read<LevelDat>(savedDataId))

            val overworld = minecraftWorldAccess.dimensions.overworld

            overworld.data.writeWorldBorderData(worldBorderData)
            overworld.data.writeChunkTicketsData(chunkTicketsData)
            overworld.data.writeRaidsData(raidsData)
            minecraftWorldAccess.dimensions.end.data.writeEnderDragonFightData(enderDragonFightData)
            assertEquals(worldBorderData, overworld.data.readWorldBorderData())
            assertEquals(chunkTicketsData, overworld.data.readChunkTicketsData())
            assertEquals(raidsData, overworld.data.readRaidsData())
            assertEquals(enderDragonFightData, minecraftWorldAccess.dimensions.end.data.readEnderDragonFightData())

            overworld.openRegion(regionPosition).use { regionHandle ->
                regionHandle.writeChunkNbt(
                    localChunkPosition = localChunkPosition,
                    value = levelDat,
                    compression = Compression.NONE,
                    serializationStrategy = LevelDat.serializer(),
                )
                regionHandle.writeChunkNbt(localChunkPosition, levelDat, Compression.NONE)
                assertEquals(levelDat, regionHandle.readChunkNbt(localChunkPosition, LevelDat.serializer()))
                assertIs<NbtCompound>(regionHandle.readChunkNbtDocument(chunkPosition)?.root)
                assertEquals(levelDat, regionHandle.readChunkNbt<LevelDat>(localChunkPosition = localChunkPosition))
            }

            minecraftWorldAccess.players.writeStatistics(player, playerStatistics, PlayerStatistics.serializer())
            minecraftWorldAccess.players.writeStatistics(player, playerStatistics)
            assertEquals(
                playerStatistics,
                minecraftWorldAccess.players.readStatistics(player, PlayerStatistics.serializer()),
            )
            assertEquals(playerStatistics, minecraftWorldAccess.players.readStatistics(player))
            assertEquals(playerStatistics, minecraftWorldAccess.players.readStatistics<PlayerStatistics>(player))
            assertEquals(
                playerStatistics,
                minecraftWorldAccess.players.readStatistics(player, JsonElement.serializer()).let { jsonElement ->
                    Json.decodeFromJsonElement<PlayerStatistics>(
                        checkNotNull(jsonElement),
                    )
                },
            )
            assertEquals(
                minecraftWorldAccess.players.readStatisticsJson(player),
                Json.parseToJsonElement(
                    checkNotNull(minecraftWorldAccess.players.readStatistics(player) { source -> source.readUtf8() }),
                ),
            )

            minecraftWorldAccess.players.writeAdvancements(
                player,
                playerAdvancements,
                PlayerAdvancements.serializer(),
            )
            minecraftWorldAccess.players.writeAdvancements(player, playerAdvancements)
            assertEquals(
                playerAdvancements,
                minecraftWorldAccess.players.readAdvancements(player, PlayerAdvancements.serializer()),
            )
            assertEquals(playerAdvancements, minecraftWorldAccess.players.readAdvancements(player))
            assertEquals(playerAdvancements, minecraftWorldAccess.players.readAdvancements<PlayerAdvancements>(player))
            minecraftWorldAccess.players.writeAdvancements(
                player,
                checkNotNull(minecraftWorldAccess.players.readAdvancementsJson(player)),
                JsonElement.serializer(),
            )
            assertEquals(
                playerAdvancements,
                minecraftWorldAccess.players.readAdvancements(player, PlayerAdvancements.serializer()),
            )
            val advancementText = checkNotNull(
                minecraftWorldAccess.players.readAdvancements(player) { source -> source.readUtf8() },
            )
            minecraftWorldAccess.players.writeAdvancements(player) { sink ->
                sink.writeUtf8(advancementText)
            }
            assertEquals(
                playerAdvancements,
                minecraftWorldAccess.players.readAdvancements(player, PlayerAdvancements.serializer()),
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
        val rootSavedDataId = SavedDataId("typed/root", namespace = "example")
        val dimensionId = DimensionId("moons/blue", namespace = "example")
        val savedDataId = SavedDataId("typed/state", namespace = "example")
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
        LevelDataStore(minecraftWorldPaths, nbtFileStore).write(levelDat, LevelDat.serializer())
        SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.WorldRoot,
            nbtFileStore,
        ).write(rootSavedDataId, levelDat, LevelDat.serializer())
        PlayerDataStore(minecraftWorldPaths, nbtFileStore).write(player, levelDat, LevelDat.serializer())
        val customSavedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(dimensionId),
            nbtFileStore,
        )
        customSavedDataStore.write(savedDataId, levelDat, LevelDat.serializer())
        val overworldSavedDataStore = SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionId.Overworld),
            nbtFileStore,
        )
        overworldSavedDataStore.write(
            WORLD_BORDER_ID,
            worldBorderData,
            SavedDataFile.serializer(WorldBorderData.serializer()),
        )
        overworldSavedDataStore.write(
            CHUNK_TICKETS_ID,
            chunkTicketsData,
            SavedDataFile.serializer(ChunkTicketsData.serializer()),
        )
        overworldSavedDataStore.write(
            RAIDS_ID,
            raidsData,
            SavedDataFile.serializer(RaidsData.serializer()),
        )
        SavedDataStore(
            minecraftWorldPaths,
            SavedDataScope.Dimension(DimensionId.End),
            nbtFileStore,
        ).write(
            ENDER_DRAGON_FIGHT_ID,
            enderDragonFightData,
            SavedDataFile.serializer(EnderDragonFightData.serializer()),
        )
        val regionStorage = CoordinatedRegionStore(
            minecraftWorldPaths,
            dimensionId = dimensionId,
            fileSystem = fakeFileSystem,
        )
        try {
            regionStorage.writeChunkNbt(chunkPosition, levelDat, Compression.NONE, LevelDat.serializer())
            assertEquals(levelDat, regionStorage.readChunkNbt(chunkPosition, LevelDat.serializer()))
            regionStorage.writeChunkNbt(regionPosition, localChunkPosition, levelDat, Compression.NONE)
            assertEquals(levelDat, regionStorage.readChunkNbt<LevelDat>(regionPosition, localChunkPosition))
            assertEquals(listOf(chunkPosition), regionStorage.readChunkPositions(regionPosition))
            val chunkNbtBuffer = Buffer()
            assertNotNull(regionStorage.readChunkNbtTo(chunkPosition, chunkNbtBuffer))
            val chunkNbtBytes = chunkNbtBuffer.readByteArray()
            regionStorage.writeChunkNbt(regionPosition, localChunkPosition, Compression.NONE) { sink ->
                sink.write(chunkNbtBytes)
            }
            val compressedChunkBuffer = Buffer()
            assertNotNull(
                regionStorage.readCompressedChunkTo(regionPosition, localChunkPosition, compressedChunkBuffer),
            )
            assertEquals(levelDat, regionStorage.readChunkNbt<LevelDat>(chunkPosition))
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
            playerStatistics,
            PlayerStatistics.serializer(),
        )
        utf8JsonFileStore.writeJson(
            minecraftWorldPaths.advancements(player),
            playerAdvancements,
            PlayerAdvancements.serializer(),
        )

        val reader = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertEquals(levelDat, reader.readLevelData(LevelDat.serializer()))
        assertEquals(levelDat, reader.readLevelData<LevelDat>())
        assertEquals(levelDat, reader.data.read(rootSavedDataId, LevelDat.serializer()))
        assertEquals(levelDat, reader.data.read<LevelDat>(rootSavedDataId))
        assertIs<NbtCompound>(reader.data.readDocument(rootSavedDataId)?.root)
        assertNotNull(reader.data.read(rootSavedDataId) { source -> source.readByteArray() })
        assertEquals(levelDat, reader.players.readData(player, LevelDat.serializer()))
        assertEquals(levelDat, reader.players.readData<LevelDat>(player))
        val customDimension = reader.dimensions[dimensionId]
        assertEquals(levelDat, customDimension.data.read(savedDataId, LevelDat.serializer()))
        assertEquals(levelDat, customDimension.data.read<LevelDat>(savedDataId))
        assertIs<NbtCompound>(customDimension.data.readDocument(savedDataId)?.root)
        assertNotNull(customDimension.data.read(savedDataId) { source -> source.readByteArray() })
        val overworld = reader.dimensions.overworld
        assertEquals(worldBorderData, overworld.data.readWorldBorderData())
        assertEquals(chunkTicketsData, overworld.data.readChunkTicketsData())
        assertEquals(raidsData, overworld.data.readRaidsData())
        assertEquals(enderDragonFightData, reader.dimensions.end.data.readEnderDragonFightData())
        customDimension.openRegion(regionPosition).use { liveRegionHandle ->
            assertEquals(levelDat, liveRegionHandle.readChunkNbt(chunkPosition, LevelDat.serializer()))
            assertEquals(levelDat, liveRegionHandle.readChunkNbt<LevelDat>(localChunkPosition = localChunkPosition))
        }
        assertEquals(playerStatistics, reader.players.readStatistics(player, PlayerStatistics.serializer()))
        assertEquals(playerStatistics, reader.players.readStatistics(player))
        assertEquals(playerStatistics, reader.players.readStatistics<PlayerStatistics>(player))
        assertEquals(playerAdvancements, reader.players.readAdvancements(player, PlayerAdvancements.serializer()))
        assertEquals(playerAdvancements, reader.players.readAdvancements(player))
        assertEquals(playerAdvancements, reader.players.readAdvancements<PlayerAdvancements>(player))
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

            minecraftWorldAccess.players.writeStatistics(player, statisticsJson, JsonElement.serializer())
            assertEquals(
                statisticsJson,
                minecraftWorldAccess.players.readStatistics(player, JsonElement.serializer()),
            )
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
