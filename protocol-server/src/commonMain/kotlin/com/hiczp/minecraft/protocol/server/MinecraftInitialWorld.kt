package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.world.ChunkPacketEncoder
import com.hiczp.minecraft.world.format.Chunk
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.MinecraftCoordinates

/**
 * Detached values for the fixed Play packets that precede caller-managed
 * Chunk and Entity synchronization.
 */
data class MinecraftInitialWorldBootstrap(
    val difficulty: Difficulty = Difficulty.EASY,
    val difficultyLocked: Boolean = false,
    val defaultSpawn: RespawnData,
    val playerAbilities: PlayerAbilities,
    val viewDistance: Int,
    val simulationDistance: Int,
    val playerPosition: PositionMoveRotation,
    val teleportId: Int = 1,
    val centerChunk: ChunkPosition = MinecraftCoordinates.block(
        playerPosition.position.x,
        playerPosition.position.y,
        playerPosition.position.z,
    ).chunkPosition,
) {
    /** Creates the fixed packets in their initial Play order. */
    fun packets(): List<ClientboundPacket> = listOf(
        ClientboundChangeDifficultyPacket(difficulty, difficultyLocked),
        ClientboundSetDefaultSpawnPositionPacket(defaultSpawn),
        ClientboundPlayerAbilitiesPacket(playerAbilities),
        ClientboundSetChunkCacheRadiusPacket(viewDistance),
        ClientboundSetSimulationDistancePacket(simulationDistance),
        ClientboundPlayerPositionPacket(
            id = teleportId,
            change = playerPosition,
            relatives = RelativeMovements(emptySet()),
        ),
        ClientboundGameEventPacket(GameEventType.LEVEL_CHUNKS_LOAD_START, 0.0f),
        ClientboundSetChunkCacheCenterPacket(centerChunk.x, centerChunk.z),
    )
}

/**
 * A finite view to enqueue with [synchronizeInitialWorld].
 *
 * Retains the supplied Chunks and encoder; conversion reads their current contents when sending starts. Callers finish
 * mutations before sending and supply an encoder for the destination's dimension and registry epoch. This value owns
 * neither a world nor a pending queue, tick loop or acknowledgement state.
 */
data class MinecraftInitialWorld(
    val minecraftInitialWorldBootstrap: MinecraftInitialWorldBootstrap,
    val chunks: List<Chunk>,
    val chunkPacketEncoder: ChunkPacketEncoder,
    val entityBatches: List<MinecraftEntityBatch> = emptyList(),
)

/**
 * Enqueues only the fixed bootstrap before caller-managed Chunk and Entity
 * synchronization. This function does not flush the connection.
 */
suspend fun MinecraftServerConnection.sendInitialWorldBootstrap(
    minecraftInitialWorldBootstrap: MinecraftInitialWorldBootstrap,
): Unit = minecraftInitialWorldBootstrap.packets().forEach { outgoing.send(it) }

/**
 * Enqueues the bootstrap, one complete Chunk batch, and every Entity pairing
 * bundle. This function neither waits for acknowledgements nor flushes.
 *
 * Run an incoming consumer concurrently when the outgoing batch may fill a bounded channel. After enqueueing, request
 * a flush at the application's chosen boundary and process teleport/Chunk-batch acknowledgements in its packet loop.
 */
suspend fun MinecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld: MinecraftInitialWorld) {
    sendInitialWorldBootstrap(minecraftInitialWorld.minecraftInitialWorldBootstrap)
    outgoing.send(ClientboundChunkBatchStartPacket)
    minecraftInitialWorld.chunks.forEach { outgoing.send(minecraftInitialWorld.chunkPacketEncoder.encode(it)) }
    outgoing.send(ClientboundChunkBatchFinishedPacket(minecraftInitialWorld.chunks.size))
    minecraftInitialWorld.entityBatches.forEach { outgoing.send(encodeEntityBundle(it)) }
}
