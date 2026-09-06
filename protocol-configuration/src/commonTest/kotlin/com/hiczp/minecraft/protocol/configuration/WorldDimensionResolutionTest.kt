package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.BiomeId
import com.hiczp.minecraft.world.format.BlockId
import com.hiczp.minecraft.world.format.BlockState
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeId
import com.hiczp.minecraft.world.format.WorldChunkContextResolutionException
import com.hiczp.minecraft.world.format.data.WorldGenDimension
import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData
import kotlin.test.*

class WorldDimensionResolutionTest {
    private val defaultBlockState = BlockState(BlockId("minecraft:stone"))
    private val defaultBiome = BiomeId("minecraft:desert")

    @Test
    fun resolvesDomainFactsAndSynchronizedIdentitiesIndependently() {
        val resolvedConfigurationData = testResolvedConfigurationData()
        val settings = worldGenSettings(
            DimensionId.Overworld to reference("overworld"),
            DimensionId.Nether to reference("the_nether"),
        )
        val worldChunkContexts = resolvedConfigurationData.resolveWorldChunkContexts(
            settings, defaultBlockState, defaultBiome,
        )
        val dimensions = resolvedConfigurationData.resolveMinecraftDimensions(settings)
        val overworld = dimensions.getValue(DimensionId.Overworld)
        val nether = dimensions.getValue(DimensionId.Nether)
        assertEquals(24, overworld.chunkLayout.sectionCount)
        assertEquals(16, nether.chunkLayout.sectionCount)
        assertEquals(0, overworld.minecraftDimensionLayout.dimensionTypeRawId)
        assertEquals(1, nether.minecraftDimensionLayout.dimensionTypeRawId)
        assertSame(resolvedConfigurationData.completePacketCodecContext, overworld.packetCodecContext)
        assertSame(overworld.packetCodecContext, nether.packetCodecContext)
        val chunkContext = worldChunkContexts.dimension(DimensionId.Overworld)
        assertSame(defaultBlockState, chunkContext.defaultBlockState)
        assertEquals(defaultBiome, chunkContext.defaultBiome)
        assertEquals(chunkContext, overworld.chunkContext(defaultBlockState, defaultBiome))
    }

    @Test
    fun readsInlineWorldFactsWithoutInventingSynchronizedIds() {
        val resolvedConfigurationData = testResolvedConfigurationData()
        val inlineDimensionId = DimensionId("inline", "test")
        val settings = worldGenSettings(
            inlineDimensionId to WorldGenDimension(
                WorldGenDimensionType.Inline(dimensionTypeData(-32, 128, true, false)), NbtCompound(emptyMap()),
            ),
        )
        val worldChunkContexts = resolvedConfigurationData.resolveWorldChunkContexts(
            settings, defaultBlockState, defaultBiome,
        )
        assertEquals(-32, worldChunkContexts.dimension(inlineDimensionId).dimensionTypeLayout.minY)
        val failure = assertFailsWith<WorldChunkContextResolutionException> {
            resolvedConfigurationData.resolveMinecraftDimensions(settings)
        }
        assertContains(failure.failures.getValue(inlineDimensionId), "no synchronized registry raw ID")
    }

    @Test
    fun collectsAllResolutionFailuresWithoutChangingConfiguration() {
        val resolvedConfigurationData = testResolvedConfigurationData(
            NbtCompound(mapOf("min_y" to NbtInt(-64))),
        )
        val missingDimensionId = DimensionId("missing", "test")
        val settings = worldGenSettings(
            DimensionId.Overworld to reference("overworld"),
            missingDimensionId to reference("absent", "test"),
        )
        val before = resolvedConfigurationData.completeSynchronizedRegistryPackets
        val failure = assertFailsWith<WorldChunkContextResolutionException> {
            resolvedConfigurationData.resolveWorldChunkContexts(settings, defaultBlockState, defaultBiome)
        }
        assertEquals(setOf(DimensionId.Overworld, missingDimensionId), failure.failures.keys)
        assertContains(failure.failures.getValue(DimensionId.Overworld), "height")
        assertContains(failure.failures.getValue(missingDimensionId), "test:absent")
        val serverFailure = assertFailsWith<WorldChunkContextResolutionException> {
            resolvedConfigurationData.resolveMinecraftDimensions(settings)
        }
        assertEquals(failure.failures.keys, serverFailure.failures.keys)
        assertSame(before, resolvedConfigurationData.completeSynchronizedRegistryPackets)
    }

    @Test
    fun knownPackFallbackRetainsTheSynchronizedRawIdWithoutCrossValidation() {
        val resolvedConfigurationData = testResolvedConfigurationData()
        val synchronizedRegistryPackets = listOf(
            ClientboundRegistryDataPacket(
                MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY,
                listOf(
                    RegistryEntry(Identifier("the_nether"), null),
                    RegistryEntry(Identifier("overworld"), null),
                ),
            ),
        )

        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = 0,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            configurationData = resolvedConfigurationData,
        )

        assertEquals(Identifier("the_nether"), minecraftDimensionLayout.dimensionTypeId)
        assertEquals(0, minecraftDimensionLayout.dimensionTypeRawId)
        assertEquals(0, minecraftDimensionLayout.minY)
        assertEquals(16, minecraftDimensionLayout.sectionCount)
    }

    private fun worldGenSettings(
        vararg dimensions: Pair<DimensionId, WorldGenDimension>,
    ): WorldGenSettingsData = WorldGenSettingsData(
        seed = 1L,
        generateStructures = true,
        bonusChest = false,
        dimensions = mapOf(*dimensions),
    )

    private fun reference(
        path: String,
        namespace: String = "minecraft",
    ): WorldGenDimension = WorldGenDimension(
        type = WorldGenDimensionType.Reference(DimensionTypeId(path, namespace)),
        generator = NbtCompound(emptyMap()),
    )

    private fun testResolvedConfigurationData(
        overworldDimensionTypeData: NbtCompound = dimensionTypeData(-64, 384, true, false),
    ): ResolvedConfigurationData {
        val air = MinecraftBlockIds.AIR
        val stone = Identifier("stone")
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(StaticRegistrySchema.BLOCK_REGISTRY to listOf(air, stone)),
            blocks = listOf(
                StaticBlockSchema(air, listOf(StaticBlockState(emptyMap(), true))),
                StaticBlockSchema(stone, listOf(StaticBlockState(emptyMap(), true))),
            ),
        )
        return ResolvedConfigurationData(
            offeredKnownPacks = emptyList(),
            enabledFeatureFlags = emptySet(),
            completeSynchronizedRegistryPackets = listOf(
                ClientboundRegistryDataPacket(
                    MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY,
                    listOf(
                        RegistryEntry(Identifier("overworld"), overworldDimensionTypeData),
                        RegistryEntry(Identifier("the_nether"), dimensionTypeData(0, 256, false, true)),
                    ),
                ),
                ClientboundRegistryDataPacket(
                    PacketCodecContext.BIOME_REGISTRY,
                    listOf(
                        RegistryEntry(MinecraftBiomeIds.PLAINS, null),
                        RegistryEntry(Identifier("desert"), null),
                    ),
                ),
            ),
            registryTags = emptyList(),
            staticRegistrySchema = staticRegistrySchema,
        )
    }

    private fun dimensionTypeData(
        minY: Int,
        height: Int,
        hasSkyLight: Boolean,
        hasCeiling: Boolean,
    ): NbtCompound = NbtCompound(
        mapOf(
            "min_y" to NbtInt(minY),
            "height" to NbtInt(height),
            "logical_height" to NbtInt(height),
            "has_skylight" to NbtByte(if (hasSkyLight) 1 else 0),
            "has_ceiling" to NbtByte(if (hasCeiling) 1 else 0),
        ),
    )
}
