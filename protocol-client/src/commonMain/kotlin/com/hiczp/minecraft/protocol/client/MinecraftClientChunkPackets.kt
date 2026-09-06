package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.world.ChunkPacketDecoder
import com.hiczp.minecraft.protocol.world.ChunkPacketDecoderContext
import com.hiczp.minecraft.protocol.world.ChunkPacketMissingDataProvider
import com.hiczp.minecraft.protocol.world.ChunkPacketReadMappings
import com.hiczp.minecraft.world.format.BiomeId
import com.hiczp.minecraft.world.format.BlockState

/**
 * Constructs a plain decoder using this result's initial dimension and registry epoch, without connection access.
 * The caller supplies terrain defaults, update-tag interpretation and local facts absent from full Chunk packets.
 * Reuse the decoder while those inputs stay valid; construct a new one after reconfiguration or a dimension change.
 */
fun MinecraftClientNegotiationResult.chunkPacketDecoder(
    defaultBlockState: BlockState,
    defaultBiome: BiomeId,
    chunkPacketReadMappings: ChunkPacketReadMappings,
    chunkPacketMissingDataProvider: ChunkPacketMissingDataProvider,
): ChunkPacketDecoder = ChunkPacketDecoder(
    ChunkPacketDecoderContext(
        chunkContext = minecraftDimensionContext.chunkContext(defaultBlockState, defaultBiome),
        packetCodecContext = minecraftDimensionContext.packetCodecContext,
        chunkPacketReadMappings = chunkPacketReadMappings,
        chunkPacketMissingDataProvider = chunkPacketMissingDataProvider,
    ),
)
