package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.BlockStateDescriptor
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinecraftWorldChunkAdaptersTest {
    @Test
    fun convertsDimensionBlockBoundsToAChunkLayout() {
        val minecraftDimensionLayout = MinecraftDimensionLayout(
            dimensionTypeId = Identifier("overworld"),
            dimensionTypeRawId = 0,
            dimensionTypeLayout = DimensionTypeLayout(
                minY = -64,
                height = 384,
                logicalHeight = 384,
                hasSkyLight = true,
                hasCeiling = false,
            ),
        )

        val chunkLayout = minecraftDimensionLayout.chunkLayout

        assertEquals(ChunkLayout.fromBlockBounds(minY = -64, height = 384), chunkLayout)
        assertEquals(-4, chunkLayout.minSectionY)
        assertEquals(24, chunkLayout.sectionCount)
        assertEquals(-64..319, chunkLayout.blockYRange)
    }

    @Test
    fun adaptsDefaultAndNamedRegistryValues() {
        val protocolRegistryContext = testProtocolRegistryContext()
        val chunkDataRegistries = protocolRegistryContext.toChunkDataRegistries()
        val airProtocolBlockState = protocolRegistryContext.blockStates[0]
        val stoneProtocolBlockState = protocolRegistryContext.blockStates[1]
        val plainsProtocolRegistryEntry = protocolRegistryContext.requireRegistryEntry(
            ProtocolRegistryContext.BIOME_REGISTRY,
            MinecraftBiomeIds.PLAINS,
        )

        assertEquals(airProtocolBlockState, chunkDataRegistries.blockStates.defaultValue)
        assertEquals(
            stoneProtocolBlockState,
            chunkDataRegistries.blockStates.resolve(BlockStateDescriptor("minecraft:stone")),
        )
        assertEquals(
            BlockStateDescriptor("minecraft:stone"),
            chunkDataRegistries.blockStates.describe(stoneProtocolBlockState),
        )
        assertEquals(plainsProtocolRegistryEntry, chunkDataRegistries.biomes.defaultValue)
        assertEquals(plainsProtocolRegistryEntry, chunkDataRegistries.biomes.resolve("minecraft:plains"))
        assertEquals("minecraft:plains", chunkDataRegistries.biomes.name(plainsProtocolRegistryEntry))
    }

    @Test
    fun resolvesBlockStatesByTheirCompleteProperties() {
        val protocolRegistryContext = testProtocolRegistryContext()
        val chunkDataRegistries = protocolRegistryContext.toChunkDataRegistries()
        val axisXProtocolBlockState = protocolRegistryContext.blockStates[2]

        assertEquals(
            axisXProtocolBlockState,
            chunkDataRegistries.blockStates.resolve(
                BlockStateDescriptor("test:axis_block", mapOf("axis" to "x")),
            ),
        )
        assertEquals(
            BlockStateDescriptor("test:axis_block", mapOf("axis" to "x")),
            chunkDataRegistries.blockStates.describe(axisXProtocolBlockState),
        )
        assertNull(chunkDataRegistries.blockStates.resolve(BlockStateDescriptor("test:axis_block")))
    }

    @Test
    fun returnsNullForUnknownOrMalformedPersistedNames() {
        val chunkDataRegistries = testProtocolRegistryContext().toChunkDataRegistries()

        assertNull(chunkDataRegistries.blockStates.resolve(BlockStateDescriptor("test:unknown")))
        assertNull(chunkDataRegistries.blockStates.resolve(BlockStateDescriptor("bad value")))
        assertNull(chunkDataRegistries.biomes.resolve("test:unknown"))
        assertNull(chunkDataRegistries.biomes.resolve("bad value"))
    }

    @Test
    fun describesCallerSuppliedValuesWithoutMembershipValidation() {
        val chunkDataRegistries = testProtocolRegistryContext().toChunkDataRegistries()
        val externalProtocolBlockState = ProtocolBlockState(
            id = 1,
            block = Identifier("stone"),
            properties = mapOf("external" to "true"),
            isDefault = true,
        )
        val externalProtocolRegistryEntry = ProtocolRegistryEntry(Identifier("test:external_biome"), 0)

        assertEquals(
            BlockStateDescriptor("minecraft:stone", mapOf("external" to "true")),
            chunkDataRegistries.blockStates.describe(externalProtocolBlockState),
        )
        assertEquals("test:external_biome", chunkDataRegistries.biomes.name(externalProtocolRegistryEntry))
    }

    @Test
    fun selectsCallerSuppliedDefaults() {
        val protocolRegistryContext = testProtocolRegistryContext()
        val chunkDataRegistries = protocolRegistryContext.toChunkDataRegistries(
            defaultBlock = Identifier("test:axis_block"),
            defaultBiome = Identifier("desert"),
        )

        assertEquals(protocolRegistryContext.blockStates[3], chunkDataRegistries.blockStates.defaultValue)
        assertEquals(
            protocolRegistryContext.requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                Identifier("desert"),
            ),
            chunkDataRegistries.biomes.defaultValue,
        )
    }

    @Test
    fun createsADimensionHandoffBeforeChoosingSemanticDefaults() {
        val protocolRegistryContext = testProtocolRegistryContext()
        val minecraftDimensionLayout = testMinecraftDimensionLayout()

        val minecraftDimensionContext = MinecraftDimensionContext.create(
            dimensionId = DimensionId.Overworld,
            minecraftDimensionLayout = minecraftDimensionLayout,
            protocolRegistryContext = protocolRegistryContext,
        )
        val minecraftChunkContext = minecraftDimensionContext.createMinecraftChunkContext()

        assertEquals(DimensionId.Overworld, minecraftDimensionContext.dimensionId)
        assertEquals(minecraftDimensionLayout, minecraftDimensionContext.minecraftDimensionLayout)
        assertEquals(24, minecraftDimensionContext.protocolRegistryContext.chunkSectionCount)
        assertEquals(DimensionId.Overworld, minecraftChunkContext.dimensionId)
        assertEquals(minecraftDimensionLayout.dimensionTypeLayout, minecraftChunkContext.dimensionTypeLayout)
        assertEquals(24, minecraftChunkContext.protocolRegistryContext.chunkSectionCount)
        assertEquals(minecraftDimensionLayout.chunkLayout, minecraftChunkContext.chunkCodecContext.chunkLayout)
        assertEquals(
            minecraftChunkContext.chunkCodecContext,
            minecraftChunkContext.chunkNbtCodec.chunkCodecContext,
        )
    }

    @Test
    fun createsAChunkContextWithoutAProtocolDimensionIdentity() {
        val protocolRegistryContext = testProtocolRegistryContext().withChunkSectionCount(16)
        val dimensionTypeLayout = testMinecraftDimensionLayout().dimensionTypeLayout

        val minecraftChunkContext = MinecraftChunkContext.create(
            dimensionId = DimensionId.Overworld,
            dimensionTypeLayout = dimensionTypeLayout,
            protocolRegistryContext = protocolRegistryContext,
        )

        assertEquals(dimensionTypeLayout, minecraftChunkContext.dimensionTypeLayout)
        assertEquals(dimensionTypeLayout.chunkLayout, minecraftChunkContext.chunkLayout)
        assertEquals(
            dimensionTypeLayout.chunkLayout.sectionCount,
            minecraftChunkContext.protocolRegistryContext.chunkSectionCount,
        )
    }

    @Test
    fun dimensionCompositionUsesTheLayoutSectionCountWithoutCrossValidatingRegistryIdentity() {
        val protocolRegistryContext = testProtocolRegistryContext().withChunkSectionCount(16)
        val minecraftDimensionLayout = testMinecraftDimensionLayout().copy(
            dimensionTypeId = Identifier("the_nether"),
        )

        val composed = MinecraftDimensionContext.create(
            DimensionId.Overworld,
            minecraftDimensionLayout,
            protocolRegistryContext,
        )

        assertEquals(minecraftDimensionLayout, composed.minecraftDimensionLayout)
        assertEquals(minecraftDimensionLayout.sectionCount, composed.protocolRegistryContext.chunkSectionCount)
    }

    private fun testMinecraftDimensionLayout(): MinecraftDimensionLayout = MinecraftDimensionLayout(
        dimensionTypeId = Identifier("overworld"),
        dimensionTypeRawId = 0,
        dimensionTypeLayout = DimensionTypeLayout(
            minY = -64,
            height = 384,
            logicalHeight = 384,
            hasSkyLight = true,
            hasCeiling = false,
        ),
    )

    private fun testProtocolRegistryContext(): ProtocolRegistryContext {
        val air = MinecraftBlockIds.AIR
        val stone = Identifier("stone")
        val axisBlock = Identifier("test:axis_block")
        return ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    StaticRegistrySchema.BLOCK_REGISTRY,
                    listOf(
                        ProtocolRegistryEntry(air, 0),
                        ProtocolRegistryEntry(stone, 1),
                        ProtocolRegistryEntry(axisBlock, 2),
                    ),
                ),
                ProtocolRegistry(
                    ProtocolRegistryContext.BIOME_REGISTRY,
                    listOf(
                        ProtocolRegistryEntry(MinecraftBiomeIds.PLAINS, 0),
                        ProtocolRegistryEntry(Identifier("desert"), 1),
                    ),
                ),
                ProtocolRegistry(
                    MinecraftDimensionLayout.DIMENSION_TYPE_REGISTRY,
                    listOf(
                        ProtocolRegistryEntry(Identifier("overworld"), 0),
                        ProtocolRegistryEntry(Identifier("the_nether"), 1),
                    ),
                ),
            ),
            blockStates = listOf(
                ProtocolBlockState(0, air, emptyMap(), isDefault = true),
                ProtocolBlockState(1, stone, emptyMap(), isDefault = true),
                ProtocolBlockState(2, axisBlock, mapOf("axis" to "x"), isDefault = false),
                ProtocolBlockState(3, axisBlock, mapOf("axis" to "y"), isDefault = true),
            ),
        )
    }
}
