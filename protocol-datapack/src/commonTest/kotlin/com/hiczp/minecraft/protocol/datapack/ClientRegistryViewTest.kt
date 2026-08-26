package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClientRegistryViewTest {
    @Test
    fun loaderMappingAndCallerSchemasResolveReceivedBlockTags() {
        val firstBlockId = Identifier("mod:first")
        val secondBlockId = Identifier("mod:second")
        val biomeRegistryId = ProtocolRegistryContext.BIOME_REGISTRY
        val biomeId = Identifier("minecraft:plains")
        val blockRegistryId = StaticRegistrySchema.BLOCK_REGISTRY
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(blockRegistryId to listOf(firstBlockId, secondBlockId)),
            blocks = listOf(block(firstBlockId), block(secondBlockId)),
        )
        val resolvedProtocolData = ResolvedProtocolData(
            minecraftVersion = "test",
            protocolVersion = 1,
            offeredKnownPacks = emptyList(),
            enabledFeatureFlags = emptySet(),
            completeSynchronizedRegistryPackets = listOf(
                RegistryDataPacket(biomeRegistryId, listOf(RegistryEntry(biomeId, NbtString("biome")))),
            ),
            registryTags = emptyList(),
            staticRegistrySchema = staticRegistrySchema,
        )
        val dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
            offeredKnownPacks = emptyList(),
            enabledFeatureFlags = emptySet(),
            synchronizedRegistryPackets = resolvedProtocolData.synchronizedRegistryPackets(emptyList()),
            registryTags = listOf(
                RegistryTags(
                    blockRegistryId,
                    listOf(TagDefinition(Identifier("mod:selected"), listOf(0))),
                ),
            ),
        )
        val remoteRegistrySnapshot = RemoteRegistrySnapshot(
            listOf(
                RemoteRegistry(
                    blockRegistryId,
                    listOf(
                        RemoteRegistryEntry(secondBlockId, 0),
                        RemoteRegistryEntry(firstBlockId, 1),
                    ),
                ),
            ),
        )

        val clientRegistryView = dataPackConfigurationSnapshot.resolveClientRegistryView(
            resolvedProtocolData,
            staticRegistrySchema,
            remoteRegistrySnapshot,
        )

        assertEquals(
            listOf(secondBlockId, firstBlockId),
            clientRegistryView.protocolRegistryContext.blockStates.map { it.block },
        )
        assertEquals(
            secondBlockId,
            clientRegistryView.tag(blockRegistryId, Identifier("mod:selected"))
                ?.protocolRegistryEntries
                ?.single()
                ?.id,
        )
    }

    @Test
    fun manuallyConstructedViewSnapshotsTagEntryLists() {
        val registryId = Identifier("test:registry")
        val tagId = Identifier("test:tag")
        val protocolRegistryEntry = ProtocolRegistryEntry(Identifier("test:value"), 0)
        val mutableRegistryEntries = mutableListOf(protocolRegistryEntry)
        val dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
            emptyList(),
            emptySet(),
            emptyList(),
            emptyList(),
        )
        val protocolRegistryContext = ProtocolRegistryContext(
            registries = listOf(ProtocolRegistry(registryId, listOf(protocolRegistryEntry))),
            blockStates = emptyList(),
        )

        val clientRegistryView = ClientRegistryView(
            dataPackConfigurationSnapshot,
            protocolRegistryContext,
            listOf(ClientRegistryTag(registryId, tagId, mutableRegistryEntries)),
        )
        mutableRegistryEntries.clear()

        assertEquals(
            protocolRegistryEntry,
            assertNotNull(clientRegistryView.tag(registryId, tagId)).protocolRegistryEntries.single(),
        )
    }

    private fun block(id: Identifier): StaticBlockSchema = StaticBlockSchema(
        id,
        listOf(StaticBlockState(emptyMap(), true)),
    )
}
