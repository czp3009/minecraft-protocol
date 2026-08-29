package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolBlockState
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryEntry
import com.hiczp.minecraft.world.format.ChunkCodecContext
import com.hiczp.minecraft.world.format.ChunkDataRegistries
import com.hiczp.minecraft.world.format.ChunkNbtCodec
import com.hiczp.minecraft.world.format.DimensionId

/** A ready-to-use protocol and persistent-Chunk context for one world dimension. */
class MinecraftChunkContext private constructor(
    val minecraftDimensionContext: MinecraftDimensionContext,
    val chunkCodecContext: ChunkCodecContext<ProtocolBlockState, ProtocolRegistryEntry>,
    val chunkNbtCodec: ChunkNbtCodec<ProtocolBlockState, ProtocolRegistryEntry>,
    val defaultBlockId: Identifier,
    val defaultBiomeId: Identifier,
) {
    val dimensionId: DimensionId
        get() = minecraftDimensionContext.dimensionId

    val minecraftDimensionLayout: MinecraftDimensionLayout
        get() = minecraftDimensionContext.minecraftDimensionLayout

    val protocolRegistryContext: ProtocolRegistryContext
        get() = minecraftDimensionContext.protocolRegistryContext

    companion object {
        fun create(
            dimensionId: DimensionId,
            minecraftDimensionLayout: MinecraftDimensionLayout,
            protocolRegistryContext: ProtocolRegistryContext,
            defaultBlock: Identifier = Identifier("air"),
            defaultBiome: Identifier = Identifier("plains"),
        ): MinecraftChunkContext {
            val minecraftDimensionContext = MinecraftDimensionContext.create(
                dimensionId = dimensionId,
                minecraftDimensionLayout = minecraftDimensionLayout,
                protocolRegistryContext = protocolRegistryContext,
            )
            return create(
                minecraftDimensionContext = minecraftDimensionContext,
                defaultBlock = defaultBlock,
                defaultBiome = defaultBiome,
            )
        }

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
            dimensionId: DimensionId,
            minecraftDimensionLayout: MinecraftDimensionLayout,
            protocolRegistryContext: ProtocolRegistryContext,
            chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry>,
            defaultBlock: Identifier,
            defaultBiome: Identifier,
        ): MinecraftChunkContext = create(
            minecraftDimensionContext = MinecraftDimensionContext.create(
                dimensionId = dimensionId,
                minecraftDimensionLayout = minecraftDimensionLayout,
                protocolRegistryContext = protocolRegistryContext,
            ),
            chunkDataRegistries = chunkDataRegistries,
            defaultBlock = defaultBlock,
            defaultBiome = defaultBiome,
        )

        private fun create(
            minecraftDimensionContext: MinecraftDimensionContext,
            chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry>,
            defaultBlock: Identifier,
            defaultBiome: Identifier,
        ): MinecraftChunkContext {
            val chunkCodecContext = ChunkCodecContext(
                chunkLayout = minecraftDimensionContext.chunkLayout,
                chunkDataRegistries = chunkDataRegistries,
            )
            return MinecraftChunkContext(
                minecraftDimensionContext = minecraftDimensionContext,
                chunkCodecContext = chunkCodecContext,
                chunkNbtCodec = ChunkNbtCodec(chunkCodecContext),
                defaultBlockId = defaultBlock,
                defaultBiomeId = defaultBiome,
            )
        }
    }
}
