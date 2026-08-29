package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeId
import com.hiczp.minecraft.world.format.data.WorldGenDimension
import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData
import kotlin.test.*

class ResolvedMinecraftWorldTest {
    @Test
    fun resolvesEveryDeclaredDimensionFromOneRegistryOrder() {
        val resolvedProtocolData = testResolvedProtocolData()
        val worldGenSettingsData = worldGenSettings(
            DimensionId.Overworld to reference("overworld"),
            DimensionId.Nether to reference("the_nether"),
        )

        val resolvedMinecraftWorld = resolvedProtocolData.resolveMinecraftWorld(worldGenSettingsData)

        assertSame(resolvedProtocolData, resolvedMinecraftWorld.protocolData)
        assertEquals(listOf(DimensionId.Overworld, DimensionId.Nether), resolvedMinecraftWorld.dimensions.keys.toList())
        val overworld = resolvedMinecraftWorld.dimension(DimensionId.Overworld)
        val nether = resolvedMinecraftWorld.dimension(DimensionId.Nether)
        assertEquals(0, overworld.minecraftDimensionLayout.dimensionTypeRawId)
        assertEquals(1, nether.minecraftDimensionLayout.dimensionTypeRawId)
        assertEquals(24, overworld.chunkCodecContext.chunkLayout.sectionCount)
        assertEquals(16, nether.chunkCodecContext.chunkLayout.sectionCount)
        assertEquals(
            resolvedProtocolData.completeProtocolRegistryContext.requireRegistry(
                MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY,
            ).entries,
            overworld.protocolRegistryContext.requireRegistry(
                MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY,
            ).entries,
        )
        assertSame(
            overworld.chunkCodecContext,
            overworld.chunkNbtCodec.chunkCodecContext,
        )
        assertSame(
            overworld.chunkCodecContext.chunkDataRegistries,
            nether.chunkCodecContext.chunkDataRegistries,
        )
    }

    @Test
    fun aggregatesInlineAndMissingReferencesBeforeReturningAnyContext() {
        val resolvedProtocolData = testResolvedProtocolData()
        val inlineDimensionId = DimensionId("alpha", "test")
        val missingDimensionId = DimensionId("zeta", "test")
        val completeRegistrySnapshot = resolvedProtocolData.completeSynchronizedRegistryPackets
        val knownPackRegistrySnapshot = resolvedProtocolData.knownPackSynchronizedRegistryPackets
        val worldGenSettingsData = worldGenSettings(
            missingDimensionId to reference("missing", "test"),
            inlineDimensionId to WorldGenDimension(
                type = WorldGenDimensionType.Inline(dimensionTypeData(-64, 384, true, false)),
                generator = NbtCompound(emptyMap()),
            ),
        )

        val failure = assertFailsWith<MinecraftWorldResolutionException> {
            resolvedProtocolData.resolveMinecraftWorld(worldGenSettingsData)
        }

        assertEquals(listOf(inlineDimensionId, missingDimensionId), failure.failures.keys.toList())
        assertContains(failure.failures.getValue(inlineDimensionId), "inline")
        assertContains(failure.failures.getValue(missingDimensionId), "test:missing")
        assertSame(completeRegistrySnapshot, resolvedProtocolData.completeSynchronizedRegistryPackets)
        assertSame(knownPackRegistrySnapshot, resolvedProtocolData.knownPackSynchronizedRegistryPackets)
    }

    @Test
    fun reportsMalformedReferencedDimensionDataAtResolutionTime() {
        val resolvedProtocolData = testResolvedProtocolData(
            overworldDimensionTypeData = NbtCompound(mapOf("min_y" to NbtInt(-64))),
        )

        val failure = assertFailsWith<MinecraftWorldResolutionException> {
            resolvedProtocolData.resolveMinecraftWorld(
                worldGenSettings(DimensionId.Overworld to reference("overworld")),
            )
        }

        assertContains(failure.failures.getValue(DimensionId.Overworld), "height")
    }

    @Test
    fun keepsCustomChunkDefaultsAtTheResolvedWorldBoundary() {
        val resolvedProtocolData = testResolvedProtocolData()

        val resolvedMinecraftWorld = resolvedProtocolData.resolveMinecraftWorld(
            worldGenSettings(DimensionId.Overworld to reference("overworld")),
            defaultBlock = Identifier("stone"),
            defaultBiome = Identifier("desert"),
        )

        val minecraftChunkContext = resolvedMinecraftWorld.dimension(DimensionId.Overworld)
        assertEquals(
            Identifier("stone"),
            minecraftChunkContext.chunkCodecContext.chunkDataRegistries.blockStates.defaultValue.block,
        )
        assertEquals(
            Identifier("desert"),
            minecraftChunkContext.chunkCodecContext.chunkDataRegistries.biomes.defaultValue.id,
        )
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

    private fun testResolvedProtocolData(
        overworldDimensionTypeData: NbtCompound = dimensionTypeData(-64, 384, true, false),
    ): ResolvedProtocolData {
        val air = Identifier("air")
        val stone = Identifier("stone")
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(StaticRegistrySchema.BLOCK_REGISTRY to listOf(air, stone)),
            blocks = listOf(
                StaticBlockSchema(air, listOf(StaticBlockState(emptyMap(), true))),
                StaticBlockSchema(stone, listOf(StaticBlockState(emptyMap(), true))),
            ),
        )
        return ResolvedProtocolData(
            minecraftVersion = "test",
            protocolVersion = 1,
            offeredKnownPacks = emptyList(),
            enabledFeatureFlags = emptySet(),
            completeSynchronizedRegistryPackets = listOf(
                RegistryDataPacket(
                    MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY,
                    listOf(
                        RegistryEntry(Identifier("overworld"), overworldDimensionTypeData),
                        RegistryEntry(Identifier("the_nether"), dimensionTypeData(0, 256, false, true)),
                    ),
                ),
                RegistryDataPacket(
                    ProtocolRegistryContext.BIOME_REGISTRY,
                    listOf(
                        RegistryEntry(Identifier("plains"), null),
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
