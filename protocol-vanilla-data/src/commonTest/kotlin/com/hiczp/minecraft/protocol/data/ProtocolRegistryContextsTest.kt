package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.*

class ProtocolRegistryContextsTest {
    @Test
    fun resolvesCompleteRegistryMappingsAgainstTheSelectedStaticSchema() {
        val registries = VanillaProtocolData.completeRegistryPackets()
        val biomePacket = registries.single { it.registryId == BIOME_REGISTRY }
        val customRegistry = Identifier("test:custom_static")
        val customEntry = Identifier("test:entry")
        val staticRegistries = StaticRegistrySchema(
            registries = mapOf(
                customRegistry to listOf(customEntry),
                BIOME_REGISTRY to biomePacket.entries.map(RegistryEntry::id).reversed(),
            ),
            blocks = emptyList(),
        )

        val context = VanillaProtocolData.resolveSynchronizedRegistryContext(
            registries = registries,
            staticRegistries = staticRegistries,
        )

        assertEquals(customEntry, context.requireRegistry(customRegistry)[0]?.id)
        assertEquals(
            biomePacket.entries.map(RegistryEntry::id),
            context.requireRegistry(BIOME_REGISTRY).entries
                .sortedBy { it.rawId }
                .map { it.id },
        )
        assertTrue(context.blockStates.isEmpty())
        assertNull(context.chunkSectionCount)
    }

    @Test
    fun compactKnownPackRegistriesUseMatchingVersionDimensionData() {
        val registries = VanillaProtocolData.registryPackets(VanillaProtocolData.knownPacks)
        val dimensionTypeId = requireNotNull(
            registries.registryId(DIMENSION_TYPE_REGISTRY, OVERWORLD),
        )
        val base = VanillaProtocolData.resolveSynchronizedRegistryContext(registries)

        assertTrue(registries.all { packet -> packet.entries.all { it.data == null } })
        assertSame(VanillaProtocolData.registryContext, base)
        val login = playLogin(dimensionTypeId)
        val dimension = MinecraftDimensionLayout.from(
            login = login,
            registries = registries,
            protocolData = VanillaProtocolData,
        )
        val active = base.withPlayLoginDimension(
            login = login,
            registries = registries,
            protocolData = VanillaProtocolData,
        )

        assertEquals(
            MinecraftDimensionLayout.from(VanillaProtocolData, OVERWORLD),
            dimension,
        )
        assertEquals(
            dimension.sectionCount,
            active.chunkSectionCount,
        )
        assertEquals(base.registries, active.registries)
        assertEquals(base.blockStates, active.blockStates)
    }

    @Test
    fun rejectsMissingAndEmptyBiomeRegistries() {
        val complete = VanillaProtocolData.completeRegistryPackets()
        val withoutBiome = complete.filterNot { it.registryId == BIOME_REGISTRY }
        val emptyBiome = complete.map { packet ->
            if (packet.registryId == BIOME_REGISTRY) {
                RegistryDataPacket(BIOME_REGISTRY, emptyList())
            } else {
                packet
            }
        }

        assertFailsWith<IllegalArgumentException> {
            VanillaProtocolData.resolveSynchronizedRegistryContext(withoutBiome)
        }
        assertFailsWith<IllegalArgumentException> {
            VanillaProtocolData.resolveSynchronizedRegistryContext(emptyBiome)
        }
    }

    @Test
    fun rejectsDuplicateSynchronizedRegistries() {
        val registries = VanillaProtocolData.completeRegistryPackets()
        val biome = registries.single { it.registryId == BIOME_REGISTRY }

        assertFailsWith<IllegalArgumentException> {
            VanillaProtocolData.resolveSynchronizedRegistryContext(registries + biome)
        }
    }

    @Test
    fun rejectsUnknownDimensionRawIdAndDimensionOutsideAdvertisedLevels() {
        val registries = VanillaProtocolData.completeRegistryPackets()
        val dimensionTypeId = requireNotNull(
            registries.registryId(DIMENSION_TYPE_REGISTRY, OVERWORLD),
        )
        val base = VanillaProtocolData.resolveSynchronizedRegistryContext(registries)

        assertFailsWith<IllegalArgumentException> {
            base.withPlayLoginDimension(
                login = playLogin(Int.MAX_VALUE),
                registries = registries,
                protocolData = VanillaProtocolData,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            base.withPlayLoginDimension(
                login = playLogin(dimensionTypeId).copy(levels = emptySet()),
                registries = registries,
                protocolData = VanillaProtocolData,
            )
        }
    }

    @Test
    fun missingSynchronizedDimensionRegistryDoesNotFallBack() {
        val registries = VanillaProtocolData.completeRegistryPackets()
            .filterNot { it.registryId == DIMENSION_TYPE_REGISTRY }
        val base = VanillaProtocolData.resolveSynchronizedRegistryContext(registries)

        assertFailsWith<IllegalArgumentException> {
            base.withPlayLoginDimension(
                login = playLogin(0),
                registries = registries,
                protocolData = VanillaProtocolData,
            )
        }
    }

    @Test
    fun synchronizedDimensionDataSelectsANonDefaultHeight() {
        val customLevel = Identifier("test:tall_world")
        val customDimensionType = Identifier("test:tall")
        val dimensionRegistry = RegistryDataPacket(
            registryId = DIMENSION_TYPE_REGISTRY,
            entries = listOf(
                RegistryEntry(
                    id = customDimensionType,
                    data = NbtCompound(
                        mapOf(
                            "min_y" to NbtInt(-64),
                            "height" to NbtInt(512),
                            "has_skylight" to NbtByte(1),
                        ),
                    ),
                ),
            ),
        )
        val biomeRegistry = VanillaProtocolData.requireRegistry(BIOME_REGISTRY)
        val registries = listOf(dimensionRegistry, biomeRegistry)
        val base = VanillaProtocolData.resolveSynchronizedRegistryContext(registries)
        val login = playLogin(
            dimensionTypeId = 0,
            dimension = customLevel,
        )
        val dimension = MinecraftDimensionLayout.from(
            login = login,
            registries = registries,
            protocolData = VanillaProtocolData,
        )
        val active = base.withPlayLoginDimension(
            login = login,
            registries = registries,
            protocolData = VanillaProtocolData,
        )

        assertEquals(-64, dimension.minY)
        assertEquals(512, dimension.height)
        assertTrue(dimension.hasSkyLight)
        assertEquals(32, active.chunkSectionCount)
        assertEquals(base.registries, active.registries)
    }

    private fun playLogin(
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
