package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.datapack.*
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
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
        val playLoginPacket = createPlayLoginPacket(dimensionTypeRawId)
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            playLoginPacket = playLoginPacket,
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
    fun rejectsMissingAndEmptyBiomeRegistries() {
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

        assertFailsWith<IllegalArgumentException> {
            VanillaProtocolData.resolveSynchronizedRegistryContext(synchronizedRegistryPacketsWithoutBiome)
        }
        assertFailsWith<IllegalArgumentException> {
            VanillaProtocolData.resolveSynchronizedRegistryContext(synchronizedRegistryPacketsWithEmptyBiome)
        }
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
    fun rejectsUnknownDimensionRawIdAndDimensionOutsideAdvertisedLevels() {
        val synchronizedRegistryPackets = VanillaProtocolData.completeSynchronizedRegistryPackets()
        val dimensionTypeRawId = requireNotNull(
            synchronizedRegistryPackets.registryRawId(DIMENSION_TYPE_REGISTRY, OVERWORLD),
        )
        assertFailsWith<IllegalArgumentException> {
            MinecraftDimensionLayout.from(
                playLoginPacket = createPlayLoginPacket(Int.MAX_VALUE),
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                protocolData = VanillaProtocolData,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftDimensionLayout.from(
                playLoginPacket = createPlayLoginPacket(dimensionTypeRawId).copy(levels = emptySet()),
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
                playLoginPacket = createPlayLoginPacket(0),
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
        val playLoginPacket = createPlayLoginPacket(
            dimensionTypeId = 0,
            dimension = customLevelId,
        )
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            playLoginPacket = playLoginPacket,
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

    private fun createPlayLoginPacket(
        dimensionTypeId: Int,
        dimension: Identifier = OVERWORLD,
    ): PlayLoginPacket = PlayLoginPacket(
        playerId = 1,
        hardcore = false,
        levels = setOf(dimension),
        maxPlayers = 20,
        chunkRadius = 8,
        simulationDistance = 8,
        reducedDebugInfo = false,
        showDeathScreen = true,
        limitedCrafting = false,
        spawnInfo = CommonPlayerSpawnInfo(
            dimensionTypeId = dimensionTypeId,
            dimension = dimension,
            seed = 0,
            gameMode = GameMode.SURVIVAL,
            previousGameMode = null,
            isDebug = false,
            isFlat = false,
            lastDeathLocation = null,
            portalCooldown = 0,
            seaLevel = 63,
        ),
        onlineMode = false,
        enforcesSecureChat = false,
    )

    private companion object {
        val BIOME_REGISTRY: Identifier = ProtocolRegistryContext.BIOME_REGISTRY
        val DIMENSION_TYPE_REGISTRY: Identifier = Identifier("dimension_type")
        val OVERWORLD: Identifier = Identifier("overworld")
    }
}
