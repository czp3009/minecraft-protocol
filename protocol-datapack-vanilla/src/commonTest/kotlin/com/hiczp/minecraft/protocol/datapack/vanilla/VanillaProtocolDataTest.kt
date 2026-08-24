package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.completeRegistryPackets
import com.hiczp.minecraft.protocol.datapack.requireRegistry
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.RegistryEntry
import kotlin.test.*

class VanillaProtocolDataTest {
    @Test
    fun exposesACompleteOfficialConfigurationSnapshot() {
        val data = VanillaProtocolData
        val complete = data.registryPackets(emptyList())

        assertTrue(data.knownPacks.isNotEmpty())
        assertTrue(data.featureFlags.featureFlags.isNotEmpty())
        assertTrue(complete.isNotEmpty())
        assertTrue(complete.all { packet -> packet.entries.all { it.data != null } })
        assertTrue(data.tags.registries.isNotEmpty())
        val biomePacket = complete.single {
            it.registryId == Identifier("worldgen/biome")
        }
        assertEquals(
            biomePacket.entries.map { it.id },
            data.registryContext.requireRegistry(biomePacket.registryId)
                .entries
                .sortedBy { it.rawId }
                .map { it.id },
        )
    }

    @Test
    fun exactKnownPacksSelectionUsesTheCompactOfficialBranch() {
        val data = VanillaProtocolData
        val complete = data.registryPackets(emptyList())
        val compact = data.registryPackets(data.knownPacks)

        assertEquals(complete.map { it.registryId }, compact.map { it.registryId })
        assertEquals(
            complete.map { packet -> packet.entries.map { it.id } },
            compact.map { packet -> packet.entries.map { it.id } },
        )
        assertTrue(compact.all { packet -> packet.entries.all { it.data == null } })
        assertNotEquals(complete, compact)
    }

    @Test
    fun anyNonExactKnownPacksSelectionUsesCompleteData() {
        val data = VanillaProtocolData
        val unknown = KnownPack("example", "not-vanilla", "1")

        assertEquals(
            data.registryPackets(emptyList()),
            data.registryPackets(data.knownPacks + unknown),
        )
    }

    @Test
    fun exposesOfficialStaticRegistryAndBlockStateIds() {
        val entityTypes = VanillaStaticData.requireRegistry(
            Identifier("entity_type"),
        )
        val pig = Identifier("pig")
        val air = Identifier("air")
        val grass = Identifier("grass_block")

        assertEquals(pig, entityTypes[entityTypes.requireProtocolId(pig)])
        assertEquals(
            air,
            VanillaStaticData.blockStates.require(0).block,
        )
        assertEquals(
            VanillaStaticData.requireRegistry(Identifier("block"))
                .requireProtocolId(grass),
            VanillaStaticData.blockStates.default(grass).block.let {
                VanillaStaticData.requireRegistry(Identifier("block"))
                    .requireProtocolId(it)
            },
        )
        assertTrue(VanillaStaticData.blockStates.size > 1)
    }

    @Test
    fun derivesChunkContextFromSynchronizedDimensionData() {
        val layout = MinecraftDimensionLayout.from(
            VanillaProtocolData,
            Identifier("overworld"),
        )
        val fromClientRegistry = MinecraftDimensionLayout.from(
            VanillaProtocolData.completeRegistryPackets(),
            layout.registryId,
        )

        assertEquals(0, layout.minY % 16)
        assertEquals(0, layout.height % 16)
        assertEquals(layout.height / 16, layout.sectionCount)
        assertTrue(layout.hasSkyLight)
        assertEquals(layout, fromClientRegistry)
        assertEquals(
            Identifier("overworld"),
            VanillaProtocolData.requireRegistry(
                Identifier("dimension_type"),
            ).entries[layout.registryId].id,
        )
    }

    @Test
    fun committedRegistryAndTagSnapshotsAreStructurallyUnambiguous() {
        val complete = VanillaProtocolData.completeRegistryPackets()
        val compact = VanillaProtocolData.registryPackets(
            VanillaProtocolData.knownPacks,
        )

        assertEquals(
            complete.size,
            complete.map { it.registryId }.distinct().size,
        )
        complete.zip(compact).forEach { (full, known) ->
            assertEquals(full.registryId, known.registryId)
            assertEquals(
                full.entries.size,
                full.entries.map { it.id }.distinct().size,
            )
            assertTrue(full.entries.all { it.data != null })
            assertTrue(known.entries.all { it.data == null })
        }

        val tags = VanillaProtocolData.tags.registries
        assertEquals(tags.size, tags.map { it.registry }.distinct().size)
        tags.forEach { registryTags ->
            assertEquals(
                registryTags.tags.size,
                registryTags.tags.map { it.name }.distinct().size,
            )
            val registrySize =
                VanillaStaticData.registry(registryTags.registry)?.size
                    ?: complete
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
    fun committedStaticDataHasBijectiveIdsAndCanonicalBlockStates() {
        VanillaStaticData.registries.forEach { (id, registry) ->
            assertEquals(id, registry.id)
            assertEquals(
                registry.entries.size,
                registry.entries.distinct().size,
            )
            registry.entries.forEachIndexed { index, entry ->
                assertEquals(index, registry.protocolId(entry))
                assertEquals(entry, registry[index])
            }
            assertNull(registry[-1])
            assertNull(registry[registry.size])
        }

        val states = VanillaStaticData.blockStates.states
        states.forEachIndexed { index, state ->
            assertEquals(index, state.id)
            assertEquals(state, VanillaStaticData.blockStates[index])
            assertEquals(
                state,
                VanillaStaticData.blockStates.find(
                    state.block,
                    state.properties,
                ),
            )
        }
        states.groupBy(VanillaBlockState::block).forEach { (block, values) ->
            assertEquals(1, values.count(VanillaBlockState::isDefault))
            assertEquals(
                values.size,
                values.map(VanillaBlockState::properties).distinct().size,
            )
            assertEquals(
                values.single(VanillaBlockState::isDefault),
                VanillaStaticData.blockStates.default(block),
            )
        }
        assertEquals(
            VanillaStaticData.requireRegistry(Identifier("block"))
                .entries
                .toSet(),
            states.map(VanillaBlockState::block).toSet(),
        )
    }

    @Test
    fun rejectsAmbiguousStaticCataloguesAndMalformedDimensionData() {
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
                        id = 1,
                        block = Identifier("test:block"),
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
                        id = 0,
                        block = Identifier("test:block"),
                        properties = emptyMap(),
                        isDefault = true,
                    ),
                    VanillaBlockState(
                        id = 1,
                        block = Identifier("test:block"),
                        properties = emptyMap(),
                        isDefault = false,
                    ),
                ),
            )
        }

        val dimensionRegistry = Identifier("dimension_type")
        fun layout(data: NbtTag?): MinecraftDimensionLayout =
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
            assertFailsWith<IllegalStateException> { layout(invalid) }
        }
        assertFailsWith<IllegalArgumentException> {
            layout(
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
