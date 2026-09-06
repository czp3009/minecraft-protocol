package com.hiczp.minecraft.protocol.configuration.vanilla

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.configuration.DataPackRegistryProjector
import com.hiczp.minecraft.protocol.configuration.resolveWorldChunkContexts
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.data.WorldGenDimension
import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData
import com.hiczp.minecraft.world.format.datapack.*
import com.hiczp.minecraft.world.format.datapack.vanilla.VanillaDataPacks
import com.hiczp.minecraft.world.format.datapack.vanilla.toVanillaDataPackStack
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VanillaConfigurationProjectionTest {
    @Test
    fun coreDefaultProjectsToExactCapturedVanillaConfiguration() {
        val resolvedConfigurationData = VanillaDataPacks.coreDataPackStack.toVanillaConfigurationData()

        assertEquals(VanillaConfigurationData.offeredKnownPacks, resolvedConfigurationData.offeredKnownPacks)
        assertEquals(VanillaConfigurationData.enabledFeatureFlags, resolvedConfigurationData.enabledFeatureFlags)
        assertEquals(
            VanillaConfigurationData.synchronizedRegistryPackets(emptyList()),
            resolvedConfigurationData.synchronizedRegistryPackets(emptyList()),
        )
        assertEquals(VanillaConfigurationData.registryTags, resolvedConfigurationData.registryTags)
    }

    @Test
    fun defaultProjectorsCoverEverySynchronizedVanillaRegistry() {
        assertEquals(
            VanillaConfigurationData.synchronizedRegistryPackets(emptyList()).map { clientboundRegistryDataPacket ->
                clientboundRegistryDataPacket.registry
            },
            VanillaConfigurationData.dataPackRegistryProjectors.map(DataPackRegistryProjector::registryId),
        )
    }

    @Test
    fun vanillaRegistryResourcesNeedNoCallerProjectors() {
        val biomeRegistryId = Identifier("worldgen/biome")
        val damageTypeRegistryId = Identifier("damage_type")
        val (dataPack, registryEntryIdsByRegistryId) = overlayDataPack(
            listOf(biomeRegistryId, damageTypeRegistryId),
        )

        val resolvedConfigurationData = DataPackStack(dataPack).toVanillaConfigurationData()

        registryEntryIdsByRegistryId.forEach { (registryId, registryEntryId) ->
            val registryEntry = resolvedConfigurationData.synchronizedRegistryPackets(emptyList())
                .single { clientboundRegistryDataPacket -> clientboundRegistryDataPacket.registry == registryId }
                .entries.single { candidate -> candidate.id == registryEntryId }
            assertIs<NbtCompound>(registryEntry.data)
        }
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
        val resolvedConfigurationData = dataPackStack.toVanillaConfigurationData(
            enabledFeatureFlags = VanillaConfigurationData.enabledFeatureFlags +
                    worldDataPackLoadResult.enabledFeatureFlags.map { Identifier(it) },
        )

        assertEquals(
            listOf(VanillaDataPacks.coreDataPackId, fileDataPack.dataPackId),
            dataPackStack.dataPacks.map(DataPack::dataPackId),
        )
        assertTrue(additionalFeatureFlag in resolvedConfigurationData.enabledFeatureFlags)
    }

    @Test
    fun persistedWorldSelectionFlowsIntoAReadyChunkContext() {
        val worldDataPackLoadResult = WorldDataPackLoadResult(
            enabledDataPackIds = listOf(VanillaDataPacks.coreDataPackId),
            loadedDataPacks = emptyList(),
        )
        val worldGenSettingsData = WorldGenSettingsData(
            seed = 1L,
            generateStructures = true,
            bonusChest = false,
            dimensions = mapOf(
                DimensionId.Overworld to WorldGenDimension(
                    type = WorldGenDimensionType.Reference(DimensionTypeId("overworld")),
                    generator = NbtCompound(emptyMap()),
                ),
            ),
        )

        val dataPackStack = worldDataPackLoadResult.toVanillaDataPackStack()
        val resolvedConfigurationData = dataPackStack.toVanillaConfigurationData()
        val worldChunkContexts = resolvedConfigurationData.resolveWorldChunkContexts(
            worldGenSettingsData, BlockState(BlockId("minecraft:air")), BiomeId("minecraft:plains"),
        )
        val chunkContext = worldChunkContexts.dimension(DimensionId.Overworld)
        assertEquals(DimensionId.Overworld, chunkContext.dimensionId)
        assertTrue(chunkContext.dimensionTypeLayout.chunkLayout.sectionCount > 0)
        assertEquals(VanillaDataPacks.dataPackFormatVersion, VanillaConfigurationData.dataPackFormatVersion)
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

        val resolvedConfigurationData = DataPackStack(dataPack).toVanillaConfigurationData(
            dataPackRegistryProjectorOverrides = listOf(
                DataPackRegistryProjector(biomeRegistryId) { _, _, _ -> NbtString("custom") },
            ),
        )

        val clientboundRegistryDataPacketsById = resolvedConfigurationData.synchronizedRegistryPackets(emptyList())
            .associateBy { clientboundRegistryDataPacket -> clientboundRegistryDataPacket.registry }
        assertEquals(
            NbtString("custom"),
            clientboundRegistryDataPacketsById.getValue(biomeRegistryId).entries
                .single { registryEntry -> registryEntry.id == biomeRegistryEntryId }
                .data,
        )
        assertIs<NbtCompound>(
            clientboundRegistryDataPacketsById.getValue(damageTypeRegistryId).entries
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

        val resolvedConfigurationData = DataPackStack(dataPack).toVanillaConfigurationData(
            dataPackRegistryProjectorOverrides = listOf(
                DataPackRegistryProjector(spellRegistryId) { _, _, _ -> NbtString("network") },
            ),
        )

        val spellClientboundRegistryDataPacket = resolvedConfigurationData.synchronizedRegistryPackets(emptyList())
            .single { clientboundRegistryDataPacket -> clientboundRegistryDataPacket.registry == spellRegistryId }
        assertEquals(
            listOf(spellEntryId),
            spellClientboundRegistryDataPacket.entries.map { registryEntry -> registryEntry.id })
        assertEquals(NbtString("network"), spellClientboundRegistryDataPacket.entries.single().data)
    }

    @Test
    fun unifiedDefaultsReachClientRegistryViewWithoutParsingTheArchive() {
        val dataPackConfigurationSnapshot = VanillaConfigurationData.dataPackConfigurationSnapshot
        val clientRegistryView = VanillaConfigurationData.clientRegistryView

        assertEquals(VanillaConfigurationData.offeredKnownPacks, dataPackConfigurationSnapshot.offeredKnownPacks)
        assertEquals(
            VanillaConfigurationData.completePacketCodecContext.registries,
            clientRegistryView.packetCodecContext.registries,
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
