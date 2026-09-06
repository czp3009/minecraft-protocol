package com.hiczp.minecraft.world.format.datapack.vanilla

import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VanillaDataPacksTest {
    @Test
    fun everyBundledOfficialFilePassesItsSelectedParser() {
        val dataPackFormat = DataPackFormat()
        var nbtFileCount = 0
        var nbtRootEntryCount = 0

        VanillaDataPacks.dataPackIds.forEach { dataPackId ->
            val dataPackArchive = VanillaDataPacks.dataPackArchive(dataPackId)
            dataPackArchive.dataPackFileBytesByPath.forEach { (dataPackFilePath, dataPackFileBytes) ->
                val dataPackFileContent = dataPackFormat.decodeFile(
                    dataPackId,
                    dataPackFilePath,
                    dataPackFileBytes,
                )
                if (dataPackFileContent is DataPackFileContent.NbtFile) {
                    nbtFileCount++
                    nbtRootEntryCount += dataPackFileContent.nbtDocument.root.size
                }
            }
        }

        assertTrue(nbtFileCount > 0)
        assertTrue(nbtRootEntryCount > 0)
    }

    @Test
    fun bundledOfficialPacksExposeTypedFilesAndMetadata() {
        val coreDataPack = VanillaDataPacks.coreDataPack

        assertTrue(coreDataPack.dataPackFileContentsByPath.values.any { it is DataPackFileContent.JsonFile })
        assertTrue(coreDataPack.dataPackFileContentsByPath.values.any { it is DataPackFileContent.NbtFile })
        assertTrue(coreDataPack.resources(VanillaDataPacks.dataPackFormatVersion).isNotEmpty())
        assertTrue(VanillaDataPacks.builtInDataPacks.isNotEmpty())
        VanillaDataPacks.builtInDataPacks.values.forEach { builtInDataPack ->
            val dataPackMetadata = assertNotNull(builtInDataPack.dataPackMetadata)
            assertTrue(dataPackMetadata.enabledFeatureFlags.isNotEmpty())
        }
    }

    @Test
    fun worldSelectionResolvesCoreBuiltInAndFilePacksInPersistedOrder() {
        val builtInDataPackId = VanillaDataPacks.dataPackIds.first { dataPackId ->
            dataPackId != VanillaDataPacks.coreDataPackId
        }
        val fileDataPack = DataPack(
            dataPackId = DataPackId("file/example"),
            dataPackMetadata = null,
            dataPackFileContentsByPath = mapOf(
                DataPackFilePath("data/example/recipe/value.json") to DataPackFileContent.JsonFile(
                    JsonPrimitive("file"),
                ),
            ),
        )
        val worldDataPackLoadResult = WorldDataPackLoadResult(
            enabledDataPackIds = listOf(
                VanillaDataPacks.coreDataPackId,
                fileDataPack.dataPackId,
                builtInDataPackId,
            ),
            loadedDataPacks = listOf(fileDataPack),
        )

        val dataPackStack = worldDataPackLoadResult.toVanillaDataPackStack()

        assertEquals(worldDataPackLoadResult.enabledDataPackIds, dataPackStack.dataPacks.map(DataPack::dataPackId))
    }

}
