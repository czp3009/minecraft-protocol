package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionContext
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.ProtocolData
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.MinecraftCoordinates
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

/**
 * Detached values for the fixed Play packets that precede caller-managed
 * Chunk and Entity synchronization.
 */
data class MinecraftInitialWorldBootstrap(
    val difficulty: Difficulty = Difficulty.EASY,
    val difficultyLocked: Boolean = false,
    val defaultSpawn: RespawnData,
    val playerAbilities: PlayerAbilities = vanillaPlayerAbilities(PlayerGameMode.SURVIVAL),
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
        SetDefaultSpawnPositionPacket(defaultSpawn),
        ClientboundPlayerAbilitiesPacket(playerAbilities),
        SetRenderDistancePacket(viewDistance),
        SetSimulationDistancePacket(simulationDistance),
        SynchronizePlayerPositionPacket(
            teleportId = teleportId,
            change = playerPosition,
            relatives = RelativeMovements(emptySet()),
        ),
        GameEventPacket(GameEventType.LEVEL_CHUNKS_LOAD_START, 0.0f),
        SetCenterChunkPacket(centerChunk.x, centerChunk.z),
    )

    companion object {
        /** Returns the vanilla player abilities associated with [gameMode]. */
        fun vanillaPlayerAbilities(playerGameMode: PlayerGameMode): PlayerAbilities =
            PlayerAbilities(
                invulnerable = playerGameMode == PlayerGameMode.CREATIVE || playerGameMode == PlayerGameMode.SPECTATOR,
                flying = playerGameMode == PlayerGameMode.SPECTATOR,
                canFly = playerGameMode == PlayerGameMode.CREATIVE || playerGameMode == PlayerGameMode.SPECTATOR,
                instantBuild = playerGameMode == PlayerGameMode.CREATIVE,
                flyingSpeed = DEFAULT_FLYING_SPEED,
                walkingSpeed = DEFAULT_WALKING_SPEED,
            )

        /**
         * Creates the ordinary vanilla bootstrap. Defaults place the player,
         * default spawn, and Chunk center at the same position; callers may
         * supply each value independently.
         */
        fun vanilla(
            dimensionId: Identifier = Identifier("overworld"),
            difficulty: Difficulty = Difficulty.EASY,
            difficultyLocked: Boolean = false,
            gameMode: PlayerGameMode = PlayerGameMode.SURVIVAL,
            viewDistance: Int = 10,
            simulationDistance: Int = 10,
            defaultSpawnPosition: Vector3d = Vector3d(0.5, 65.0, 0.5),
            defaultSpawnYaw: Float = 0.0f,
            defaultSpawnPitch: Float = 0.0f,
            playerPosition: Vector3d = defaultSpawnPosition,
            playerDeltaMovement: Vector3d = Vector3d(0.0, 0.0, 0.0),
            playerYaw: Float = defaultSpawnYaw,
            playerPitch: Float = defaultSpawnPitch,
            centerChunk: ChunkPosition = MinecraftCoordinates.block(
                playerPosition.x,
                playerPosition.y,
                playerPosition.z,
            ).chunkPosition,
            teleportId: Int = 1,
        ): MinecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap(
            difficulty = difficulty,
            difficultyLocked = difficultyLocked,
            defaultSpawn = RespawnData(
                globalPosition = GlobalPosition(dimensionId, defaultSpawnPosition.toBlockPosition()),
                yaw = defaultSpawnYaw,
                pitch = defaultSpawnPitch,
            ),
            playerAbilities = vanillaPlayerAbilities(gameMode),
            viewDistance = viewDistance,
            simulationDistance = simulationDistance,
            playerPosition = PositionMoveRotation(
                position = playerPosition,
                deltaMovement = playerDeltaMovement,
                yaw = playerYaw,
                pitch = playerPitch,
            ),
            teleportId = teleportId,
            centerChunk = centerChunk,
        )

        /** Creates a bootstrap from the exact dimension and view settings sent by a completed negotiation. */
        fun vanilla(
            minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
            difficulty: Difficulty = Difficulty.EASY,
            difficultyLocked: Boolean = false,
            defaultSpawnPosition: Vector3d = Vector3d(0.5, 65.0, 0.5),
            defaultSpawnYaw: Float = 0.0f,
            defaultSpawnPitch: Float = 0.0f,
            playerPosition: Vector3d = defaultSpawnPosition,
            playerDeltaMovement: Vector3d = Vector3d(0.0, 0.0, 0.0),
            playerYaw: Float = defaultSpawnYaw,
            playerPitch: Float = defaultSpawnPitch,
            centerChunk: ChunkPosition = MinecraftCoordinates.block(
                playerPosition.x,
                playerPosition.y,
                playerPosition.z,
            ).chunkPosition,
            teleportId: Int = 1,
        ): MinecraftInitialWorldBootstrap = vanilla(
            dimensionId = minecraftServerNegotiationResult.playLoginPacket.spawnInfo.dimension,
            difficulty = difficulty,
            difficultyLocked = difficultyLocked,
            gameMode = minecraftServerNegotiationResult.playLoginPacket.spawnInfo.gameMode,
            viewDistance = minecraftServerNegotiationResult.playLoginPacket.chunkRadius,
            simulationDistance = minecraftServerNegotiationResult.playLoginPacket.simulationDistance,
            defaultSpawnPosition = defaultSpawnPosition,
            defaultSpawnYaw = defaultSpawnYaw,
            defaultSpawnPitch = defaultSpawnPitch,
            playerPosition = playerPosition,
            playerDeltaMovement = playerDeltaMovement,
            playerYaw = playerYaw,
            playerPitch = playerPitch,
            centerChunk = centerChunk,
            teleportId = teleportId,
        )
    }
}

/** A finite one-shot initial projection for examples and simple servers. */
data class MinecraftInitialWorld(
    val minecraftInitialWorldBootstrap: MinecraftInitialWorldBootstrap,
    val chunks: List<MinecraftChunkSnapshot>,
    val entities: List<MinecraftEntitySnapshot> = emptyList(),
) {
    companion object {
        /** Creates the ordinary finite flat projection for the dimension selected by completed negotiation. */
        fun flatVanilla(
            minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
            groundY: Int = 64,
            difficulty: Difficulty = Difficulty.EASY,
            difficultyLocked: Boolean = false,
            chunkRadius: Int = minecraftServerNegotiationResult.playLoginPacket.chunkRadius,
            surfaceBlockId: Identifier = Identifier("grass_block"),
            biomeId: Identifier = MinecraftBiomeIds.PLAINS,
            entities: List<MinecraftEntitySnapshot> = emptyList(),
        ): MinecraftInitialWorld = flatVanilla(
            minecraftDimensionContext = minecraftServerNegotiationResult.minecraftDimensionContext,
            groundY = groundY,
            minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
                minecraftServerNegotiationResult = minecraftServerNegotiationResult,
                difficulty = difficulty,
                difficultyLocked = difficultyLocked,
                defaultSpawnPosition = Vector3d(0.5, groundY + 1.0, 0.5),
            ),
            chunkRadius = chunkRadius,
            surfaceBlockId = surfaceBlockId,
            biomeId = biomeId,
            entities = entities,
        )

        /**
         * Creates a vanilla flat-world projection around [minecraftInitialWorldBootstrap]'s Chunk
         * center. The radius is measured in Chunks.
         */
        fun flatVanilla(
            protocolData: ProtocolData = VanillaProtocolData,
            dimensionId: Identifier = Identifier("overworld"),
            groundY: Int = 64,
            minecraftInitialWorldBootstrap: MinecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
                dimensionId = dimensionId,
                defaultSpawnPosition = Vector3d(0.5, groundY + 1.0, 0.5),
            ),
            chunkRadius: Int = minecraftInitialWorldBootstrap.viewDistance,
            surfaceBlockId: Identifier = Identifier("grass_block"),
            biomeId: Identifier = MinecraftBiomeIds.PLAINS,
            entities: List<MinecraftEntitySnapshot> = emptyList(),
        ): MinecraftInitialWorld = flatVanilla(
            minecraftDimensionContext = MinecraftDimensionContext.create(
                dimensionId = DimensionId.parse(dimensionId.toString()),
                minecraftDimensionLayout = MinecraftDimensionLayout.from(protocolData, dimensionId),
                protocolRegistryContext = protocolData.completeProtocolRegistryContext,
            ),
            groundY = groundY,
            minecraftInitialWorldBootstrap = minecraftInitialWorldBootstrap,
            chunkRadius = chunkRadius,
            surfaceBlockId = surfaceBlockId,
            biomeId = biomeId,
            entities = entities,
        )

        /** Creates a flat initial projection with an already-resolved dimension context. */
        fun flatVanilla(
            minecraftDimensionContext: MinecraftDimensionContext,
            groundY: Int = 64,
            minecraftInitialWorldBootstrap: MinecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
                dimensionId = Identifier.parse(minecraftDimensionContext.dimensionId.toString()),
                defaultSpawnPosition = Vector3d(0.5, groundY + 1.0, 0.5),
            ),
            chunkRadius: Int = minecraftInitialWorldBootstrap.viewDistance,
            surfaceBlockId: Identifier = Identifier("grass_block"),
            biomeId: Identifier = MinecraftBiomeIds.PLAINS,
            entities: List<MinecraftEntitySnapshot> = emptyList(),
        ): MinecraftInitialWorld {
            val chunks = MinecraftCoordinates
                .chunkPositionsAround(minecraftInitialWorldBootstrap.centerChunk, chunkRadius)
                .map { chunkPosition ->
                    MinecraftChunkSnapshot.flat(
                        protocolRegistryContext = minecraftDimensionContext.protocolRegistryContext,
                        minecraftDimensionLayout = minecraftDimensionContext.minecraftDimensionLayout,
                        chunkX = chunkPosition.x,
                        chunkZ = chunkPosition.z,
                        groundY = groundY,
                        surfaceBlockId = surfaceBlockId,
                        biomeId = biomeId,
                    )
                }
                .toList()
            return MinecraftInitialWorld(
                minecraftInitialWorldBootstrap = minecraftInitialWorldBootstrap,
                chunks = chunks,
                entities = entities,
            )
        }
    }
}

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
 */
suspend fun MinecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld: MinecraftInitialWorld) {
    sendInitialWorldBootstrap(minecraftInitialWorld.minecraftInitialWorldBootstrap)
    outgoing.send(ChunkBatchStartPacket)
    minecraftInitialWorld.chunks.forEach { outgoing.send(it.packet()) }
    outgoing.send(ChunkBatchFinishedPacket(minecraftInitialWorld.chunks.size))
    minecraftInitialWorld.entities.forEach { minecraftEntitySnapshot -> sendEntitySnapshot(minecraftEntitySnapshot) }
}

private fun Vector3d.toBlockPosition(): BlockPosition =
    BlockPosition(
        x = MinecraftCoordinates.blockCoordinate(x),
        y = MinecraftCoordinates.blockCoordinate(y),
        z = MinecraftCoordinates.blockCoordinate(z),
    )

private const val DEFAULT_FLYING_SPEED: Float = 0.05f
private const val DEFAULT_WALKING_SPEED: Float = 0.1f
