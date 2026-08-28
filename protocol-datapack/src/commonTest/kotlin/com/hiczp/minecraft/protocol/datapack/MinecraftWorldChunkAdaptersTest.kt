package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.BlockStateDescriptor
import com.hiczp.minecraft.world.format.ChunkLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinecraftWorldChunkAdaptersTest {
    @Test
    fun convertsDimensionBlockBoundsToAChunkLayout() {
        val minecraftDimensionLayout = MinecraftDimensionLayout(
            dimensionTypeId = Identifier("overworld"),
            dimensionTypeRawId = 0,
            minY = -64,
            height = 384,
            hasSkyLight = true,
        )

        val chunkLayout = minecraftDimensionLayout.toChunkLayout()

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
            Identifier("plains"),
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
    fun doesNotDescribeValuesOutsideTheActiveContext() {
        val chunkDataRegistries = testProtocolRegistryContext().toChunkDataRegistries()
        val externalProtocolBlockState = ProtocolBlockState(
            id = 1,
            block = Identifier("stone"),
            properties = mapOf("external" to "true"),
            isDefault = true,
        )
        val externalProtocolRegistryEntry = ProtocolRegistryEntry(Identifier("test:external_biome"), 0)

        assertNull(chunkDataRegistries.blockStates.describe(externalProtocolBlockState))
        assertNull(chunkDataRegistries.biomes.name(externalProtocolRegistryEntry))
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

    private fun testProtocolRegistryContext(): ProtocolRegistryContext {
        val air = Identifier("air")
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
                        ProtocolRegistryEntry(Identifier("plains"), 0),
                        ProtocolRegistryEntry(Identifier("desert"), 1),
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
