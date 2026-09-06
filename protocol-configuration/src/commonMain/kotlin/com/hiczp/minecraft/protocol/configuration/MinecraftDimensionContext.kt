package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.world.format.BiomeId
import com.hiczp.minecraft.world.format.BlockState
import com.hiczp.minecraft.world.format.ChunkContext
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.DimensionId

/** Configuration identities and layout active for one dimension in a connection epoch. */
data class MinecraftDimensionContext(
    val dimensionId: DimensionId,
    val minecraftDimensionLayout: MinecraftDimensionLayout,
    val packetCodecContext: PacketCodecContext,
) {
    val chunkLayout: ChunkLayout
        get() = minecraftDimensionLayout.chunkLayout

    /** Creates a raw-ID-free domain context with caller-selected defaults for absent block and biome cells. */
    fun chunkContext(defaultBlockState: BlockState, defaultBiome: BiomeId): ChunkContext = ChunkContext(
        dimensionId,
        minecraftDimensionLayout.dimensionTypeLayout,
        defaultBlockState,
        defaultBiome,
    )
}
