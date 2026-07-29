package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
}
