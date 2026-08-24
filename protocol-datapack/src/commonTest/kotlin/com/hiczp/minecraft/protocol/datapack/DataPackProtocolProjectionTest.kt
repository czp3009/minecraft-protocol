package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.packet.FeatureFlagsPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DataPackProtocolProjectionTest {
    private val biomeRegistry = Identifier("worldgen/biome")
    private val plains = Identifier("plains")

    @Test
    fun refusesToGuessCustomRegistryNetworkCodec() {
        val stack = DataPackStack(registryPack("custom", "custom_biome", "projected"))

        val failure = assertFailsWith<MissingDataPackRegistryProjectors> {
            DataPackProtocolProjection(base = baseData()).project(stack)
        }

        assertEquals(
            listOf(DataPackResourceId("test", "custom_biome")),
            failure.resourcesByRegistry.getValue(biomeRegistry),
        )
    }

    @Test
    fun callerProjectorOverlaysRegistryAndGenericTagProjection() {
        val registryPack = registryPack("custom", "custom_biome", "projected")
        val tagPack = DataPack(
            id = DataPackId("tags"),
            metadata = metadata(),
            files = mapOf(
                DataPackPath("data/test/tags/worldgen/biome/available.json") to DataPackFileContent.JsonFile(
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
        val projection = DataPackProtocolProjection(
            base = baseData(),
            registryProjectors = listOf(
                DataPackSynchronizedRegistryProjector(biomeRegistry) { _, resource, _ ->
                    val json = assertIs<DataPackFileContent.JsonFile>(resource.content).element.jsonObject
                    NbtString(json.getValue("network").jsonPrimitive.content)
                },
            ),
        )

        val data = projection.project(DataPackStack(registryPack, tagPack))

        val registry = data.registryPackets(emptyList()).single()
        assertEquals(listOf(plains, Identifier("test:custom_biome")), registry.entries.map(RegistryEntry::id))
        assertEquals(NbtString("projected"), registry.entries.last().data)
        val tag = data.tags.registries.single().tags.single { it.name == Identifier("test:available") }
        assertEquals(listOf(0, 1), tag.entries)
    }

    @Test
    fun filterCanRemovePreprojectedBaseEntryWithoutAValueProjector() {
        val filteringPack = DataPack(
            id = DataPackId("filter"),
            metadata = metadata(
                filters = listOf(
                    DataPackFilterPattern(
                        namespacePattern = "minecraft",
                        pathPattern = "worldgen/biome/plains\\.json",
                    ),
                ),
            ),
            files = mapOf(
                DataPackPath("data/test/recipe/marker.json") to DataPackFileContent.JsonFile(JsonPrimitive(1)),
            ),
        )

        val data = DataPackProtocolProjection(base = baseData()).project(DataPackStack(filteringPack))

        assertTrue(data.registryPackets(emptyList()).single().entries.isEmpty())
    }

    @Test
    fun remapsRetainedBaseTagRawIdsAfterRegistryFiltering() {
        val forest = Identifier("forest")
        val filteringPack = DataPack(
            id = DataPackId("filter"),
            metadata = metadata(
                filters = listOf(
                    DataPackFilterPattern(
                        namespacePattern = "minecraft",
                        pathPattern = "worldgen/biome/plains\\.json",
                    ),
                ),
            ),
            files = mapOf(
                DataPackPath("data/test/recipe/marker.json") to DataPackFileContent.JsonFile(JsonPrimitive(1)),
            ),
        )
        val base = baseData(
            entries = listOf(
                RegistryEntry(plains, NbtString("plains")),
                RegistryEntry(forest, NbtString("forest")),
            ),
            tagEntries = listOf(1),
        )

        val data = DataPackProtocolProjection(base = base).project(DataPackStack(filteringPack))

        assertEquals(listOf(forest), data.registryPackets(emptyList()).single().entries.map(RegistryEntry::id))
        assertEquals(listOf(0), data.tags.registries.single().tags.single().entries)
    }

    @Test
    fun filterRemovesExternalBaseTagEvenWhenThePackSuppliesAReplacement() {
        val tagPath = DataPackPath("data/minecraft/tags/worldgen/biome/overworld.json")
        val replacingPack = DataPack(
            id = DataPackId("replace-tag"),
            metadata = metadata(
                filters = listOf(
                    DataPackFilterPattern(
                        namespacePattern = "minecraft",
                        pathPattern = "tags/worldgen/biome/overworld\\.json",
                    ),
                ),
            ),
            files = mapOf(
                tagPath to DataPackFileContent.JsonFile(
                    buildJsonObject { put("values", buildJsonArray {}) },
                ),
            ),
        )

        val data = DataPackProtocolProjection(base = baseData()).project(DataPackStack(replacingPack))

        assertTrue(data.tags.registries.single().tags.single().entries.isEmpty())
    }

    private fun registryPack(id: String, resourceId: String, projected: String): DataPack = DataPack(
        id = DataPackId(id),
        metadata = metadata(),
        files = mapOf(
            DataPackPath("data/test/worldgen/biome/$resourceId.json") to DataPackFileContent.JsonFile(
                buildJsonObject { put("network", projected) },
            ),
        ),
    )

    private fun baseData(
        entries: List<RegistryEntry> = listOf(RegistryEntry(plains, NbtString("base"))),
        tagEntries: List<Int> = listOf(0),
    ): DataPackProtocolDataSet = DataPackProtocolDataSet(
        minecraftVersion = "test",
        protocolVersion = 1,
        knownPacks = emptyList(),
        featureFlags = FeatureFlagsPacket(emptySet()),
        completeRegistries = listOf(
            RegistryDataPacket(
                biomeRegistry,
                entries,
            ),
        ),
        tags = ConfigurationUpdateTagsPacket(
            listOf(
                RegistryTags(
                    biomeRegistry,
                    listOf(TagDefinition(Identifier("overworld"), tagEntries)),
                ),
            ),
        ),
        staticRegistries = StaticRegistrySchema.Empty,
    )

    private fun metadata(
        filters: List<DataPackFilterPattern> = emptyList(),
    ): DataPackMetadata = DataPackMetadata(
        description = JsonPrimitive("test"),
        formats = DataPackFormatRange.exact(DataPackFormatVersion(1)),
        filters = filters,
    )
}
