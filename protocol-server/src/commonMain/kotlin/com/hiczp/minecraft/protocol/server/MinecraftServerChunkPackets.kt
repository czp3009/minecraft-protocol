package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.world.ChunkPacketEncoder
import com.hiczp.minecraft.protocol.world.ChunkPacketEncoderContext
import com.hiczp.minecraft.protocol.world.ChunkPacketRequiredDataProvider
import com.hiczp.minecraft.protocol.world.ChunkPacketWriteMappings
import com.hiczp.minecraft.world.format.BiomeId
import com.hiczp.minecraft.world.format.BlockState

/**
 * Constructs a plain encoder using this result's initial dimension and registry epoch, without connection access.
 * The caller supplies absent-terrain defaults, client-visible update tags and any missing projection data.
 * The encoder never reads configuration from the encoded Chunk's context. Rebind after a dimension or registry change.
 */
fun MinecraftServerNegotiationResult.chunkPacketEncoder(
    defaultBlockState: BlockState,
    defaultBiome: BiomeId,
    chunkPacketWriteMappings: ChunkPacketWriteMappings,
    chunkPacketRequiredDataProvider: ChunkPacketRequiredDataProvider,
): ChunkPacketEncoder = ChunkPacketEncoder(
    ChunkPacketEncoderContext(
        chunkLayout = minecraftDimensionContext.chunkLayout,
        hasSkyLight = minecraftDimensionContext.minecraftDimensionLayout.hasSkyLight,
        defaultBlockState = defaultBlockState,
        defaultBiome = defaultBiome,
        packetCodecContext = minecraftDimensionContext.packetCodecContext,
        chunkPacketWriteMappings = chunkPacketWriteMappings,
        chunkPacketRequiredDataProvider = chunkPacketRequiredDataProvider,
    ),
)
