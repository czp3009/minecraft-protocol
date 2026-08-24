package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.packet.FeatureFlagsPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClientDataPackRuntimeTest {
    @Test
    fun loaderMappingAndCallerSchemasResolveReceivedBlockTags() {
        val first = Identifier("mod:first")
        val second = Identifier("mod:second")
        val biomeRegistry = ProtocolRegistryContext.BIOME_REGISTRY
        val biome = Identifier("minecraft:plains")
        val blockRegistry = StaticRegistrySchema.BLOCK_REGISTRY
        val static = StaticRegistrySchema(
            registries = mapOf(blockRegistry to listOf(first, second)),
            blocks = listOf(block(first), block(second)),
        )
        val protocolData = DataPackProtocolDataSet(
            minecraftVersion = "test",
            protocolVersion = 1,
            knownPacks = emptyList(),
            featureFlags = FeatureFlagsPacket(emptySet()),
            completeRegistries = listOf(
                RegistryDataPacket(biomeRegistry, listOf(RegistryEntry(biome, NbtString("biome")))),
            ),
            tags = ConfigurationUpdateTagsPacket(emptyList()),
            staticRegistries = static,
        )
        val received = ReceivedDataPackConfiguration(
            knownPacks = emptyList(),
            featureFlags = emptySet(),
            registries = protocolData.registryPackets(emptyList()),
            tags = listOf(
                RegistryTags(
                    blockRegistry,
                    listOf(TagDefinition(Identifier("mod:selected"), listOf(0))),
                ),
            ),
        )
        val remote = RemoteRegistrySnapshot(
            listOf(
                RemoteRegistry(
                    blockRegistry,
                    listOf(
                        RemoteRegistryEntry(second, 0),
                        RemoteRegistryEntry(first, 1),
                    ),
                ),
            ),
        )

        val runtime = received.resolveRuntime(protocolData, static, remote)

        assertEquals(listOf(second, first), runtime.registryContext.blockStates.map { it.block })
        assertEquals(
            second,
            runtime.tag(blockRegistry, Identifier("mod:selected"))?.entries?.single()?.id,
        )
    }

    @Test
    fun manuallyConstructedRuntimeSnapshotsTagEntryLists() {
        val registryId = Identifier("test:registry")
        val tagId = Identifier("test:tag")
        val entry = ProtocolRegistryEntry(Identifier("test:value"), 0)
        val mutableEntries = mutableListOf(entry)
        val configuration = ReceivedDataPackConfiguration(emptyList(), emptySet(), emptyList(), emptyList())
        val context = ProtocolRegistryContext(
            registries = listOf(ProtocolRegistry(registryId, listOf(entry))),
            blockStates = emptyList(),
        )

        val runtime = ClientDataPackRuntime(
            configuration,
            context,
            listOf(ClientDataPackTag(registryId, tagId, mutableEntries)),
        )
        mutableEntries.clear()

        assertEquals(entry, assertNotNull(runtime.tag(registryId, tagId)).entries.single())
    }

    private fun block(id: Identifier): StaticBlockSchema = StaticBlockSchema(
        id,
        listOf(StaticBlockState(emptyMap(), true)),
    )
}
