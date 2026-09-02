package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.datapack.*
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.RegistryEntry
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema
import com.hiczp.minecraft.world.format.DimensionId
import kotlin.test.*

class ProtocolRegistryContextsTest {
    @Test
    fun resolvesCompleteRegistryMappingsAgainstTheSelectedStaticSchema() {
        val synchronizedRegistryPackets = VanillaProtocolData.completeSynchronizedRegistryPackets()
        val biomeRegistryPacket = synchronizedRegistryPackets.single { it.registryId == BIOME_REGISTRY }
        val customRegistryId = Identifier("test:custom_static")
        val customRegistryEntryId = Identifier("test:entry")
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(
                customRegistryId to listOf(customRegistryEntryId),
                BIOME_REGISTRY to biomeRegistryPacket.entries.map(RegistryEntry::id).reversed(),
            ),
            blocks = emptyList(),
        )

        val protocolRegistryContext = VanillaProtocolData.resolveSynchronizedRegistryContext(
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            staticRegistrySchema = staticRegistrySchema,
        )

        assertEquals(customRegistryEntryId, protocolRegistryContext.requireRegistry(customRegistryId)[0]?.id)
        assertEquals(
            biomeRegistryPacket.entries.map(RegistryEntry::id),
            protocolRegistryContext.requireRegistry(BIOME_REGISTRY).entries
                .sortedBy { it.rawId }
                .map { it.id },
        )
        assertTrue(protocolRegistryContext.blockStates.isEmpty())
        assertNull(protocolRegistryContext.chunkSectionCount)
    }

    @Test
    fun compactKnownPackRegistriesUseMatchingVersionDimensionData() {
        val synchronizedRegistryPackets = VanillaProtocolData.synchronizedRegistryPackets(
            VanillaProtocolData.offeredKnownPacks,
        )
        val dimensionTypeRawId = requireNotNull(
            synchronizedRegistryPackets.registryRawId(DIMENSION_TYPE_REGISTRY, OVERWORLD),
        )
        val baseProtocolRegistryContext =
            VanillaProtocolData.resolveSynchronizedRegistryContext(synchronizedRegistryPackets)

        assertTrue(synchronizedRegistryPackets.all { registryDataPacket ->
            registryDataPacket.entries.all { it.data == null }
        })
        assertSame(VanillaProtocolData.completeProtocolRegistryContext, baseProtocolRegistryContext)
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = dimensionTypeRawId,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            protocolData = VanillaProtocolData,
        )
        val minecraftChunkContext = MinecraftDimensionContext.create(
            dimensionId = DimensionId.Overworld,
            minecraftDimensionLayout = minecraftDimensionLayout,
            protocolRegistryContext = baseProtocolRegistryContext,
        ).createMinecraftChunkContext()

        assertEquals(
            MinecraftDimensionLayout.from(VanillaProtocolData, OVERWORLD),
            minecraftDimensionLayout,
        )
        assertEquals(
            minecraftDimensionLayout.sectionCount,
            minecraftChunkContext.protocolRegistryContext.chunkSectionCount,
        )
        assertEquals(baseProtocolRegistryContext.registries, minecraftChunkContext.protocolRegistryContext.registries)
        assertEquals(baseProtocolRegistryContext.blockStates, minecraftChunkContext.protocolRegistryContext.blockStates)
    }

    @Test
    fun keepsMissingAndEmptyBiomeRegistriesForCallersThatDoNotNeedChunkPalettes() {
        val completeSynchronizedRegistryPackets = VanillaProtocolData.completeSynchronizedRegistryPackets()
        val synchronizedRegistryPacketsWithoutBiome = completeSynchronizedRegistryPackets.filterNot {
            it.registryId == BIOME_REGISTRY
        }
        val synchronizedRegistryPacketsWithEmptyBiome = completeSynchronizedRegistryPackets.map { registryDataPacket ->
            if (registryDataPacket.registryId == BIOME_REGISTRY) {
                RegistryDataPacket(BIOME_REGISTRY, emptyList())
            } else {
                registryDataPacket
            }
        }

        val withoutBiome = VanillaProtocolData.resolveSynchronizedRegistryContext(
            synchronizedRegistryPackets = synchronizedRegistryPacketsWithoutBiome,
            staticRegistrySchema = StaticRegistrySchema.Empty,
        )
        val withEmptyBiome =
            VanillaProtocolData.resolveSynchronizedRegistryContext(synchronizedRegistryPacketsWithEmptyBiome)

        assertNull(withoutBiome.registry(BIOME_REGISTRY))
        assertEquals(0, withEmptyBiome.requireRegistry(BIOME_REGISTRY).size)
    }

    @Test
    fun rejectsDuplicateSynchronizedRegistries() {
        val synchronizedRegistryPackets = VanillaProtocolData.completeSynchronizedRegistryPackets()
        val biomeRegistryPacket = synchronizedRegistryPackets.single { it.registryId == BIOME_REGISTRY }

        assertFailsWith<IllegalArgumentException> {
            VanillaProtocolData.resolveSynchronizedRegistryContext(synchronizedRegistryPackets + biomeRegistryPacket)
        }
    }

    @Test
    fun rejectsUnknownDimensionRawId() {
        val synchronizedRegistryPackets = VanillaProtocolData.completeSynchronizedRegistryPackets()
        assertFailsWith<IllegalArgumentException> {
            MinecraftDimensionLayout.from(
                dimensionTypeRawId = Int.MAX_VALUE,
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                protocolData = VanillaProtocolData,
            )
        }
    }

    @Test
    fun missingSynchronizedDimensionRegistryDoesNotFallBack() {
        val synchronizedRegistryPackets = VanillaProtocolData.completeSynchronizedRegistryPackets()
            .filterNot { it.registryId == DIMENSION_TYPE_REGISTRY }
        assertFailsWith<IllegalArgumentException> {
            MinecraftDimensionLayout.from(
                dimensionTypeRawId = 0,
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                protocolData = VanillaProtocolData,
            )
        }
    }

    @Test
    fun synchronizedDimensionDataSelectsANonDefaultHeight() {
        val customLevelId = Identifier("test:tall_world")
        val customDimensionTypeId = Identifier("test:tall")
        val dimensionTypeRegistryPacket = RegistryDataPacket(
            registryId = DIMENSION_TYPE_REGISTRY,
            entries = listOf(
                RegistryEntry(
                    id = customDimensionTypeId,
                    data = NbtCompound(
                        mapOf(
                            "min_y" to NbtInt(-64),
                            "height" to NbtInt(512),
                            "logical_height" to NbtInt(512),
                            "has_skylight" to NbtByte(1),
                            "has_ceiling" to NbtByte(0),
                        ),
                    ),
                ),
            ),
        )
        val biomeRegistryPacket = VanillaProtocolData.requireRegistryPacket(BIOME_REGISTRY)
        val synchronizedRegistryPackets = listOf(dimensionTypeRegistryPacket, biomeRegistryPacket)
        val baseProtocolRegistryContext =
            VanillaProtocolData.resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = 0,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            protocolData = VanillaProtocolData,
        )
        val minecraftChunkContext = MinecraftDimensionContext.create(
            dimensionId = DimensionId.parse(customLevelId.toString()),
            minecraftDimensionLayout = minecraftDimensionLayout,
            protocolRegistryContext = baseProtocolRegistryContext,
        ).createMinecraftChunkContext()

        assertEquals(-64, minecraftDimensionLayout.minY)
        assertEquals(512, minecraftDimensionLayout.height)
        assertTrue(minecraftDimensionLayout.hasSkyLight)
        assertEquals(32, minecraftChunkContext.protocolRegistryContext.chunkSectionCount)
        assertEquals(baseProtocolRegistryContext.registries, minecraftChunkContext.protocolRegistryContext.registries)
    }

    private companion object {
        val BIOME_REGISTRY: Identifier = ProtocolRegistryContext.BIOME_REGISTRY
        val DIMENSION_TYPE_REGISTRY: Identifier = Identifier("dimension_type")
        val OVERWORLD: Identifier = Identifier("overworld")
    }
}
