package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*

/**
 * A ready-to-use semantic Chunk context for one world dimension.
 *
 * Unlike [MinecraftDimensionContext], this value contains no synchronized dimension-type raw ID. Persistent Chunk NBT
 * and Chunk packet bodies need the dimension layout and active block/biome registries, but not the Play Login identity.
 */
class MinecraftChunkContext private constructor(
    val dimensionId: DimensionId,
    val dimensionTypeLayout: DimensionTypeLayout,
    val protocolRegistryContext: ProtocolRegistryContext,
    val chunkCodecContext: ChunkCodecContext<ProtocolBlockState, ProtocolRegistryEntry>,
    val chunkNbtCodec: ChunkNbtCodec<ProtocolBlockState, ProtocolRegistryEntry>,
    val defaultBlockId: Identifier,
    val defaultBiomeId: Identifier,
) {
    val chunkLayout: ChunkLayout
        get() = chunkCodecContext.chunkLayout

    companion object {
        fun create(
            dimensionId: DimensionId,
            dimensionTypeLayout: DimensionTypeLayout,
            protocolRegistryContext: ProtocolRegistryContext,
            defaultBlock: Identifier = MinecraftBlockIds.AIR,
            defaultBiome: Identifier = MinecraftBiomeIds.PLAINS,
        ): MinecraftChunkContext = create(
            dimensionId = dimensionId,
            dimensionTypeLayout = dimensionTypeLayout,
            protocolRegistryContext = protocolRegistryContext,
            chunkDataRegistries = protocolRegistryContext.toChunkDataRegistries(defaultBlock, defaultBiome),
            defaultBlock = defaultBlock,
            defaultBiome = defaultBiome,
        )

        internal fun create(
            minecraftDimensionContext: MinecraftDimensionContext,
            defaultBlock: Identifier,
            defaultBiome: Identifier,
        ): MinecraftChunkContext = create(
            minecraftDimensionContext = minecraftDimensionContext,
            chunkDataRegistries = minecraftDimensionContext.protocolRegistryContext.toChunkDataRegistries(
                defaultBlock,
                defaultBiome,
            ),
            defaultBlock = defaultBlock,
            defaultBiome = defaultBiome,
        )

        internal fun create(
            minecraftDimensionContext: MinecraftDimensionContext,
            chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry>,
            defaultBlock: Identifier,
            defaultBiome: Identifier,
        ): MinecraftChunkContext = createActive(
            dimensionId = minecraftDimensionContext.dimensionId,
            dimensionTypeLayout = minecraftDimensionContext.minecraftDimensionLayout.dimensionTypeLayout,
            protocolRegistryContext = minecraftDimensionContext.protocolRegistryContext,
            chunkDataRegistries = chunkDataRegistries,
            defaultBlock = defaultBlock,
            defaultBiome = defaultBiome,
        )

        internal fun create(
            dimensionId: DimensionId,
            dimensionTypeLayout: DimensionTypeLayout,
            protocolRegistryContext: ProtocolRegistryContext,
            chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry>,
            defaultBlock: Identifier,
            defaultBiome: Identifier,
        ): MinecraftChunkContext {
            val chunkLayout = dimensionTypeLayout.chunkLayout
            val activeProtocolRegistryContext = protocolRegistryContext.forChunkLayout(chunkLayout)
            return createActive(
                dimensionId = dimensionId,
                dimensionTypeLayout = dimensionTypeLayout,
                protocolRegistryContext = activeProtocolRegistryContext,
                chunkDataRegistries = chunkDataRegistries,
                defaultBlock = defaultBlock,
                defaultBiome = defaultBiome,
            )
        }

        private fun createActive(
            dimensionId: DimensionId,
            dimensionTypeLayout: DimensionTypeLayout,
            protocolRegistryContext: ProtocolRegistryContext,
            chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry>,
            defaultBlock: Identifier,
            defaultBiome: Identifier,
        ): MinecraftChunkContext {
            val chunkCodecContext = ChunkCodecContext(
                chunkLayout = dimensionTypeLayout.chunkLayout,
                chunkDataRegistries = chunkDataRegistries,
            )
            return MinecraftChunkContext(
                dimensionId = dimensionId,
                dimensionTypeLayout = dimensionTypeLayout,
                protocolRegistryContext = protocolRegistryContext,
                chunkCodecContext = chunkCodecContext,
                chunkNbtCodec = ChunkNbtCodec(chunkCodecContext),
                defaultBlockId = defaultBlock,
                defaultBiomeId = defaultBiome,
            )
        }
    }
}

private fun ProtocolRegistryContext.forChunkLayout(chunkLayout: ChunkLayout): ProtocolRegistryContext =
    if (chunkSectionCount == chunkLayout.sectionCount) this else withChunkSectionCount(chunkLayout.sectionCount)
