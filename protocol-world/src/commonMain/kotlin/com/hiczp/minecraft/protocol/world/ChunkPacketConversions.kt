package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.ClientboundLevelChunkWithLightPacket
import com.hiczp.minecraft.world.format.Chunk
import com.hiczp.minecraft.world.format.ChunkPosition

val ClientboundLevelChunkWithLightPacket.chunkPosition: ChunkPosition get() = ChunkPosition(x, z)

fun Chunk.toClientboundLevelChunkWithLightPacket(chunkPacketEncoder: ChunkPacketEncoder): ClientboundLevelChunkWithLightPacket =
    chunkPacketEncoder.encode(this)

fun Chunk.toClientboundLevelChunkWithLightPacket(chunkPacketEncoderContext: ChunkPacketEncoderContext): ClientboundLevelChunkWithLightPacket =
    ChunkPacketEncoder(chunkPacketEncoderContext).encode(this)

fun ClientboundLevelChunkWithLightPacket.toChunk(chunkPacketDecoder: ChunkPacketDecoder): Chunk =
    chunkPacketDecoder.decode(this)

fun ClientboundLevelChunkWithLightPacket.toChunk(chunkPacketDecoderContext: ChunkPacketDecoderContext): Chunk =
    ChunkPacketDecoder(chunkPacketDecoderContext).decode(this)
