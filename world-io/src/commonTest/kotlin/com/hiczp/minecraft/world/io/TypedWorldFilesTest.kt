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
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TypedWorldFilesTest {
    @Test
    fun mutableWorldUsesTheSameLogicalFilesForTypedTreeTextAndRawOperations() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val world = OpenMinecraftWorld(
            paths = paths,
            files = WorldFileAccess.mutable(fileSystem),
        )
        val player = "00000000-0000-0000-0000-000000000000"
        val region = RegionPosition(0, -1)
        val localChunk = LocalChunkPosition(3, 30)
        val chunk = region.chunk(localChunk)
        val level = testLevelDat()
        val statistics = typedStatistics()
        val advancements = typedAdvancements()
        try {
            world.writeLevelData(LevelDat.serializer(), level)
            assertEquals(level, world.readLevelData(LevelDat.serializer()))
            assertIs<NbtCompound>(world.readLevelDataDocument().root["Data"])
            val levelRoot = world.readLevelData(
                MapSerializer(String.serializer(), NbtTagTreeSerializer),
            )
            assertIs<NbtCompound>(levelRoot["Data"])

            world.writePlayerData(player, LevelDat.serializer(), level)
            assertEquals(level, world.readPlayerData(player, LevelDat.serializer()))
            assertIs<NbtCompound>(world.readPlayerDataDocument(player)?.root)

            world.writeSavedData("example:typed", LevelDat.serializer(), level, DimensionDirectory.Overworld)
            assertEquals(
                level,
                world.readSavedData("example:typed", LevelDat.serializer(), DimensionDirectory.Overworld),
            )
            assertIs<NbtCompound>(world.readSavedDataDocument("example:typed", DimensionDirectory.Overworld)?.root)

            world.writeChunkNbt(
                region,
                localChunk,
                LevelDat.serializer(),
                level,
                Compression.NONE,
                RegionStorageDirectory.CHUNKS,
                DimensionDirectory.Overworld,
            )
            assertEquals(
                level,
                world.readChunkNbt(
                    chunk,
                    LevelDat.serializer(),
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                ),
            )
            assertIs<NbtCompound>(
                world.readChunkNbtDocument(
                    chunk,
                    RegionStorageDirectory.CHUNKS,
                    DimensionDirectory.Overworld,
                )?.root,
            )

            world.writeStatistics(player, PlayerStatistics.serializer(), statistics)
            assertEquals(statistics, world.readStatistics(player, PlayerStatistics.serializer()))
            assertEquals(
                statistics,
                world.readStatistics(player, JsonElement.serializer()).let { element ->
                    kotlinx.serialization.json.Json.decodeFromJsonElement(
                        PlayerStatistics.serializer(),
                        element,
                    )
                },
            )
            assertEquals(
                world.readStatisticsText(player),
                world.readStatistics(player) { source -> source.readString() },
            )

            world.writeAdvancements(player, PlayerAdvancements.serializer(), advancements)
            assertEquals(advancements, world.readAdvancements(player, PlayerAdvancements.serializer()))
            world.writeAdvancements(
                player,
                JsonElement.serializer(),
                world.readAdvancements(player, JsonElement.serializer()),
            )
            assertEquals(advancements, world.readAdvancements(player, PlayerAdvancements.serializer()))
            val advancementText = world.readAdvancementsText(player)
            world.writeAdvancements(player) { sink ->
                sink.writeString(advancementText)
            }
            assertEquals(advancements, world.readAdvancements(player, PlayerAdvancements.serializer()))
        } finally {
            world.close()
        }
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun liveReaderProvidesExplicitAndReifiedTypedReadsWithoutRetainedState() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val nbtFiles = NbtFileStore(fileSystem)
        val jsonFiles = Utf8JsonFileStore(fileSystem)
        val player = "00000000-0000-0000-0000-000000000000"
        val region = RegionPosition(0, -1)
        val localChunk = LocalChunkPosition(3, 30)
        val chunk = region.chunk(localChunk)
        val level = testLevelDat()
        val statistics = typedStatistics()
        val advancements = typedAdvancements()
        LevelDataStore(paths, nbtFiles).write(LevelDat.serializer(), level)
        PlayerDataStore(paths, nbtFiles).write(player, LevelDat.serializer(), level)
        SavedDataFileStore(paths, nbtFiles = nbtFiles).write("example:typed", LevelDat.serializer(), level)
        val regions = RegionStorage(paths, fileSystem = fileSystem)
        try {
            regions.writeChunkNbt(chunk, LevelDat.serializer(), level, Compression.NONE)
        } finally {
            regions.close()
        }
        jsonFiles.writeJson(paths.statistics(player), PlayerStatistics.serializer(), statistics)
        jsonFiles.writeJson(paths.advancement(player), PlayerAdvancements.serializer(), advancements)

        val reader = LiveMinecraftWorldAccess.open(paths.root, fileSystem)
        assertEquals(level, reader.readLevelData(LevelDat.serializer()))
        assertEquals(level, reader.readLevelData<LevelDat>())
        assertEquals(level, reader.readPlayerData(player, LevelDat.serializer()))
        assertEquals(level, reader.readPlayerData<LevelDat>(player))
        assertEquals(level, reader.readSavedData("example:typed", LevelDat.serializer()))
        assertEquals(level, reader.readSavedData<LevelDat>("example:typed"))
        val liveRegionHandle = reader.openRegion(region)
        assertEquals(level, liveRegionHandle.readChunkNbt(chunk, LevelDat.serializer()))
        assertEquals(level, liveRegionHandle.readChunkNbt<LevelDat>(localChunk))
        assertEquals(statistics, reader.readStatistics(player, PlayerStatistics.serializer()))
        assertEquals(statistics, reader.readStatistics<PlayerStatistics>(player))
        assertEquals(advancements, reader.readAdvancements(player, PlayerAdvancements.serializer()))
        assertEquals(advancements, reader.readAdvancements<PlayerAdvancements>(player))
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun treeFallbacksRoundTripUnknownFieldsWithoutTypedSchemaLoss() = runTest {
        val fileSystem = FakeFileSystem()
        val paths = MinecraftWorldPaths("/world".toPath())
        val world = OpenMinecraftWorld(
            paths = paths,
            files = WorldFileAccess.mutable(fileSystem),
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
            world.writeLevelDataDocument(levelDocument)
            assertEquals(levelDocument, world.readLevelDataDocument())

            world.writeStatistics(player, JsonElement.serializer(), statisticsJson)
            assertEquals(statisticsJson, world.readStatistics(player, JsonElement.serializer()))
        } finally {
            world.close()
        }
        fileSystem.checkNoOpenFiles()
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
