package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VanillaDataPacksTest {
    @Test
    fun everyBundledOfficialFilePassesItsSelectedParser() {
        val format = DataPackFormat()
        var nbtFileCount = 0
        var nbtRootEntryCount = 0

        VanillaDataPacks.packIds.forEach { id ->
            VanillaDataPacks.archive(id).files.forEach { (path, bytes) ->
                val content = format.decodeFile(id, path, bytes)
                if (content is DataPackFileContent.NbtFile) {
                    nbtFileCount++
                    nbtRootEntryCount += content.document.root.size
                }
            }
        }

        assertTrue(nbtFileCount > 0)
        assertTrue(nbtRootEntryCount > 0)
    }

    @Test
    fun bundledOfficialFilesFullyParseAndRetainBuiltInPacks() {
        val core = VanillaDataPacks.core

        assertTrue(core.files.values.any { it is DataPackFileContent.JsonFile })
        assertTrue(core.files.values.any { it is DataPackFileContent.NbtFile })
        assertTrue(core.resources(VanillaDataPacks.formatVersion).isNotEmpty())
        assertTrue(VanillaDataPacks.builtIn.isNotEmpty())
        VanillaDataPacks.builtIn.values.forEach { pack ->
            val metadata = assertNotNull(pack.metadata)
            assertTrue(metadata.enabledFeatures.isNotEmpty())
        }
    }

    @Test
    fun coreDefaultProjectsToExactCapturedVanillaConfiguration() {
        val projected = VanillaDataPacks.coreStack.toVanillaProtocolDataSet()

        assertEquals(VanillaProtocolData.knownPacks, projected.knownPacks)
        assertEquals(VanillaProtocolData.featureFlags, projected.featureFlags)
        assertEquals(
            VanillaProtocolData.registryPackets(emptyList()),
            projected.registryPackets(emptyList()),
        )
        assertEquals(VanillaProtocolData.tags, projected.tags)
    }

    @Test
    fun unifiedDefaultsReachClientRuntimeWithoutParsingTheArchive() {
        val configuration = VanillaDataPacks.clientConfiguration
        val runtime = VanillaDataPacks.clientRuntime

        assertEquals(VanillaProtocolData.knownPacks, configuration.knownPacks)
        assertEquals(VanillaProtocolData.registryContext.registries, runtime.registryContext.registries)
        assertTrue(runtime.tags.isNotEmpty())
    }
}
