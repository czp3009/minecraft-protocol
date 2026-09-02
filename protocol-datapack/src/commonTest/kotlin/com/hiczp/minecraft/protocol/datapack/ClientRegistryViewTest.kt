package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.*

class ClientRegistryViewTest {
    @Test
    fun loaderMappingAndCallerSchemasResolveReceivedBlockTags() {
        val firstBlockId = Identifier("mod:first")
        val secondBlockId = Identifier("mod:second")
        val biomeRegistryId = ProtocolRegistryContext.BIOME_REGISTRY
        val biomeId = MinecraftBiomeIds.PLAINS
        val blockRegistryId = StaticRegistrySchema.BLOCK_REGISTRY
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(blockRegistryId to listOf(firstBlockId, secondBlockId)),
            blocks = listOf(block(firstBlockId), block(secondBlockId)),
        )
        val resolvedProtocolData = ResolvedProtocolData(
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
    fun manuallyConstructedViewRetainsTagEntryLists() {
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

        val clientRegistryTags = listOf(ClientRegistryTag(registryId, tagId, mutableRegistryEntries))
        val clientRegistryView = ClientRegistryView(
            dataPackConfigurationSnapshot,
            protocolRegistryContext,
            clientRegistryTags,
        )

        assertSame(clientRegistryTags, clientRegistryView.clientRegistryTags)
        assertSame(mutableRegistryEntries, clientRegistryView.clientRegistryTags.single().protocolRegistryEntries)
        mutableRegistryEntries.clear()

        assertTrue(assertNotNull(clientRegistryView.tag(registryId, tagId)).protocolRegistryEntries.isEmpty())
    }

    @Test
    fun resolvedProtocolDataRetainsCallerOwnedCollections() {
        val offeredKnownPacks = mutableListOf<KnownPack>()
        val enabledFeatureFlags = mutableSetOf<Identifier>()
        val synchronizedRegistryPackets = mutableListOf<RegistryDataPacket>()
        val registryTags = mutableListOf<RegistryTags>()
        val resolvedProtocolData = ResolvedProtocolData(
            offeredKnownPacks = offeredKnownPacks,
            enabledFeatureFlags = enabledFeatureFlags,
            completeSynchronizedRegistryPackets = synchronizedRegistryPackets,
            registryTags = registryTags,
            staticRegistrySchema = StaticRegistrySchema.Empty,
        )

        assertSame(offeredKnownPacks, resolvedProtocolData.offeredKnownPacks)
        assertSame(enabledFeatureFlags, resolvedProtocolData.enabledFeatureFlags)
        assertSame(synchronizedRegistryPackets, resolvedProtocolData.completeSynchronizedRegistryPackets)
        assertSame(synchronizedRegistryPackets, resolvedProtocolData.knownPackSynchronizedRegistryPackets)
        assertSame(registryTags, resolvedProtocolData.registryTags)
        assertEquals(
            resolvedProtocolData,
            ResolvedProtocolData(
                offeredKnownPacks = offeredKnownPacks,
                enabledFeatureFlags = enabledFeatureFlags,
                completeSynchronizedRegistryPackets = synchronizedRegistryPackets,
                registryTags = registryTags,
                staticRegistrySchema = StaticRegistrySchema.Empty,
            ),
        )
        assertEquals(
            DataPackConfigurationSnapshot(emptyList(), emptySet(), emptyList(), emptyList()),
            DataPackConfigurationSnapshot(emptyList(), emptySet(), emptyList(), emptyList()),
        )
    }

    private fun block(id: Identifier): StaticBlockSchema = StaticBlockSchema(
        id,
        listOf(StaticBlockState(emptyMap(), true)),
    )
}
