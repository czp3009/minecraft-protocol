package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class DataPackProtocolProjectorTest {
    private val biomeRegistry = Identifier("worldgen/biome")
    private val plains = MinecraftBiomeIds.PLAINS

    @Test
    fun refusesToGuessCustomRegistryNetworkCodec() {
        val dataPackStack = DataPackStack(registryDataPack("custom", "custom_biome", "projected"))

        val failure = assertFailsWith<MissingDataPackRegistryProjectorsException> {
            DataPackProtocolProjector(baseProtocolData = baseProtocolData()).project(dataPackStack)
        }

        assertEquals(
            listOf(DataPackResourceId("test", "custom_biome")),
            failure.unprojectedResourceIdsByRegistryId.getValue(biomeRegistry),
        )
    }

    @Test
    fun callerProjectorOverlaysRegistryAndGenericTagProjection() {
        val registryDataPack = registryDataPack("custom", "custom_biome", "projected")
        val tagDataPack = DataPack(
            dataPackId = DataPackId("tags"),
            dataPackMetadata = dataPackMetadata(),
            dataPackFileContentsByPath = mapOf(
                DataPackFilePath("data/test/tags/worldgen/biome/available.json") to DataPackFileContent.JsonFile(
                    buildJsonObject {
                        put(
                            "values",
                            buildJsonArray {
                                add("minecraft:plains")
                                add("test:custom_biome")
                            },
                        )
                    },
                ),
            ),
        )
        val dataPackProtocolProjector = DataPackProtocolProjector(
            baseProtocolData = baseProtocolData(),
            dataPackRegistryProjectors = listOf(
                DataPackRegistryProjector(biomeRegistry) { _, resolvedDataPackResource, _ ->
                    val registryEntryJson = assertIs<DataPackFileContent.JsonFile>(
                        resolvedDataPackResource.dataPackFileContent,
                    ).jsonElement.jsonObject
                    NbtString(registryEntryJson.getValue("network").jsonPrimitive.content)
                },
            ),
        )

        val resolvedProtocolData = dataPackProtocolProjector.project(
            DataPackStack(registryDataPack, tagDataPack),
        )

        val registryDataPacket = resolvedProtocolData.synchronizedRegistryPackets(emptyList()).single()
        assertEquals(
            listOf(plains, Identifier("test:custom_biome")),
            registryDataPacket.entries.map(RegistryEntry::id),
        )
        assertEquals(NbtString("projected"), registryDataPacket.entries.last().data)
        val tagDefinition = resolvedProtocolData.registryTags.single().tags.single {
            it.name == Identifier("test:available")
        }
        assertEquals(listOf(0, 1), tagDefinition.entries)
    }

    @Test
    fun callerProjectorFailureIncludesRegistryAndResourceContext() {
        val cause = IllegalStateException("projector failed")
        val dataPackProtocolProjector = DataPackProtocolProjector(
            baseProtocolData = baseProtocolData(),
            dataPackRegistryProjectors = listOf(
                DataPackRegistryProjector(biomeRegistry) { _, _, _ -> throw cause },
            ),
        )

        val failure = assertFailsWith<DataPackRegistryProjectionException> {
            dataPackProtocolProjector.project(
                DataPackStack(registryDataPack("custom", "custom_biome", "projected")),
            )
        }

        assertEquals(biomeRegistry, failure.registryId)
        assertEquals(Identifier("test:custom_biome"), failure.registryEntryId)
        assertEquals(DataPackId("custom"), failure.sourceDataPackId)
        assertEquals(
            DataPackFilePath("data/test/worldgen/biome/custom_biome.json"),
            failure.sourceDataPackFilePath,
        )
        assertSame(cause, failure.cause)
    }

    @Test
    fun callerProjectorDoesNotInterceptCancellation() {
        val cancellationException = CancellationException("cancelled")
        val dataPackProtocolProjector = DataPackProtocolProjector(
            baseProtocolData = baseProtocolData(),
            dataPackRegistryProjectors = listOf(
                DataPackRegistryProjector(biomeRegistry) { _, _, _ -> throw cancellationException },
            ),
        )

        val thrown = assertFailsWith<CancellationException> {
            dataPackProtocolProjector.project(
                DataPackStack(registryDataPack("custom", "custom_biome", "projected")),
            )
        }

        assertSame(cancellationException, thrown)
    }

    @Test
    fun resolvedMalformedTagFailureIncludesProjectionContext() {
        val dataPackId = DataPackId("invalid-tags")
        val dataPackFilePath = DataPackFilePath("data/test/tags/worldgen/biome/invalid.json")
        val dataPackResourcePath = DataPackResourcePath("test", "tags/worldgen/biome/invalid.json")
        val resolvedDataPackStack = ResolvedDataPackStack(
            dataPackIds = listOf(dataPackId),
            resolvedDataPackResources = mapOf(
                dataPackResourcePath to ResolvedDataPackResource(
                    dataPackResourcePath = dataPackResourcePath,
                    dataPackFileContent = DataPackFileContent.JsonFile(
                        buildJsonObject { put("values", buildJsonArray { add(buildJsonArray {}) }) },
                    ),
                    sourceDataPackId = dataPackId,
                    sourceDataPackFilePath = dataPackFilePath,
                ),
            ),
        )

        val failure = assertFailsWith<DataPackTagProjectionException> {
            DataPackProtocolProjector(baseProtocolData = baseProtocolData()).project(resolvedDataPackStack)
        }

        assertContains(failure.message.orEmpty(), "test:invalid")
        assertContains(failure.message.orEmpty(), biomeRegistry.value)
        assertIs<DataPackFormatException>(failure.cause)
        assertContains(failure.cause?.message.orEmpty(), dataPackFilePath.value)
        assertContains(failure.cause?.message.orEmpty(), dataPackId.value)
    }

    @Test
    fun filterCanRemovePreprojectedBaseEntryWithoutAValueProjector() {
        val filteringDataPack = DataPack(
            dataPackId = DataPackId("filter"),
            dataPackMetadata = dataPackMetadata(
                dataPackFilterPatterns = listOf(
                    DataPackFilterPattern(
                        namespacePattern = "minecraft",
                        pathPattern = "worldgen/biome/plains\\.json",
                    ),
                ),
            ),
            dataPackFileContentsByPath = mapOf(
                DataPackFilePath("data/test/recipe/marker.json") to DataPackFileContent.JsonFile(JsonPrimitive(1)),
            ),
        )

        val resolvedProtocolData = DataPackProtocolProjector(
            baseProtocolData = baseProtocolData(),
        ).project(DataPackStack(filteringDataPack))

        assertTrue(resolvedProtocolData.synchronizedRegistryPackets(emptyList()).single().entries.isEmpty())
    }

    @Test
    fun remapsRetainedBaseTagRawIdsAfterRegistryFiltering() {
        val forest = Identifier("forest")
        val filteringDataPack = DataPack(
            dataPackId = DataPackId("filter"),
            dataPackMetadata = dataPackMetadata(
                dataPackFilterPatterns = listOf(
                    DataPackFilterPattern(
                        namespacePattern = "minecraft",
                        pathPattern = "worldgen/biome/plains\\.json",
                    ),
                ),
            ),
            dataPackFileContentsByPath = mapOf(
                DataPackFilePath("data/test/recipe/marker.json") to DataPackFileContent.JsonFile(JsonPrimitive(1)),
            ),
        )
        val baseProtocolData = baseProtocolData(
            registryEntries = listOf(
                RegistryEntry(plains, NbtString("plains")),
                RegistryEntry(forest, NbtString("forest")),
            ),
            tagRawIds = listOf(1),
        )

        val resolvedProtocolData = DataPackProtocolProjector(baseProtocolData = baseProtocolData)
            .project(DataPackStack(filteringDataPack))

        assertEquals(
            listOf(forest),
            resolvedProtocolData.synchronizedRegistryPackets(emptyList()).single().entries.map(RegistryEntry::id),
        )
        assertEquals(listOf(0), resolvedProtocolData.registryTags.single().tags.single().entries)
    }

    @Test
    fun filterRemovesExternalBaseTagEvenWhenThePackSuppliesAReplacement() {
        val dataPackTagFilePath = DataPackFilePath("data/minecraft/tags/worldgen/biome/overworld.json")
        val replacingDataPack = DataPack(
            dataPackId = DataPackId("replace-tag"),
            dataPackMetadata = dataPackMetadata(
                dataPackFilterPatterns = listOf(
                    DataPackFilterPattern(
                        namespacePattern = "minecraft",
                        pathPattern = "tags/worldgen/biome/overworld\\.json",
                    ),
                ),
            ),
            dataPackFileContentsByPath = mapOf(
                dataPackTagFilePath to DataPackFileContent.JsonFile(
                    buildJsonObject { put("values", buildJsonArray {}) },
                ),
            ),
        )

        val resolvedProtocolData = DataPackProtocolProjector(
            baseProtocolData = baseProtocolData(),
        ).project(DataPackStack(replacingDataPack))

        assertTrue(resolvedProtocolData.registryTags.single().tags.single().entries.isEmpty())
    }

    private fun registryDataPack(
        dataPackId: String,
        dataPackResourceId: String,
        projectedValue: String,
    ): DataPack = DataPack(
        dataPackId = DataPackId(dataPackId),
        dataPackMetadata = dataPackMetadata(),
        dataPackFileContentsByPath = mapOf(
            DataPackFilePath("data/test/worldgen/biome/$dataPackResourceId.json") to DataPackFileContent.JsonFile(
                buildJsonObject { put("network", projectedValue) },
            ),
        ),
    )

    private fun baseProtocolData(
        registryEntries: List<RegistryEntry> = listOf(RegistryEntry(plains, NbtString("base"))),
        tagRawIds: List<Int> = listOf(0),
    ): ResolvedProtocolData = ResolvedProtocolData(
        offeredKnownPacks = emptyList(),
        enabledFeatureFlags = emptySet(),
        completeSynchronizedRegistryPackets = listOf(
            RegistryDataPacket(
                biomeRegistry,
                registryEntries,
            ),
        ),
        registryTags = listOf(
            RegistryTags(
                biomeRegistry,
                listOf(TagDefinition(Identifier("overworld"), tagRawIds)),
            ),
        ),
        staticRegistrySchema = StaticRegistrySchema.Empty,
    )

    private fun dataPackMetadata(
        dataPackFilterPatterns: List<DataPackFilterPattern> = emptyList(),
    ): DataPackMetadata = DataPackMetadata(
        description = JsonPrimitive("test"),
        supportedDataPackFormatVersionRange = DataPackFormatVersionRange.exact(DataPackFormatVersion(1)),
        dataPackFilterPatterns = dataPackFilterPatterns,
    )
}
