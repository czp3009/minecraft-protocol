package com.hiczp.minecraft.protocol.configuration.vanilla

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.configuration.*
import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.model.type.RegistryEntry
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema
import com.hiczp.minecraft.world.format.DimensionId
import kotlin.test.*

class PacketCodecContextsTest {
    @Test
    fun resolvesCompleteRegistryMappingsAgainstTheSelectedStaticSchema() {
        val synchronizedRegistryPackets = VanillaConfigurationData.completeSynchronizedRegistryPackets()
        val biomeRegistryPacket = synchronizedRegistryPackets.single { it.registry == BIOME_REGISTRY }
        val customRegistryId = Identifier("test:custom_static")
        val customRegistryEntryId = Identifier("test:entry")
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(
                customRegistryId to listOf(customRegistryEntryId),
                BIOME_REGISTRY to biomeRegistryPacket.entries.map(RegistryEntry::id).reversed(),
            ),
            blocks = emptyList(),
        )

        val packetCodecContext = VanillaConfigurationData.resolveSynchronizedRegistryContext(
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            staticRegistrySchema = staticRegistrySchema,
        )

        assertEquals(customRegistryEntryId, packetCodecContext.requireRegistry(customRegistryId)[0]?.id)
        assertEquals(
            biomeRegistryPacket.entries.map(RegistryEntry::id),
            packetCodecContext.requireRegistry(BIOME_REGISTRY).entries
                .sortedBy { it.rawId }
                .map { it.id },
        )
        assertTrue(packetCodecContext.blockStates.isEmpty())
    }

    @Test
    fun compactKnownPackRegistriesUseMatchingVersionDimensionData() {
        val synchronizedRegistryPackets = VanillaConfigurationData.synchronizedRegistryPackets(
            VanillaConfigurationData.offeredKnownPacks,
        )
        val dimensionTypeRawId = requireNotNull(
            synchronizedRegistryPackets.registryRawId(DIMENSION_TYPE_REGISTRY, OVERWORLD),
        )
        val basePacketCodecContext =
            VanillaConfigurationData.resolveSynchronizedRegistryContext(synchronizedRegistryPackets)

        assertTrue(synchronizedRegistryPackets.all { clientboundRegistryDataPacket ->
            clientboundRegistryDataPacket.entries.all { it.data == null }
        })
        assertSame(VanillaConfigurationData.completePacketCodecContext, basePacketCodecContext)
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = dimensionTypeRawId,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            configurationData = VanillaConfigurationData,
        )
        val minecraftDimensionContext = MinecraftDimensionContext(
            dimensionId = DimensionId.Overworld,
            minecraftDimensionLayout = minecraftDimensionLayout,
            packetCodecContext = basePacketCodecContext,
        )

        assertEquals(
            MinecraftDimensionLayout.from(VanillaConfigurationData, OVERWORLD),
            minecraftDimensionLayout,
        )
        assertEquals(
            minecraftDimensionLayout.sectionCount,
            minecraftDimensionContext.chunkLayout.sectionCount,
        )
        assertSame(basePacketCodecContext, minecraftDimensionContext.packetCodecContext)
        assertEquals(basePacketCodecContext.blockStates, minecraftDimensionContext.packetCodecContext.blockStates)
    }

    @Test
    fun keepsMissingAndEmptyBiomeRegistriesForCallersThatDoNotNeedChunkPalettes() {
        val completeSynchronizedRegistryPackets = VanillaConfigurationData.completeSynchronizedRegistryPackets()
        val synchronizedRegistryPacketsWithoutBiome = completeSynchronizedRegistryPackets.filterNot {
            it.registry == BIOME_REGISTRY
        }
        val synchronizedRegistryPacketsWithEmptyBiome =
            completeSynchronizedRegistryPackets.map { clientboundRegistryDataPacket ->
                if (clientboundRegistryDataPacket.registry == BIOME_REGISTRY) {
                    ClientboundRegistryDataPacket(BIOME_REGISTRY, emptyList())
            } else {
                    clientboundRegistryDataPacket
            }
        }

        val withoutBiome = VanillaConfigurationData.resolveSynchronizedRegistryContext(
            synchronizedRegistryPackets = synchronizedRegistryPacketsWithoutBiome,
            staticRegistrySchema = StaticRegistrySchema.Empty,
        )
        val withEmptyBiome =
            VanillaConfigurationData.resolveSynchronizedRegistryContext(synchronizedRegistryPacketsWithEmptyBiome)

        assertNull(withoutBiome.registry(BIOME_REGISTRY))
        assertEquals(0, withEmptyBiome.requireRegistry(BIOME_REGISTRY).size)
    }

    @Test
    fun rejectsDuplicateSynchronizedRegistries() {
        val synchronizedRegistryPackets = VanillaConfigurationData.completeSynchronizedRegistryPackets()
        val biomeRegistryPacket = synchronizedRegistryPackets.single { it.registry == BIOME_REGISTRY }

        assertFailsWith<IllegalArgumentException> {
            VanillaConfigurationData.resolveSynchronizedRegistryContext(synchronizedRegistryPackets + biomeRegistryPacket)
        }
    }

    @Test
    fun rejectsUnknownDimensionRawId() {
        val synchronizedRegistryPackets = VanillaConfigurationData.completeSynchronizedRegistryPackets()
        assertFailsWith<IllegalArgumentException> {
            MinecraftDimensionLayout.from(
                dimensionTypeRawId = Int.MAX_VALUE,
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                configurationData = VanillaConfigurationData,
            )
        }
    }

    @Test
    fun missingSynchronizedDimensionRegistryDoesNotFallBack() {
        val synchronizedRegistryPackets = VanillaConfigurationData.completeSynchronizedRegistryPackets()
            .filterNot { it.registry == DIMENSION_TYPE_REGISTRY }
        assertFailsWith<IllegalArgumentException> {
            MinecraftDimensionLayout.from(
                dimensionTypeRawId = 0,
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                configurationData = VanillaConfigurationData,
            )
        }
    }

    @Test
    fun synchronizedDimensionDataSelectsANonDefaultHeight() {
        val customLevelId = Identifier("test:tall_world")
        val customDimensionTypeId = Identifier("test:tall")
        val dimensionTypeRegistryPacket = ClientboundRegistryDataPacket(
            registry = DIMENSION_TYPE_REGISTRY,
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
        val biomeRegistryPacket = VanillaConfigurationData.requireRegistryPacket(BIOME_REGISTRY)
        val synchronizedRegistryPackets = listOf(dimensionTypeRegistryPacket, biomeRegistryPacket)
        val basePacketCodecContext =
            VanillaConfigurationData.resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = 0,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            configurationData = VanillaConfigurationData,
        )
        val minecraftDimensionContext = MinecraftDimensionContext(
            dimensionId = DimensionId.parse(customLevelId.toString()),
            minecraftDimensionLayout = minecraftDimensionLayout,
            packetCodecContext = basePacketCodecContext,
        )

        assertEquals(-64, minecraftDimensionLayout.minY)
        assertEquals(512, minecraftDimensionLayout.height)
        assertTrue(minecraftDimensionLayout.hasSkyLight)
        assertEquals(32, minecraftDimensionContext.chunkLayout.sectionCount)
        assertSame(basePacketCodecContext, minecraftDimensionContext.packetCodecContext)
    }

    private companion object {
        val BIOME_REGISTRY: Identifier = PacketCodecContext.BIOME_REGISTRY
        val DIMENSION_TYPE_REGISTRY: Identifier = Identifier("dimension_type")
        val OVERWORLD: Identifier = Identifier("overworld")
    }
}
