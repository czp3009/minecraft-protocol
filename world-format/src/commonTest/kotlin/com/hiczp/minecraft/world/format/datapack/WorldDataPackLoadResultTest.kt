package com.hiczp.minecraft.world.format.datapack

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class WorldDataPackLoadResultTest {
    @Test
    fun externalResolutionRestoresTheCompletePersistedPriorityOrder() {
        val vanillaDataPack = dataPack("vanilla")
        val lowFileDataPack = dataPack("file/low")
        val builtInDataPack = dataPack("experiment")
        val highFileDataPack = dataPack("file/high")
        val loadedDataPacks = listOf(lowFileDataPack, highFileDataPack)
        val worldDataPackLoadResult = WorldDataPackLoadResult(
            enabledDataPackIds = listOf(
                vanillaDataPack.dataPackId,
                lowFileDataPack.dataPackId,
                builtInDataPack.dataPackId,
                highFileDataPack.dataPackId,
            ),
            loadedDataPacks = loadedDataPacks,
        )
        val externalDataPacks = listOf(vanillaDataPack, builtInDataPack).associateBy(DataPack::dataPackId)

        val dataPackStack = worldDataPackLoadResult.toDataPackStack(externalDataPacks::get)

        assertSame(loadedDataPacks, worldDataPackLoadResult.loadedDataPacks)
        assertEquals(
            listOf(vanillaDataPack, lowFileDataPack, builtInDataPack, highFileDataPack),
            dataPackStack.dataPacks,
        )
    }

    @Test
    fun loadedPacksAreNormalizedOnlyWhenTheirPriorityOrderDiffers() {
        val lowDataPack = dataPack("file/low")
        val highDataPack = dataPack("file/high")

        val worldDataPackLoadResult = WorldDataPackLoadResult(
            enabledDataPackIds = listOf(lowDataPack.dataPackId, highDataPack.dataPackId),
            loadedDataPacks = listOf(highDataPack, lowDataPack),
        )

        assertEquals(listOf(lowDataPack, highDataPack), worldDataPackLoadResult.loadedDataPacks)
    }

    @Test
    fun unresolvedIdsAreReportedTogether() {
        val worldDataPackLoadResult = WorldDataPackLoadResult(
            enabledDataPackIds = listOf(DataPackId("vanilla"), DataPackId("missing")),
            loadedDataPacks = emptyList(),
        )

        val failure = assertFailsWith<UnresolvedDataPackIdsException> {
            worldDataPackLoadResult.toDataPackStack { null }
        }

        assertEquals(worldDataPackLoadResult.enabledDataPackIds, failure.unresolvedDataPackIds)
    }

    private fun dataPack(dataPackId: String): DataPack = DataPack(
        dataPackId = DataPackId(dataPackId),
        dataPackMetadata = null,
        dataPackFileContentsByPath = mapOf(
            DataPackFilePath("data/test/custom/value.json") to DataPackFileContent.JsonFile(
                JsonPrimitive(dataPackId),
            ),
        ),
    )
}
