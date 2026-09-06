package com.hiczp.minecraft.protocol.configuration.vanilla

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.configuration.completeSynchronizedRegistryPackets
import com.hiczp.minecraft.protocol.configuration.requireRegistryPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.MinecraftBlockIds
import com.hiczp.minecraft.protocol.model.type.RegistryEntry
import com.hiczp.minecraft.world.format.DimensionTypeFormatException
import kotlin.test.*

class VanillaConfigurationDataTest {
    @Test
    fun exposesACompleteOfficialConfigurationSnapshot() {
        val vanillaConfigurationData = VanillaConfigurationData
        val completeSynchronizedRegistryPackets = vanillaConfigurationData.synchronizedRegistryPackets(emptyList())

        assertTrue(vanillaConfigurationData.offeredKnownPacks.isNotEmpty())
        assertTrue(vanillaConfigurationData.enabledFeatureFlags.isNotEmpty())
        assertTrue(completeSynchronizedRegistryPackets.isNotEmpty())
        assertTrue(completeSynchronizedRegistryPackets.all { clientboundRegistryDataPacket ->
            clientboundRegistryDataPacket.entries.all { it.data != null }
        })
        assertTrue(vanillaConfigurationData.registryTags.isNotEmpty())
        val biomeRegistryPacket = completeSynchronizedRegistryPackets.single { clientboundRegistryDataPacket ->
            clientboundRegistryDataPacket.registry == Identifier("worldgen/biome")
        }
        assertEquals(
            biomeRegistryPacket.entries.map { it.id },
            vanillaConfigurationData.completePacketCodecContext.requireRegistry(biomeRegistryPacket.registry)
                .entries
                .sortedBy { it.rawId }
                .map { it.id },
        )
    }

    @Test
    fun exactKnownPacksSelectionUsesTheCompactOfficialBranch() {
        val vanillaConfigurationData = VanillaConfigurationData
        val completeSynchronizedRegistryPackets = vanillaConfigurationData.synchronizedRegistryPackets(emptyList())
        val knownPackSynchronizedRegistryPackets =
            vanillaConfigurationData.synchronizedRegistryPackets(vanillaConfigurationData.offeredKnownPacks)

        assertEquals(
            completeSynchronizedRegistryPackets.map { it.registry },
            knownPackSynchronizedRegistryPackets.map { it.registry },
        )
        assertEquals(
            completeSynchronizedRegistryPackets.map { clientboundRegistryDataPacket ->
                clientboundRegistryDataPacket.entries.map { it.id }
            },
            knownPackSynchronizedRegistryPackets.map { clientboundRegistryDataPacket ->
                clientboundRegistryDataPacket.entries.map { it.id }
            },
        )
        assertTrue(knownPackSynchronizedRegistryPackets.all { clientboundRegistryDataPacket ->
            clientboundRegistryDataPacket.entries.all { it.data == null }
        })
        assertNotEquals(completeSynchronizedRegistryPackets, knownPackSynchronizedRegistryPackets)
    }

    @Test
    fun anyNonExactKnownPacksSelectionUsesCompleteData() {
        val vanillaConfigurationData = VanillaConfigurationData
        val unknownKnownPack = KnownPack("example", "not-vanilla", "1")

        assertEquals(
            vanillaConfigurationData.synchronizedRegistryPackets(emptyList()),
            vanillaConfigurationData.synchronizedRegistryPackets(vanillaConfigurationData.offeredKnownPacks + unknownKnownPack),
        )
    }

    @Test
    fun exposesOfficialRegistryAndBlockStateIds() {
        val entityTypes = VanillaRegistryData.requireRegistry(
            Identifier("entity_type"),
        )
        val pig = Identifier("pig")
        val air = MinecraftBlockIds.AIR
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
    fun registryModelsRetainCallerOwnedLists() {
        val registryEntryIds = mutableListOf(Identifier("test:entry"))
        val vanillaRegistry = VanillaRegistry(Identifier("test:registry"), registryEntryIds)
        val vanillaBlockStates = mutableListOf(
            VanillaBlockState(
                rawId = 0,
                blockId = Identifier("test:block"),
                properties = emptyMap(),
                isDefault = true,
            ),
        )
        val vanillaBlockStateRegistry = VanillaBlockStateRegistry(vanillaBlockStates)

        assertSame(registryEntryIds, vanillaRegistry.registryEntryIds)
        assertSame(vanillaBlockStates, vanillaBlockStateRegistry.vanillaBlockStates)
        assertEquals(vanillaRegistry, vanillaRegistry.copy())
        assertEquals(vanillaBlockStateRegistry, vanillaBlockStateRegistry.copy())
    }

    @Test
    fun blockStateRawIdsAreAuthoritativeRegardlessOfListPosition() {
        val first = VanillaBlockState(7, Identifier("test:first"), emptyMap(), isDefault = true)
        val second = VanillaBlockState(2, Identifier("test:second"), emptyMap(), isDefault = true)
        val vanillaBlockStateRegistry = VanillaBlockStateRegistry(listOf(first, second))

        assertSame(first, vanillaBlockStateRegistry[7])
        assertSame(second, vanillaBlockStateRegistry[2])
        assertEquals(8, vanillaBlockStateRegistry.size)
    }

    @Test
    fun blockStateRawIdsRemainNonNegative() {
        assertFailsWith<IllegalArgumentException> {
            VanillaBlockState(-1, Identifier("test:block"), emptyMap(), isDefault = true)
        }
    }

    @Test
    fun derivesChunkContextFromSynchronizedDimensionData() {
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaConfigurationData,
            Identifier("overworld"),
        )
        val clientMinecraftDimensionLayout = MinecraftDimensionLayout.from(
            VanillaConfigurationData.completeSynchronizedRegistryPackets(),
            minecraftDimensionLayout.dimensionTypeRawId,
        )

        assertEquals(0, minecraftDimensionLayout.minY % 16)
        assertEquals(0, minecraftDimensionLayout.height % 16)
        assertEquals(minecraftDimensionLayout.height / 16, minecraftDimensionLayout.sectionCount)
        assertTrue(minecraftDimensionLayout.hasSkyLight)
        assertEquals(minecraftDimensionLayout, clientMinecraftDimensionLayout)
        assertEquals(
            Identifier("overworld"),
            VanillaConfigurationData.requireRegistryPacket(
                Identifier("dimension_type"),
            ).entries[minecraftDimensionLayout.dimensionTypeRawId].id,
        )
    }

    @Test
    fun committedRegistryAndTagSnapshotsAreStructurallyUnambiguous() {
        val completeSynchronizedRegistryPackets = VanillaConfigurationData.completeSynchronizedRegistryPackets()
        val knownPackSynchronizedRegistryPackets = VanillaConfigurationData.synchronizedRegistryPackets(
            VanillaConfigurationData.offeredKnownPacks,
        )

        assertEquals(
            completeSynchronizedRegistryPackets.size,
            completeSynchronizedRegistryPackets.map { it.registry }.distinct().size,
        )
        completeSynchronizedRegistryPackets.zip(knownPackSynchronizedRegistryPackets)
            .forEach { (completeRegistry, knownPackRegistry) ->
                assertEquals(completeRegistry.registry, knownPackRegistry.registry)
                assertEquals(
                    completeRegistry.entries.size,
                    completeRegistry.entries.map { it.id }.distinct().size,
                )
                assertTrue(completeRegistry.entries.all { it.data != null })
                assertTrue(knownPackRegistry.entries.all { it.data == null })
            }

        val registryTags = VanillaConfigurationData.registryTags
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
                            it.registry == registryTags.registry
                        }
                        ?.entries
                        ?.size
            assertTrue(
                registrySize != null,
                "No registry catalogue exists for ${registryTags.registry}",
            )
            registryTags.tags.forEach { tagDefinition ->
                assertEquals(tagDefinition.entries.size, tagDefinition.entries.distinct().size)
                assertTrue(tagDefinition.entries.all { it in 0 until registrySize })
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
    fun acceptsSparseBlockStateIdsAndRejectsAmbiguousCataloguesAndMalformedDimensionData() {
        val duplicate = Identifier("test:duplicate")
        assertFailsWith<IllegalArgumentException> {
            VanillaRegistry(
                Identifier("test:registry"),
                listOf(duplicate, duplicate),
            )
        }
        val sparseBlockState = VanillaBlockState(
            rawId = 1,
            blockId = Identifier("test:block"),
            properties = emptyMap(),
            isDefault = true,
        )
        val sparseBlockStateRegistry = VanillaBlockStateRegistry(listOf(sparseBlockState))
        assertEquals(2, sparseBlockStateRegistry.size)
        assertNull(sparseBlockStateRegistry[0])
        assertEquals(sparseBlockState, sparseBlockStateRegistry[1])
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
                    ClientboundRegistryDataPacket(
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
                    "logical_height" to NbtInt(16),
                    "has_skylight" to NbtByte(2),
                    "has_ceiling" to NbtByte(0),
                ),
            ),
        ).forEach { invalid ->
            assertFailsWith<DimensionTypeFormatException> { minecraftDimensionLayout(invalid) }
        }
        assertFailsWith<DimensionTypeFormatException> {
            minecraftDimensionLayout(
                NbtCompound(
                    mapOf(
                        "min_y" to NbtInt(0),
                        "height" to NbtInt(15),
                        "logical_height" to NbtInt(15),
                        "has_skylight" to NbtByte(1),
                        "has_ceiling" to NbtByte(0),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftDimensionLayout.from(
                VanillaConfigurationData,
                Identifier("test:absent"),
            )
        }
    }
}
