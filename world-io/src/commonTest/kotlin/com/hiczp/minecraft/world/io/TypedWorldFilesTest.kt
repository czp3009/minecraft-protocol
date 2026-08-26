package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtTagTreeSerializer
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import kotlinx.io.writeString
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
        val openMinecraftWorld = OpenMinecraftWorld(
            minecraftWorldPaths = minecraftWorldPaths,
            worldFileAccess = WorldFileAccess.mutable(fakeFileSystem),
        )
        val player = "00000000-0000-0000-0000-000000000000"
        val regionPosition = RegionPosition(0, -1)
        val localChunkPosition = LocalChunkPosition(3, 30)
        val chunkPosition = regionPosition.chunk(localChunkPosition)
        val levelDat = testLevelDat()
        val playerStatistics = typedStatistics()
        val playerAdvancements = typedAdvancements()
        try {
            openMinecraftWorld.writeLevelData(LevelDat.serializer(), levelDat)
            assertEquals(levelDat, openMinecraftWorld.readLevelData(LevelDat.serializer()))
            assertIs<NbtCompound>(openMinecraftWorld.readLevelDataDocument().root["Data"])
            val levelRoot = openMinecraftWorld.readLevelData(
                MapSerializer(String.serializer(), NbtTagTreeSerializer),
            )
            assertIs<NbtCompound>(levelRoot["Data"])

            openMinecraftWorld.writePlayerData(player, LevelDat.serializer(), levelDat)
            assertEquals(levelDat, openMinecraftWorld.readPlayerData(player, LevelDat.serializer()))
            assertIs<NbtCompound>(openMinecraftWorld.readPlayerDataDocument(player)?.root)

            openMinecraftWorld.writeSavedData("example:typed", LevelDat.serializer(), levelDat, DimensionDirectory.Overworld)
            assertEquals(
                levelDat,
                openMinecraftWorld.readSavedData("example:typed", LevelDat.serializer(), DimensionDirectory.Overworld),
            )
            assertIs<NbtCompound>(openMinecraftWorld.readSavedDataDocument("example:typed", DimensionDirectory.Overworld)?.root)

            openMinecraftWorld.writeChunkNbt(
                regionPosition = regionPosition,
                localChunkPosition = localChunkPosition,
                serializationStrategy = LevelDat.serializer(),
                value = levelDat,
                compression = Compression.NONE,
                regionStorageDirectory = RegionStorageDirectory.CHUNKS,
                dimensionDirectory = DimensionDirectory.Overworld,
            )
            assertEquals(
                levelDat,
                openMinecraftWorld.readChunkNbt(
                    regionPosition = regionPosition,
                    localChunkPosition = localChunkPosition,
                    deserializationStrategy = LevelDat.serializer(),
                    regionStorageDirectory = RegionStorageDirectory.CHUNKS,
                    dimensionDirectory = DimensionDirectory.Overworld,
                ),
            )
            assertIs<NbtCompound>(
                openMinecraftWorld.readChunkNbtDocument(
                    chunkPosition,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )?.root,
            )
            openMinecraftWorld.openRegion(
                regionPosition = regionPosition,
                regionStorageDirectory = RegionStorageDirectory.CHUNKS,
                dimensionDirectory = DimensionDirectory.Overworld,
            ).use { regionHandle ->
                regionHandle.writeChunkNbt(
                    localChunkPosition = localChunkPosition,
                    value = levelDat,
                    compression = Compression.NONE,
                )
                assertEquals(levelDat, regionHandle.readChunkNbt<LevelDat>(localChunkPosition = localChunkPosition))
            }

            openMinecraftWorld.writeStatistics(player, PlayerStatistics.serializer(), playerStatistics)
            assertEquals(playerStatistics, openMinecraftWorld.readStatistics(player, PlayerStatistics.serializer()))
            assertEquals(
                playerStatistics,
                openMinecraftWorld.readStatistics(player, JsonElement.serializer()).let { jsonElement ->
                    kotlinx.serialization.json.Json.decodeFromJsonElement<PlayerStatistics>(
                        jsonElement,
                    )
                },
            )
            assertEquals(
                openMinecraftWorld.readStatisticsText(player),
                openMinecraftWorld.readStatistics(player) { source -> source.readString() },
            )

            openMinecraftWorld.writeAdvancements(player, PlayerAdvancements.serializer(), playerAdvancements)
            assertEquals(playerAdvancements, openMinecraftWorld.readAdvancements(player, PlayerAdvancements.serializer()))
            openMinecraftWorld.writeAdvancements(
                player,
                JsonElement.serializer(),
                openMinecraftWorld.readAdvancements(player, JsonElement.serializer()),
            )
            assertEquals(playerAdvancements, openMinecraftWorld.readAdvancements(player, PlayerAdvancements.serializer()))
            val advancementText = openMinecraftWorld.readAdvancementsText(player)
            openMinecraftWorld.writeAdvancements(player) { sink ->
                sink.writeString(advancementText)
            }
            assertEquals(playerAdvancements, openMinecraftWorld.readAdvancements(player, PlayerAdvancements.serializer()))
        } finally {
            openMinecraftWorld.close()
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun liveReaderProvidesExplicitAndReifiedTypedReadsWithoutRetainedState() = runTest {
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
        LevelDataStore(minecraftWorldPaths, nbtFileStore).write(LevelDat.serializer(), levelDat)
        PlayerDataStore(minecraftWorldPaths, nbtFileStore).write(player, LevelDat.serializer(), levelDat)
        SavedDataFileStore(minecraftWorldPaths, nbtFileStore = nbtFileStore).write("example:typed", LevelDat.serializer(), levelDat)
        val regionStorage = RegionStorage(minecraftWorldPaths, fileSystem = fakeFileSystem)
        try {
            regionStorage.writeChunkNbt(chunkPosition, levelDat, Compression.NONE)
        } finally {
            regionStorage.close()
        }
        utf8JsonFileStore.writeJson(minecraftWorldPaths.statistics(player), PlayerStatistics.serializer(), playerStatistics)
        utf8JsonFileStore.writeJson(minecraftWorldPaths.advancement(player), PlayerAdvancements.serializer(), playerAdvancements)

        val reader = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertEquals(levelDat, reader.readLevelData(LevelDat.serializer()))
        assertEquals(levelDat, reader.readLevelData<LevelDat>())
        assertEquals(levelDat, reader.readPlayerData(player, LevelDat.serializer()))
        assertEquals(levelDat, reader.readPlayerData<LevelDat>(player))
        assertEquals(levelDat, reader.readSavedData("example:typed", LevelDat.serializer()))
        assertEquals(levelDat, reader.readSavedData<LevelDat>("example:typed"))
        val liveRegionHandle = reader.openRegion(regionPosition)
        assertEquals(levelDat, liveRegionHandle.readChunkNbt(chunkPosition, LevelDat.serializer()))
        assertEquals(levelDat, liveRegionHandle.readChunkNbt<LevelDat>(localChunkPosition = localChunkPosition))
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
        val openMinecraftWorld = OpenMinecraftWorld(
            minecraftWorldPaths = minecraftWorldPaths,
            worldFileAccess = WorldFileAccess.mutable(fakeFileSystem),
        )
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
            openMinecraftWorld.writeLevelDataDocument(levelDocument)
            assertEquals(levelDocument, openMinecraftWorld.readLevelDataDocument())

            openMinecraftWorld.writeStatistics(player, JsonElement.serializer(), statisticsJson)
            assertEquals(statisticsJson, openMinecraftWorld.readStatistics(player, JsonElement.serializer()))
        } finally {
            openMinecraftWorld.close()
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
