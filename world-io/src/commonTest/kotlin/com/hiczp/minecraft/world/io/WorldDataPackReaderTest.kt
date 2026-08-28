package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.LevelDat
import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackFilePath
import com.hiczp.minecraft.world.format.datapack.DataPackId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldDataPackReaderTest {
    @Test
    fun mutableAndLiveFacadesReadTheSameStrongWorldSelection() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val root = "/world".toPath()
        val minecraftWorldPaths = MinecraftWorldPaths(root)
        val levelDat = testLevelDat().let { originalLevelDat ->
            LevelDat(
                originalLevelDat.data.copy(
                    dataPackSelection = LevelDat.Data.DataPackSelection(
                        enabledDataPackReferences = listOf("vanilla", "file/example"),
                        disabledDataPackReferences = listOf("trade_rebalance"),
                    ),
                    enabledFeatures = listOf("minecraft:vanilla"),
                    removedFeatures = listOf("minecraft:retired"),
                ),
            )
        }
        LevelDataStore(minecraftWorldPaths, NbtFileStore(fakeFileSystem)).write(LevelDat.serializer(), levelDat)
        fakeFileSystem.writeJson(
            minecraftWorldPaths.dataPacksDirectory / "example/pack.mcmeta",
            buildJsonObject {
                put("pack", buildJsonObject {
                    put("description", "example")
                    put("pack_format", 107)
                })
            },
        )

        val mutableResult = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem).use {
            it.readEnabledDataPacks()
        }
        val liveResult = LiveMinecraftWorldAccess.open(root, fakeFileSystem).readEnabledDataPacks()

        listOf(mutableResult, liveResult).forEach { worldDataPackLoadResult ->
            assertEquals(
                listOf(DataPackId("vanilla"), DataPackId("file/example")),
                worldDataPackLoadResult.enabledDataPackIds,
            )
            assertEquals(
                listOf(DataPackId("trade_rebalance")),
                worldDataPackLoadResult.disabledDataPackIds,
            )
            assertEquals(setOf("minecraft:vanilla"), worldDataPackLoadResult.enabledFeatureFlags)
            assertEquals(setOf("minecraft:retired"), worldDataPackLoadResult.removedFeatureFlags)
            assertEquals(
                listOf(DataPackId("file/example")),
                worldDataPackLoadResult.loadedDataPacks.map { dataPack -> dataPack.dataPackId },
            )
            assertEquals(
                listOf(DataPackId("vanilla")),
                worldDataPackLoadResult.unloadedEnabledDataPackIds,
            )
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun inspectionExposesSizesBeforeUnrestrictedDetachedRead() {
        val fakeFileSystem = FakeFileSystem()
        val dataPackDirectory = "/world/datapacks/example".toPath()
        val dataPackMetadataJson = buildJsonObject {
            put(
                "pack",
                buildJsonObject {
                    put("description", "example")
                    put("min_format", buildJsonArray {
                        add(107)
                        add(1)
                    })
                    put("max_format", 107)
                },
            )
        }
        val recipeJson = buildJsonObject { put("value", 42) }
        fakeFileSystem.writeJson(dataPackDirectory / "pack.mcmeta", dataPackMetadataJson)
        fakeFileSystem.writeJson(dataPackDirectory / "data/example/recipe/value.json", recipeJson)
        val binaryDataPackFilePath = dataPackDirectory / "data/example/custom/payload.bin"
        fakeFileSystem.createDirectories(requireNotNull(binaryDataPackFilePath.parent))
        fakeFileSystem.write(binaryDataPackFilePath) { write(byteArrayOf(1, 2, 3, 4)) }
        val worldDataPackReader = WorldDataPackReader(fakeFileSystem, "/world/datapacks".toPath())

        val dataPackInspection = worldDataPackReader.inspectDataPack(
            dataPackDirectory,
            DataPackId("file/example"),
        )

        assertEquals(3, dataPackInspection.dataPackFileInfos.size)
        assertEquals(
            dataPackInspection.dataPackFileInfos.sumOf { it.sizeInBytes.toULong() },
            dataPackInspection.totalSizeInBytes,
        )
        val dataPack = worldDataPackReader.readDataPack(dataPackInspection)
        assertEquals(DataPackId("file/example"), dataPack.dataPackId)
        assertEquals(
            byteArrayOf(1, 2, 3, 4).toList(),
            worldDataPackReader.readDataPackFile(
                dataPackInspection,
                DataPackFilePath("data/example/custom/payload.bin"),
            ) { dataPackFileSource -> dataPackFileSource.readByteArray().toList() },
        )
        assertIs<DataPackFileContent.JsonFile>(
            dataPack.dataPackFileContent(DataPackFilePath("data/example/recipe/value.json")),
        )
        assertIs<DataPackFileContent.BinaryFile>(
            dataPack.dataPackFileContent(DataPackFilePath("data/example/custom/payload.bin")),
        )
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun enabledListRetainsNonFileReferencesForTheCaller() {
        val fakeFileSystem = FakeFileSystem()
        val dataPackDirectory = "/world/datapacks/example".toPath()
        fakeFileSystem.writeJson(
            dataPackDirectory / "pack.mcmeta",
            buildJsonObject {
                put(
                    "pack",
                    buildJsonObject {
                        put("description", "example")
                        put("pack_format", 107)
                    },
                )
            },
        )
        val worldDataPackReader = WorldDataPackReader(fakeFileSystem, "/world/datapacks".toPath())

        val worldDataPackLoadResult = worldDataPackReader.readEnabledDataPacks(
            listOf(DataPackId("vanilla"), DataPackId("file/example")),
        )

        assertEquals(listOf(DataPackId("vanilla")), worldDataPackLoadResult.unloadedEnabledDataPackIds)
        assertEquals(
            listOf(DataPackId("file/example")),
            worldDataPackLoadResult.loadedDataPacks.map { dataPack -> dataPack.dataPackId },
        )
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun levelDataSelectionRetainsDisabledPacksAndFeatureConfiguration() {
        val fakeFileSystem = FakeFileSystem()
        val levelDat = testLevelDat().let { originalLevelDat ->
            LevelDat(
                originalLevelDat.data.copy(
                    dataPackSelection = LevelDat.Data.DataPackSelection(
                        enabledDataPackReferences = listOf("vanilla"),
                        disabledDataPackReferences = listOf("trade_rebalance"),
                    ),
                    enabledFeatures = listOf("minecraft:vanilla", "minecraft:trade_rebalance"),
                    removedFeatures = listOf("minecraft:retired"),
                ),
            )
        }
        val worldDataPackReader = WorldDataPackReader(fakeFileSystem, "/world/datapacks".toPath())

        val worldDataPackLoadResult = worldDataPackReader.readEnabledDataPacks(levelDat)

        assertEquals(listOf(DataPackId("vanilla")), worldDataPackLoadResult.enabledDataPackIds)
        assertEquals(listOf(DataPackId("trade_rebalance")), worldDataPackLoadResult.disabledDataPackIds)
        assertEquals(
            setOf("minecraft:vanilla", "minecraft:trade_rebalance"),
            worldDataPackLoadResult.enabledFeatureFlags,
        )
        assertEquals(setOf("minecraft:retired"), worldDataPackLoadResult.removedFeatureFlags)
        fakeFileSystem.checkNoOpenFiles()
    }
}

private fun FileSystem.writeJson(path: Path, jsonElement: JsonElement) {
    val byteArray = Json.encodeToString(jsonElement).encodeToByteArray()
    createDirectories(requireNotNull(path.parent))
    write(path) { write(byteArray) }
}
