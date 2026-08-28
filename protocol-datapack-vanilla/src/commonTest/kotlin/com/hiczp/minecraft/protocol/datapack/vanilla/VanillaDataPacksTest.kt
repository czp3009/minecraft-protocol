package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.datapack.DataPackRegistryProjector
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

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
    fun coreDefaultProjectsToExactCapturedVanillaConfiguration() {
        val resolvedProtocolData = VanillaDataPacks.coreDataPackStack.toVanillaProtocolData()

        assertEquals(VanillaProtocolData.offeredKnownPacks, resolvedProtocolData.offeredKnownPacks)
        assertEquals(VanillaProtocolData.enabledFeatureFlags, resolvedProtocolData.enabledFeatureFlags)
        assertEquals(
            VanillaProtocolData.synchronizedRegistryPackets(emptyList()),
            resolvedProtocolData.synchronizedRegistryPackets(emptyList()),
        )
        assertEquals(VanillaProtocolData.registryTags, resolvedProtocolData.registryTags)
    }

    @Test
    fun defaultProjectorsCoverEverySynchronizedVanillaRegistry() {
        assertEquals(
            VanillaProtocolData.synchronizedRegistryPackets(emptyList()).map { registryDataPacket ->
                registryDataPacket.registryId
            },
            vanillaDataPackRegistryProjectors.map(DataPackRegistryProjector::registryId),
        )
    }

    @Test
    fun vanillaRegistryResourcesNeedNoCallerProjectors() {
        val biomeRegistryId = Identifier("worldgen/biome")
        val damageTypeRegistryId = Identifier("damage_type")
        val (dataPack, registryEntryIdsByRegistryId) = overlayDataPack(
            listOf(biomeRegistryId, damageTypeRegistryId),
        )

        val resolvedProtocolData = DataPackStack(dataPack).toVanillaProtocolData()

        registryEntryIdsByRegistryId.forEach { (registryId, registryEntryId) ->
            val registryEntry = resolvedProtocolData.synchronizedRegistryPackets(emptyList())
                .single { registryDataPacket -> registryDataPacket.registryId == registryId }
                .entries.single { candidate -> candidate.id == registryEntryId }
            assertIs<NbtCompound>(registryEntry.data)
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

    @Test
    fun worldSelectionAddsRequiredCoreAndProjectsPersistedFeatureFlags() {
        val fileDataPack = DataPack(
            dataPackId = DataPackId("file/example"),
            dataPackMetadata = null,
            dataPackFileContentsByPath = mapOf(
                DataPackFilePath("data/example/recipe/value.json") to DataPackFileContent.JsonFile(
                    JsonPrimitive("file"),
                ),
            ),
        )
        val additionalFeatureFlag = Identifier("example:world_feature")
        val worldDataPackLoadResult = WorldDataPackLoadResult(
            enabledDataPackIds = listOf(fileDataPack.dataPackId),
            enabledFeatureFlags = setOf(additionalFeatureFlag.value),
            loadedDataPacks = listOf(fileDataPack),
        )

        val dataPackStack = worldDataPackLoadResult.toVanillaDataPackStack()
        val resolvedProtocolData = worldDataPackLoadResult.toVanillaProtocolData()

        assertEquals(
            listOf(VanillaDataPacks.coreDataPackId, fileDataPack.dataPackId),
            dataPackStack.dataPacks.map(DataPack::dataPackId),
        )
        assertTrue(additionalFeatureFlag in resolvedProtocolData.enabledFeatureFlags)
    }

    @Test
    fun callerProjectorsOverrideVanillaDefaultsWithoutRemovingTheOthers() {
        val biomeRegistryId = Identifier("worldgen/biome")
        val damageTypeRegistryId = Identifier("damage_type")
        val (dataPack, registryEntryIdsByRegistryId) = overlayDataPack(
            listOf(biomeRegistryId, damageTypeRegistryId),
        )
        val biomeRegistryEntryId = registryEntryIdsByRegistryId.getValue(biomeRegistryId)
        val damageTypeRegistryEntryId = registryEntryIdsByRegistryId.getValue(damageTypeRegistryId)

        val resolvedProtocolData = DataPackStack(dataPack).toVanillaProtocolData(
            dataPackRegistryProjectorOverrides = listOf(
                DataPackRegistryProjector(biomeRegistryId) { _, _, _ -> NbtString("custom") },
            ),
        )

        val registryDataPacketsById = resolvedProtocolData.synchronizedRegistryPackets(emptyList())
            .associateBy { registryDataPacket -> registryDataPacket.registryId }
        assertEquals(
            NbtString("custom"),
            registryDataPacketsById.getValue(biomeRegistryId).entries
                .single { registryEntry -> registryEntry.id == biomeRegistryEntryId }
                .data,
        )
        assertIs<NbtCompound>(
            registryDataPacketsById.getValue(damageTypeRegistryId).entries
                .single { registryEntry -> registryEntry.id == damageTypeRegistryEntryId }
                .data,
        )
    }

    @Test
    fun callerProjectorsCanAddModRegistriesAfterVanillaDefaults() {
        val spellRegistryId = Identifier("example:spell")
        val spellEntryId = Identifier("example:blink")
        val dataPack = DataPack(
            dataPackId = DataPackId("mod-registry-projector-test"),
            dataPackMetadata = null,
            dataPackFileContentsByPath = mapOf(
                DataPackFilePath("data/example/spell/blink.json") to DataPackFileContent.JsonFile(
                    JsonPrimitive("disk"),
                ),
            ),
        )

        val resolvedProtocolData = DataPackStack(dataPack).toVanillaProtocolData(
            dataPackRegistryProjectorOverrides = listOf(
                DataPackRegistryProjector(spellRegistryId) { _, _, _ -> NbtString("network") },
            ),
        )

        val spellRegistryDataPacket = resolvedProtocolData.synchronizedRegistryPackets(emptyList())
            .single { registryDataPacket -> registryDataPacket.registryId == spellRegistryId }
        assertEquals(listOf(spellEntryId), spellRegistryDataPacket.entries.map { registryEntry -> registryEntry.id })
        assertEquals(NbtString("network"), spellRegistryDataPacket.entries.single().data)
    }

    @Test
    fun unifiedDefaultsReachClientRegistryViewWithoutParsingTheArchive() {
        val dataPackConfigurationSnapshot = VanillaProtocolData.dataPackConfigurationSnapshot
        val clientRegistryView = VanillaProtocolData.clientRegistryView

        assertEquals(VanillaProtocolData.offeredKnownPacks, dataPackConfigurationSnapshot.offeredKnownPacks)
        assertEquals(
            VanillaProtocolData.completeProtocolRegistryContext.registries,
            clientRegistryView.protocolRegistryContext.registries,
        )
        assertTrue(clientRegistryView.clientRegistryTags.isNotEmpty())
    }

    private fun overlayDataPack(
        registryIds: List<Identifier>,
    ): Pair<DataPack, Map<Identifier, Identifier>> {
        val resolvedCoreDataPackStack = VanillaDataPacks.coreDataPackStack.resolve(
            VanillaDataPacks.dataPackFormatVersion,
        )
        val registryEntryIdsByRegistryId = linkedMapOf<Identifier, Identifier>()
        val dataPackFileContentsByPath = registryIds.associate { registryId ->
            val (dataPackResourceId, resolvedDataPackResource) = resolvedCoreDataPackStack
                .resources(DataPackResourceType(registryId.path))
                .entries.first()
            registryEntryIdsByRegistryId[registryId] = Identifier(
                dataPackResourceId.namespace,
                dataPackResourceId.path,
            )
            resolvedDataPackResource.sourceDataPackFilePath to resolvedDataPackResource.dataPackFileContent
        }
        return DataPack(
            dataPackId = DataPackId("vanilla-default-projector-test"),
            dataPackMetadata = null,
            dataPackFileContentsByPath = dataPackFileContentsByPath,
        ) to registryEntryIdsByRegistryId
    }
}
