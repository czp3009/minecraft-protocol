package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.data.*
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
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
                    MinecraftServerConfiguration.MIN_VIEW_DISTANCE..
                    MinecraftServerConfiguration.MAX_VIEW_DISTANCE,
        ) {
            "View distance must be in ${MinecraftServerConfiguration.MIN_VIEW_DISTANCE}..${MinecraftServerConfiguration.MAX_VIEW_DISTANCE}"
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
            configuration: MinecraftServerConfiguration,
            dimension: Identifier = Identifier("overworld"),
            groundY: Int = 64,
            spawnPosition: Vector3d =
                Vector3d(0.5, groundY + 1.0, 0.5),
            chunkRadius: Int = configuration.viewDistance,
            surfaceBlock: Identifier = Identifier("grass_block"),
            biome: Identifier = Identifier("plains"),
            entities: List<MinecraftEntitySnapshot> = emptyList(),
        ): MinecraftInitialWorld {
            require(chunkRadius >= 0)
            val dimensionType = MinecraftDimensionLayout.from(
                configuration.protocolData,
                dimension,
            )
            val surfaceBlockStateId = VanillaStaticData.blockStates
                .default(surfaceBlock)
                .id
            val biomeId = configuration.protocolData.registryId(
                BIOME_REGISTRY,
                biome,
            ) ?: error("$biome is absent from $BIOME_REGISTRY")
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
                                dimension = dimensionType,
                                chunkX = chunkX,
                                chunkZ = chunkZ,
                                groundY = groundY,
                                surfaceBlockStateId = surfaceBlockStateId,
                                biomeId = biomeId,
                            ),
                        )
                    }
                }
            }
            return MinecraftInitialWorld(
                dimension = dimension,
                dimensionType = dimensionType,
                spawnPosition = spawnPosition,
                viewDistance = configuration.viewDistance,
                simulationDistance = configuration.simulationDistance,
                difficulty = configuration.difficulty,
                difficultyLocked = configuration.difficultyLocked,
                playerAbilities = vanillaPlayerAbilities(configuration.gameMode),
                chunks = chunks,
                entities = entities,
            )
        }

        private val BIOME_REGISTRY = Identifier("worldgen/biome")
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
    require(session.state == ConnectionState.PLAY) {
        "Initial world synchronization requires a Play session"
    }
    validateInitialWorld(
        world = world,
        login = checkNotNull(protocol.negotiatedPlayLogin) {
            "Initial world synchronization requires a completed Play login"
        },
        configuration = protocol.configuration,
    )
    val biomeRegistrySize = protocol.configuration.protocolData
        .requireRegistry(Identifier("worldgen/biome"))
        .entries
        .size
    session.format = MinecraftProtocolFormat(
        configuration = session.format.configuration.copy(
            chunkSectionCount = world.dimensionType.sectionCount,
            blockStateRegistrySize = VanillaStaticData.blockStates.size,
            biomeRegistrySize = biomeRegistrySize,
        ),
        serializersModule = session.format.serializersModule,
    )

    session.send(
        ClientboundChangeDifficultyPacket(
            difficulty = world.difficulty,
            locked = world.difficultyLocked,
        ),
    )
    session.send(
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
    session.send(
        ClientboundPlayerAbilitiesPacket(
            world.playerAbilities,
        ),
    )
    session.send(SetRenderDistancePacket(world.viewDistance))
    session.send(SetSimulationDistancePacket(world.simulationDistance))
    session.send(
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
    session.send(
        GameEventPacket(
            event = GameEventType.LEVEL_CHUNKS_LOAD_START,
            value = 0.0f,
        ),
    )
    session.send(
        SetCenterChunkPacket(
            chunkX = floor(world.spawnPosition.x / CHUNK_SIZE).toInt(),
            chunkZ = floor(world.spawnPosition.z / CHUNK_SIZE).toInt(),
        ),
    )
    session.send(ChunkBatchStartPacket)
    world.chunks.forEach { session.send(it.packet()) }
    session.send(ChunkBatchFinishedPacket(world.chunks.size))
    world.entities.forEach { entity ->
        entity.packets().forEach { session.send(it) }
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
    configuration: MinecraftServerConfiguration,
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
    val expectedDimension = MinecraftDimensionLayout.from(
        configuration.protocolData.completeRegistryPackets(),
        login.spawnInfo.dimensionTypeId,
    )
    require(world.dimensionType == expectedDimension) {
        "Initial dimension type does not match synchronized registry data"
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
