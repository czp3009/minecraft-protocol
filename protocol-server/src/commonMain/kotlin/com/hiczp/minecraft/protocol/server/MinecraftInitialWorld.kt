package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.ChunkPosition
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
    ).chunk,
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
        fun vanillaPlayerAbilities(gameMode: PlayerGameMode): PlayerAbilities =
            PlayerAbilities(
                invulnerable = gameMode == PlayerGameMode.CREATIVE || gameMode == PlayerGameMode.SPECTATOR,
                flying = gameMode == PlayerGameMode.SPECTATOR,
                canFly = gameMode == PlayerGameMode.CREATIVE || gameMode == PlayerGameMode.SPECTATOR,
                instantBuild = gameMode == PlayerGameMode.CREATIVE,
                flyingSpeed = DEFAULT_FLYING_SPEED,
                walkingSpeed = DEFAULT_WALKING_SPEED,
            )

        /**
         * Creates the ordinary vanilla bootstrap. Defaults place the player,
         * default spawn, and Chunk center at the same position; callers may
         * supply each value independently.
         */
        fun vanilla(
            options: MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(),
            dimensionId: Identifier = Identifier("overworld"),
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
            ).chunk,
            teleportId: Int = 1,
        ): MinecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap(
            difficulty = options.difficulty,
            difficultyLocked = options.difficultyLocked,
            defaultSpawn = RespawnData(
                globalPosition = GlobalPosition(dimensionId, defaultSpawnPosition.toBlockPosition()),
                yaw = defaultSpawnYaw,
                pitch = defaultSpawnPitch,
            ),
            playerAbilities = vanillaPlayerAbilities(options.gameMode),
            viewDistance = options.viewDistance,
            simulationDistance = options.simulationDistance,
            playerPosition = PositionMoveRotation(
                position = playerPosition,
                deltaMovement = playerDeltaMovement,
                yaw = playerYaw,
                pitch = playerPitch,
            ),
            teleportId = teleportId,
            centerChunk = centerChunk,
        )
    }
}

/** A finite one-shot initial projection for examples and simple servers. */
data class MinecraftInitialWorld(
    val bootstrap: MinecraftInitialWorldBootstrap,
    val chunks: List<MinecraftChunkSnapshot>,
    val entities: List<MinecraftEntitySnapshot> = emptyList(),
) {
    companion object {
        /**
         * Creates a vanilla flat-world projection around [bootstrap]'s Chunk
         * center. The radius is measured in Chunks.
         */
        fun flatVanilla(
            options: MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(),
            dimensionId: Identifier = Identifier("overworld"),
            groundY: Int = 64,
            bootstrap: MinecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
                options = options,
                dimensionId = dimensionId,
                defaultSpawnPosition = Vector3d(0.5, groundY + 1.0, 0.5),
            ),
            chunkRadius: Int = options.viewDistance,
            surfaceBlockId: Identifier = Identifier("grass_block"),
            biomeId: Identifier = Identifier("plains"),
            entities: List<MinecraftEntitySnapshot> = emptyList(),
        ): MinecraftInitialWorld {
            val minecraftDimensionLayout = MinecraftDimensionLayout.from(
                options.protocolData,
                dimensionId,
            )
            val protocolRegistryContext = options.protocolData.completeProtocolRegistryContext
            val chunks = MinecraftCoordinates.chunkPositionsAround(bootstrap.centerChunk, chunkRadius).map { position ->
                MinecraftChunkSnapshot.flat(
                    protocolRegistryContext = protocolRegistryContext,
                    minecraftDimensionLayout = minecraftDimensionLayout,
                    chunkX = position.x,
                    chunkZ = position.z,
                    groundY = groundY,
                    surfaceBlockId = surfaceBlockId,
                    biomeId = biomeId,
                )
            }.toList()
            return MinecraftInitialWorld(
                bootstrap = bootstrap,
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
    bootstrap: MinecraftInitialWorldBootstrap,
): Unit = bootstrap.packets().forEach { outgoing.send(it) }

/**
 * Enqueues the bootstrap, one complete Chunk batch, and every Entity pairing
 * bundle. This function neither waits for acknowledgements nor flushes.
 */
suspend fun MinecraftServerConnection.synchronizeInitialWorld(world: MinecraftInitialWorld) {
    sendInitialWorldBootstrap(world.bootstrap)
    outgoing.send(ChunkBatchStartPacket)
    world.chunks.forEach { outgoing.send(it.packet()) }
    outgoing.send(ChunkBatchFinishedPacket(world.chunks.size))
    world.entities.forEach { entity -> sendEntitySnapshot(entity) }
}

private fun Vector3d.toBlockPosition(): BlockPosition =
    BlockPosition(
        x = MinecraftCoordinates.blockCoordinate(x),
        y = MinecraftCoordinates.blockCoordinate(y),
        z = MinecraftCoordinates.blockCoordinate(z),
    )

private const val DEFAULT_FLYING_SPEED: Float = 0.05f
private const val DEFAULT_WALKING_SPEED: Float = 0.1f
