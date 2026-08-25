package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.completeSynchronizedRegistryPackets
import com.hiczp.minecraft.protocol.datapack.requireRegistryPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.RegistryEntry
import kotlin.test.*

class VanillaProtocolDataTest {
    @Test
    fun exposesACompleteOfficialConfigurationSnapshot() {
        val protocolData = VanillaProtocolData
        val completeSynchronizedRegistryPackets = protocolData.synchronizedRegistryPackets(emptyList())

        assertTrue(protocolData.offeredKnownPacks.isNotEmpty())
        assertTrue(protocolData.enabledFeatureFlags.isNotEmpty())
        assertTrue(completeSynchronizedRegistryPackets.isNotEmpty())
        assertTrue(completeSynchronizedRegistryPackets.all { registryDataPacket ->
            registryDataPacket.entries.all { it.data != null }
        })
        assertTrue(protocolData.registryTags.isNotEmpty())
        val biomeRegistryPacket = completeSynchronizedRegistryPackets.single { registryDataPacket ->
            registryDataPacket.registryId == Identifier("worldgen/biome")
        }
        assertEquals(
            biomeRegistryPacket.entries.map { it.id },
            protocolData.completeProtocolRegistryContext.requireRegistry(biomeRegistryPacket.registryId)
                .entries
                .sortedBy { it.rawId }
                .map { it.id },
        )
    }

    @Test
    fun exactKnownPacksSelectionUsesTheCompactOfficialBranch() {
        val protocolData = VanillaProtocolData
        val completeSynchronizedRegistryPackets = protocolData.synchronizedRegistryPackets(emptyList())
        val knownPackSynchronizedRegistryPackets =
            protocolData.synchronizedRegistryPackets(protocolData.offeredKnownPacks)

        assertEquals(
            completeSynchronizedRegistryPackets.map { it.registryId },
            knownPackSynchronizedRegistryPackets.map { it.registryId },
        )
        assertEquals(
            completeSynchronizedRegistryPackets.map { registryDataPacket ->
                registryDataPacket.entries.map { it.id }
            },
            knownPackSynchronizedRegistryPackets.map { registryDataPacket ->
                registryDataPacket.entries.map { it.id }
            },
        )
        assertTrue(knownPackSynchronizedRegistryPackets.all { registryDataPacket ->
            registryDataPacket.entries.all { it.data == null }
        })
        assertNotEquals(completeSynchronizedRegistryPackets, knownPackSynchronizedRegistryPackets)
    }

    @Test
    fun anyNonExactKnownPacksSelectionUsesCompleteData() {
        val protocolData = VanillaProtocolData
        val unknownKnownPack = KnownPack("example", "not-vanilla", "1")

        assertEquals(
            protocolData.synchronizedRegistryPackets(emptyList()),
            protocolData.synchronizedRegistryPackets(protocolData.offeredKnownPacks + unknownKnownPack),
        )
    }

    @Test
    fun exposesOfficialRegistryAndBlockStateIds() {
        val entityTypes = VanillaRegistryData.requireRegistry(
            Identifier("entity_type"),
        )
        val pig = Identifier("pig")
        val air = Identifier("air")
        val grass = Identifier("grass_block")

        assertEquals(pig, entityTypes[entityTypes.requireRawId(pig)])
        assertEquals(
            air,
            VanillaRegistryData.vanillaBlockStateRegistry.require(0).blockId,
        )
        assertEquals(
            VanillaRegistryData.requireRegistry(Identifier("block"))
                .requireRawId(grass),
            VanillaRegistryData.vanillaBlockStateRegistry.default(grass).blockId.let {
                VanillaRegistryData.requireRegistry(Identifier("block"))
                    .requireRawId(it)
            },
        )
        assertTrue(VanillaRegistryData.vanillaBlockStateRegistry.size > 1)
    }

    @Test
    fun derivesChunkContextFromSynchronizedDimensionData() {
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val clientMinecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaProtocolData.completeSynchronizedRegistryPackets(),
            minecraftDimensionLayout.dimensionTypeRawId,
        )

        assertEquals(0, minecraftDimensionLayout.minY % 16)
        assertEquals(0, minecraftDimensionLayout.height % 16)
        assertEquals(minecraftDimensionLayout.height / 16, minecraftDimensionLayout.sectionCount)
        assertTrue(minecraftDimensionLayout.hasSkyLight)
        assertEquals(minecraftDimensionLayout, clientMinecraftDimensionLayout)
        assertEquals(
            Identifier("overworld"),
            VanillaProtocolData.requireRegistryPacket(
                Identifier("dimension_type"),
            ).entries[minecraftDimensionLayout.dimensionTypeRawId].id,
        )
    }

    @Test
    fun committedRegistryAndTagSnapshotsAreStructurallyUnambiguous() {
        val completeSynchronizedRegistryPackets = VanillaProtocolData.completeSynchronizedRegistryPackets()
        val knownPackSynchronizedRegistryPackets = VanillaProtocolData.synchronizedRegistryPackets(
            VanillaProtocolData.offeredKnownPacks,
        )

        assertEquals(
            completeSynchronizedRegistryPackets.size,
            completeSynchronizedRegistryPackets.map { it.registryId }.distinct().size,
        )
        completeSynchronizedRegistryPackets.zip(knownPackSynchronizedRegistryPackets)
            .forEach { (completeRegistry, knownPackRegistry) ->
                assertEquals(completeRegistry.registryId, knownPackRegistry.registryId)
            assertEquals(
                completeRegistry.entries.size,
                completeRegistry.entries.map { it.id }.distinct().size,
            )
                assertTrue(completeRegistry.entries.all { it.data != null })
                assertTrue(knownPackRegistry.entries.all { it.data == null })
        }

        val registryTags = VanillaProtocolData.registryTags
        assertEquals(registryTags.size, registryTags.map { it.registry }.distinct().size)
        registryTags.forEach { registryTags ->
            assertEquals(
                registryTags.tags.size,
                registryTags.tags.map { it.name }.distinct().size,
            )
            val registrySize =
                VanillaRegistryData.registry(registryTags.registry)?.size
                    ?: completeSynchronizedRegistryPackets
                        .singleOrNull {
                            it.registryId == registryTags.registry
                        }
                        ?.entries
                        ?.size
            assertTrue(
                registrySize != null,
                "No registry catalogue exists for ${registryTags.registry}",
            )
            registryTags.tags.forEach { tag ->
                assertEquals(tag.entries.size, tag.entries.distinct().size)
                assertTrue(tag.entries.all { it in 0 until registrySize })
            }
        }
    }

    @Test
    fun committedRegistryDataHasBijectiveIdsAndCanonicalBlockStates() {
        VanillaRegistryData.vanillaRegistries.forEach { (registryId, vanillaRegistry) ->
            assertEquals(registryId, vanillaRegistry.registryId)
            assertEquals(
                vanillaRegistry.registryEntryIds.size,
                vanillaRegistry.registryEntryIds.distinct().size,
            )
            vanillaRegistry.registryEntryIds.forEachIndexed { rawId, registryEntryId ->
                assertEquals(rawId, vanillaRegistry.rawId(registryEntryId))
                assertEquals(registryEntryId, vanillaRegistry[rawId])
            }
            assertNull(vanillaRegistry[-1])
            assertNull(vanillaRegistry[vanillaRegistry.size])
        }

        val vanillaBlockStates = VanillaRegistryData.vanillaBlockStateRegistry.vanillaBlockStates
        vanillaBlockStates.forEachIndexed { rawId, vanillaBlockState ->
            assertEquals(rawId, vanillaBlockState.rawId)
            assertEquals(vanillaBlockState, VanillaRegistryData.vanillaBlockStateRegistry[rawId])
            assertEquals(
                vanillaBlockState,
                VanillaRegistryData.vanillaBlockStateRegistry.find(
                    vanillaBlockState.blockId,
                    vanillaBlockState.properties,
                ),
            )
        }
        vanillaBlockStates.groupBy(VanillaBlockState::blockId).forEach { (blockId, blockStates) ->
            assertEquals(1, blockStates.count(VanillaBlockState::isDefault))
            assertEquals(
                blockStates.size,
                blockStates.map(VanillaBlockState::properties).distinct().size,
            )
            assertEquals(
                blockStates.single(VanillaBlockState::isDefault),
                VanillaRegistryData.vanillaBlockStateRegistry.default(blockId),
            )
        }
        assertEquals(
            VanillaRegistryData.requireRegistry(Identifier("block"))
                .registryEntryIds
                .toSet(),
            vanillaBlockStates.map(VanillaBlockState::blockId).toSet(),
        )
    }

    @Test
    fun rejectsAmbiguousRegistryCataloguesAndMalformedDimensionData() {
        val duplicate = Identifier("test:duplicate")
        assertFailsWith<IllegalArgumentException> {
            VanillaRegistry(
                Identifier("test:registry"),
                listOf(duplicate, duplicate),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VanillaBlockStateRegistry(
                listOf(
                    VanillaBlockState(
                        rawId = 1,
                        blockId = Identifier("test:block"),
                        properties = emptyMap(),
                        isDefault = true,
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VanillaBlockStateRegistry(
                listOf(
                    VanillaBlockState(
                        rawId = 0,
                        blockId = Identifier("test:block"),
                        properties = emptyMap(),
                        isDefault = true,
                    ),
                    VanillaBlockState(
                        rawId = 1,
                        blockId = Identifier("test:block"),
                        properties = emptyMap(),
                        isDefault = false,
                    ),
                ),
            )
        }

        val dimensionRegistry = Identifier("dimension_type")
        fun minecraftDimensionLayout(data: NbtTag?): MinecraftDimensionLayout =
            MinecraftDimensionLayout.from(
                listOf(
                    RegistryDataPacket(
                        dimensionRegistry,
                        listOf(RegistryEntry(Identifier("test:type"), data)),
                    ),
                ),
                0,
            )

        listOf(
            null,
            NbtInt(1),
            NbtCompound(emptyMap()),
            NbtCompound(
                mapOf(
                    "min_y" to NbtInt(0),
                    "height" to NbtInt(16),
                    "has_skylight" to NbtByte(2),
                ),
            ),
        ).forEach { invalid ->
            assertFailsWith<IllegalStateException> { minecraftDimensionLayout(invalid) }
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftDimensionLayout(
                NbtCompound(
                    mapOf(
                        "min_y" to NbtInt(0),
                        "height" to NbtInt(15),
                        "has_skylight" to NbtByte(1),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            MinecraftDimensionLayout.from(
                VanillaProtocolData,
                Identifier("test:absent"),
            )
        }
    }
}
