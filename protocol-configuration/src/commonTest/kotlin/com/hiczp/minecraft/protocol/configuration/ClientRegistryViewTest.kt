package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.*

class ClientRegistryViewTest {
    @Test
    fun loaderMappingAndCallerSchemasResolveReceivedBlockTags() {
        val firstBlockId = Identifier("mod:first")
        val secondBlockId = Identifier("mod:second")
        val biomeRegistryId = PacketCodecContext.BIOME_REGISTRY
        val biomeId = MinecraftBiomeIds.PLAINS
        val blockRegistryId = StaticRegistrySchema.BLOCK_REGISTRY
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(blockRegistryId to listOf(firstBlockId, secondBlockId)),
            blocks = listOf(block(firstBlockId), block(secondBlockId)),
        )
        val resolvedConfigurationData = ResolvedConfigurationData(
            offeredKnownPacks = emptyList(),
            enabledFeatureFlags = emptySet(),
            completeSynchronizedRegistryPackets = listOf(
                ClientboundRegistryDataPacket(biomeRegistryId, listOf(RegistryEntry(biomeId, NbtString("biome")))),
            ),
            registryTags = emptyList(),
            staticRegistrySchema = staticRegistrySchema,
        )
        val dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
            offeredKnownPacks = emptyList(),
            enabledFeatureFlags = emptySet(),
            synchronizedRegistryPackets = resolvedConfigurationData.synchronizedRegistryPackets(emptyList()),
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
            resolvedConfigurationData,
            staticRegistrySchema,
            remoteRegistrySnapshot,
        )

        assertEquals(
            listOf(secondBlockId, firstBlockId),
            clientRegistryView.packetCodecContext.blockStates.map { it.block },
        )
        assertEquals(
            secondBlockId,
            clientRegistryView.tag(blockRegistryId, Identifier("mod:selected"))
                ?.registryIdMapEntries
                ?.single()
                ?.id,
        )
    }

    @Test
    fun manuallyConstructedViewRetainsTagEntryLists() {
        val registryId = Identifier("test:registry")
        val tagId = Identifier("test:tag")
        val registryIdMapping = RegistryIdMapping(Identifier("test:value"), 0)
        val mutableRegistryEntries = mutableListOf(registryIdMapping)
        val dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
            emptyList(),
            emptySet(),
            emptyList(),
            emptyList(),
        )
        val packetCodecContext = PacketCodecContext(
            registries = listOf(RegistryIdMap(registryId, listOf(registryIdMapping))),
            blockStates = emptyList(),
        )

        val clientRegistryTags = listOf(ClientRegistryTag(registryId, tagId, mutableRegistryEntries))
        val clientRegistryView = ClientRegistryView(
            dataPackConfigurationSnapshot,
            packetCodecContext,
            clientRegistryTags,
        )

        assertSame(clientRegistryTags, clientRegistryView.clientRegistryTags)
        assertSame(mutableRegistryEntries, clientRegistryView.clientRegistryTags.single().registryIdMapEntries)
        mutableRegistryEntries.clear()

        assertTrue(assertNotNull(clientRegistryView.tag(registryId, tagId)).registryIdMapEntries.isEmpty())
    }

    @Test
    fun resolvedConfigurationDataRetainsCallerOwnedCollections() {
        val offeredKnownPacks = mutableListOf<KnownPack>()
        val enabledFeatureFlags = mutableSetOf<Identifier>()
        val synchronizedRegistryPackets = mutableListOf<ClientboundRegistryDataPacket>()
        val registryTags = mutableListOf<RegistryTags>()
        val resolvedConfigurationData = ResolvedConfigurationData(
            offeredKnownPacks = offeredKnownPacks,
            enabledFeatureFlags = enabledFeatureFlags,
            completeSynchronizedRegistryPackets = synchronizedRegistryPackets,
            registryTags = registryTags,
            staticRegistrySchema = StaticRegistrySchema.Empty,
        )

        assertSame(offeredKnownPacks, resolvedConfigurationData.offeredKnownPacks)
        assertSame(enabledFeatureFlags, resolvedConfigurationData.enabledFeatureFlags)
        assertSame(synchronizedRegistryPackets, resolvedConfigurationData.completeSynchronizedRegistryPackets)
        assertSame(synchronizedRegistryPackets, resolvedConfigurationData.knownPackSynchronizedRegistryPackets)
        assertSame(registryTags, resolvedConfigurationData.registryTags)
        assertEquals(
            resolvedConfigurationData,
            ResolvedConfigurationData(
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

    @Test
    fun resolvedConfigurationDataKeepsCompleteAndKnownPackBranchesIndependent() {
        val knownPack = KnownPack("test", "branch", "1")
        val completeRegistryPackets = listOf(
            ClientboundRegistryDataPacket(
                Identifier("test:complete"),
                listOf(RegistryEntry(Identifier("test:full"), NbtString("full"))),
            ),
        )
        val knownPackRegistryPackets = listOf(
            ClientboundRegistryDataPacket(
                Identifier("test:compact"),
                listOf(RegistryEntry(Identifier("test:known"), null)),
            ),
        )

        val resolvedConfigurationData = ResolvedConfigurationData(
            offeredKnownPacks = listOf(knownPack),
            enabledFeatureFlags = emptySet(),
            completeSynchronizedRegistryPackets = completeRegistryPackets,
            knownPackSynchronizedRegistryPackets = knownPackRegistryPackets,
            registryTags = emptyList(),
            staticRegistrySchema = StaticRegistrySchema.Empty,
        )

        assertSame(completeRegistryPackets, resolvedConfigurationData.synchronizedRegistryPackets(emptyList()))
        assertSame(knownPackRegistryPackets, resolvedConfigurationData.synchronizedRegistryPackets(listOf(knownPack)))
    }

    private fun block(id: Identifier): StaticBlockSchema = StaticBlockSchema(
        id,
        listOf(StaticBlockState(emptyMap(), true)),
    )
}
