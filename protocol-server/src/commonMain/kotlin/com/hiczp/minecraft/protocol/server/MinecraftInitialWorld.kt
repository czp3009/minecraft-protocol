package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.math.floor
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

/**
 * A finite initial projection sent after Play Login. Applications remain the
 * owners of authoritative worlds, chunks, entities, ticking, and persistence.
 */
data class MinecraftInitialWorld(
    val dimension: Identifier,
    val dimensionType: MinecraftDimensionLayout,
    val spawnPosition: Vector3d,
    val spawnYaw: Float = 0.0f,
    val spawnPitch: Float = 0.0f,
    val viewDistance: Int,
    val simulationDistance: Int,
    val difficulty: Difficulty = Difficulty.EASY,
    val difficultyLocked: Boolean = false,
    val playerAbilities: PlayerAbilities =
        vanillaPlayerAbilities(PlayerGameMode.SURVIVAL),
    val teleportId: Int = 1,
    val chunks: List<MinecraftChunkSnapshot>,
    val entities: List<MinecraftEntitySnapshot> = emptyList(),
) {
    init {
        require(spawnPosition.isFinite()) {
            "The initial player position must be finite"
        }
        spawnPosition.toBlockPosition()
        require(spawnYaw.isFinite() && spawnPitch.isFinite()) {
            "The initial player rotation must be finite"
        }
        require(
            viewDistance in
                    MinecraftServerNegotiationOptions.MIN_VIEW_DISTANCE..
                    MinecraftServerNegotiationOptions.MAX_VIEW_DISTANCE,
        ) {
            "View distance must be in ${MinecraftServerNegotiationOptions.MIN_VIEW_DISTANCE}..${MinecraftServerNegotiationOptions.MAX_VIEW_DISTANCE}"
        }
        require(simulationDistance >= 0)
        require(teleportId >= 0)
        require(
            chunks
                .map { it.chunkX to it.chunkZ }
                .toSet()
                .size == chunks.size,
        ) {
            "Initial chunks must have unique coordinates"
        }
        require(
            entities.map(MinecraftEntitySnapshot::entityId).toSet().size ==
                    entities.size
        ) {
            "Initial entities must have unique entity IDs"
        }
        require(
            chunks.all {
                it.chunkData.sections.size == dimensionType.sectionCount
            },
        ) {
            "Every initial chunk must match the active dimension height"
        }
    }

    companion object {
        /**
         * Creates a vanilla flat-world projection centered on the spawn
         * chunk. The radius is measured in chunks.
         */
        fun flatVanilla(
            options: MinecraftServerNegotiationOptions,
            dimension: Identifier = Identifier("overworld"),
            groundY: Int = 64,
            spawnPosition: Vector3d =
                Vector3d(0.5, groundY + 1.0, 0.5),
            chunkRadius: Int = options.viewDistance,
            surfaceBlock: Identifier = Identifier("grass_block"),
            biome: Identifier = Identifier("plains"),
            entities: List<MinecraftEntitySnapshot> = emptyList(),
        ): MinecraftInitialWorld {
            require(chunkRadius >= 0)
            val dimensionType = MinecraftDimensionLayout.from(
                options.protocolData,
                dimension,
            )
            val registries = options.protocolData.registryContext
            val centerX = floor(spawnPosition.x / CHUNK_SIZE).toInt()
            val centerZ = floor(spawnPosition.z / CHUNK_SIZE).toInt()
            val chunks = buildList {
                for (chunkZ in centerZ - chunkRadius..centerZ + chunkRadius) {
                    for (
                    chunkX in
                    centerX - chunkRadius..centerX + chunkRadius
                    ) {
                        add(
                            MinecraftChunkSnapshot.flat(
                                registries = registries,
                                dimension = dimensionType,
                                chunkX = chunkX,
                                chunkZ = chunkZ,
                                groundY = groundY,
                                surfaceBlock = surfaceBlock,
                                biome = biome,
                            ),
                        )
                    }
                }
            }
            return MinecraftInitialWorld(
                dimension = dimension,
                dimensionType = dimensionType,
                spawnPosition = spawnPosition,
                viewDistance = options.viewDistance,
                simulationDistance = options.simulationDistance,
                difficulty = options.difficulty,
                difficultyLocked = options.difficultyLocked,
                playerAbilities = vanillaPlayerAbilities(options.gameMode),
                chunks = chunks,
                entities = entities,
            )
        }
        private const val CHUNK_SIZE: Double = 16.0
    }
}

data class MinecraftInitialWorldSynchronization(
    val teleportId: Int,
    val chunkCount: Int,
    val entityCount: Int,
)

/**
 * Sends the stateless Play bootstrap needed for a client to place the player,
 * accept chunk columns, render blocks and track initial entities.
 */
suspend fun MinecraftServerConnection.synchronizeInitialWorld(
    world: MinecraftInitialWorld,
): MinecraftInitialWorldSynchronization {
    require(state == ConnectionState.PLAY) {
        "Initial world synchronization requires a Play session"
    }
    validateInitialWorld(
        world = world,
        login = checkNotNull(playLogin) {
            "Initial world synchronization requires a completed Play login"
        },
        registries = registries,
    )

    outgoing.send(
        ClientboundChangeDifficultyPacket(
            difficulty = world.difficulty,
            locked = world.difficultyLocked,
        ),
    )
    outgoing.send(
        SetDefaultSpawnPositionPacket(
            RespawnData(
                globalPosition = GlobalPosition(
                    dimension = world.dimension,
                    position = world.spawnPosition.toBlockPosition(),
                ),
                yaw = world.spawnYaw,
                pitch = world.spawnPitch,
            ),
        ),
    )
    outgoing.send(
        ClientboundPlayerAbilitiesPacket(
            world.playerAbilities,
        ),
    )
    outgoing.send(SetRenderDistancePacket(world.viewDistance))
    outgoing.send(SetSimulationDistancePacket(world.simulationDistance))
    outgoing.send(
        SynchronizePlayerPositionPacket(
            teleportId = world.teleportId,
            change = PositionMoveRotation(
                position = world.spawnPosition,
                deltaMovement = Vector3d(0.0, 0.0, 0.0),
                yaw = world.spawnYaw,
                pitch = world.spawnPitch,
            ),
            relatives = RelativeMovements(emptySet()),
        ),
    )
    outgoing.send(
        GameEventPacket(
            event = GameEventType.LEVEL_CHUNKS_LOAD_START,
            value = 0.0f,
        ),
    )
    outgoing.send(
        SetCenterChunkPacket(
            chunkX = floor(world.spawnPosition.x / CHUNK_SIZE).toInt(),
            chunkZ = floor(world.spawnPosition.z / CHUNK_SIZE).toInt(),
        ),
    )
    outgoing.send(ChunkBatchStartPacket)
    world.chunks.forEach { outgoing.send(it.packet()) }
    outgoing.send(ChunkBatchFinishedPacket(world.chunks.size))
    world.entities.forEach { entity ->
        entity.packets(registries).forEach { outgoing.send(it) }
    }

    return MinecraftInitialWorldSynchronization(
        teleportId = world.teleportId,
        chunkCount = world.chunks.size,
        entityCount = world.entities.size,
    )
}

internal fun validateInitialWorld(
    world: MinecraftInitialWorld,
    login: PlayLoginPacket,
    registries: ProtocolRegistryContext,
) {
    require(world.dimension == login.spawnInfo.dimension) {
        "Initial world dimension does not match the Play login dimension"
    }
    require(world.dimension in login.levels) {
        "Initial world dimension is absent from the advertised Play levels"
    }
    require(
        world.dimensionType.registryId == login.spawnInfo.dimensionTypeId,
    ) {
        "Initial dimension-type registry ID does not match Play login"
    }
    require(registries.chunkSectionCount == world.dimensionType.sectionCount) {
        "Initial dimension height does not match the installed registry context"
    }
    require(world.entities.none { it.entityId == login.playerId }) {
        "Initial entities must not reuse the player entity ID"
    }
}

private fun Vector3d.toBlockPosition(): BlockPosition =
    BlockPosition(
        x = floor(x).toInt(),
        y = floor(y).toInt(),
        z = floor(z).toInt(),
    )

private fun Vector3d.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite()

fun vanillaPlayerAbilities(gameMode: PlayerGameMode): PlayerAbilities =
    PlayerAbilities(
        invulnerable =
            gameMode == PlayerGameMode.CREATIVE ||
                    gameMode == PlayerGameMode.SPECTATOR,
        flying = gameMode == PlayerGameMode.SPECTATOR,
        canFly =
            gameMode == PlayerGameMode.CREATIVE ||
                    gameMode == PlayerGameMode.SPECTATOR,
        instantBuild = gameMode == PlayerGameMode.CREATIVE,
        flyingSpeed = DEFAULT_FLYING_SPEED,
        walkingSpeed = DEFAULT_WALKING_SPEED,
    )

private const val DEFAULT_FLYING_SPEED: Float = 0.05f
private const val DEFAULT_WALKING_SPEED: Float = 0.1f
private const val CHUNK_SIZE: Double = 16.0
