package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionContext
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.BlockPosition
import com.hiczp.minecraft.protocol.world.ChunkPacketEncoder
import com.hiczp.minecraft.protocol.world.ChunkPacketEncoderContext
import com.hiczp.minecraft.protocol.world.ChunkPacketRequiredDataProvider
import com.hiczp.minecraft.protocol.world.ChunkPacketWriteMappings
import com.hiczp.minecraft.world.format.*

/** Game choices belong to this simulated application, including terrain, abilities, spawn and update tags. */
internal fun testPlayerAbilities(gameMode: GameMode): PlayerAbilities = PlayerAbilities(
    invulnerable = gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR,
    flying = gameMode == GameMode.SPECTATOR,
    canFly = gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR,
    instantBuild = gameMode == GameMode.CREATIVE,
    flyingSpeed = 0.05f,
    walkingSpeed = 0.1f,
)

internal fun testWorldBootstrap(
    dimensionId: Identifier = Identifier("overworld"),
    difficulty: Difficulty = Difficulty.EASY,
    difficultyLocked: Boolean = false,
    gameMode: GameMode = GameMode.SURVIVAL,
    viewDistance: Int = 10,
    simulationDistance: Int = 10,
    defaultSpawnPosition: Vector3d = Vector3d(0.5, 65.0, 0.5),
    defaultSpawnYaw: Float = 0f,
    defaultSpawnPitch: Float = 0f,
    playerPosition: Vector3d = defaultSpawnPosition,
    playerYaw: Float = defaultSpawnYaw,
    playerPitch: Float = defaultSpawnPitch,
    centerChunk: ChunkPosition = MinecraftCoordinates.block(
        playerPosition.x,
        playerPosition.y,
        playerPosition.z
    ).chunkPosition,
): MinecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap(
    difficulty = difficulty,
    difficultyLocked = difficultyLocked,
    defaultSpawn = RespawnData(
        GlobalPosition(
            dimensionId, BlockPosition(
                MinecraftCoordinates.blockCoordinate(defaultSpawnPosition.x),
                MinecraftCoordinates.blockCoordinate(defaultSpawnPosition.y),
                MinecraftCoordinates.blockCoordinate(defaultSpawnPosition.z),
            )
        ),
        defaultSpawnYaw, defaultSpawnPitch,
    ),
    playerAbilities = testPlayerAbilities(gameMode),
    viewDistance = viewDistance,
    simulationDistance = simulationDistance,
    playerPosition = PositionMoveRotation(playerPosition, Vector3d(0.0, 0.0, 0.0), playerYaw, playerPitch),
    centerChunk = centerChunk,
)

internal fun testWorldBootstrap(
    minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
): MinecraftInitialWorldBootstrap = with(minecraftServerNegotiationResult.clientboundLoginPacket) {
    testWorldBootstrap(
        dimensionId = commonPlayerSpawnInfo.dimension,
        gameMode = commonPlayerSpawnInfo.gameMode,
        viewDistance = chunkRadius,
        simulationDistance = simulationDistance,
    )
}

internal fun testInitialWorld(
    minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
    entityBatches: List<MinecraftEntityBatch> = emptyList(),
): MinecraftInitialWorld = testInitialWorld(
    minecraftDimensionContext = minecraftServerNegotiationResult.minecraftDimensionContext,
    minecraftInitialWorldBootstrap = testWorldBootstrap(minecraftServerNegotiationResult),
    entityBatches = entityBatches,
)

internal fun testInitialWorld(
    minecraftDimensionContext: MinecraftDimensionContext = MinecraftDimensionContext(
        DimensionId.Overworld,
        MinecraftDimensionLayout.from(VanillaConfigurationData, Identifier("overworld")),
        VanillaConfigurationData.completePacketCodecContext,
    ),
    groundY: Int = 64,
    minecraftInitialWorldBootstrap: MinecraftInitialWorldBootstrap = testWorldBootstrap(
        dimensionId = Identifier(minecraftDimensionContext.dimensionId.toString()),
        defaultSpawnPosition = Vector3d(0.5, groundY + 1.0, 0.5),
    ),
    chunkRadius: Int = minecraftInitialWorldBootstrap.viewDistance,
    surfaceBlockId: Identifier = Identifier("grass_block"),
    biomeId: Identifier = MinecraftBiomeIds.PLAINS,
    entityBatches: List<MinecraftEntityBatch> = emptyList(),
): MinecraftInitialWorld {
    val packetCodecContext = minecraftDimensionContext.packetCodecContext
    val air = packetCodecContext.requireDefaultBlockState(MinecraftBlockIds.AIR)
    val surface = packetCodecContext.requireDefaultBlockState(surfaceBlockId)
    val chunkContext = minecraftDimensionContext.chunkContext(
        BlockState(BlockId(air.block.value), StateProperties(air.properties)), BiomeId(biomeId.value),
    )
    val surfaceBlockState = BlockState(BlockId(surface.block.value), StateProperties(surface.properties))
    val chunks = MinecraftCoordinates.chunkPositionsAround(minecraftInitialWorldBootstrap.centerChunk, chunkRadius)
        .map { createFlatChunk(it, chunkContext, groundY, surfaceBlockState) }.toList()
    val chunkPacketEncoder = ChunkPacketEncoder(
        ChunkPacketEncoderContext(
            chunkContext.dimensionTypeLayout.chunkLayout, chunkContext.dimensionTypeLayout.hasSkyLight,
            chunkContext.defaultBlockState, chunkContext.defaultBiome, packetCodecContext,
            ChunkPacketWriteMappings({ null }), ChunkPacketRequiredDataProvider.RequirePresent,
        )
    )
    return MinecraftInitialWorld(minecraftInitialWorldBootstrap, chunks, chunkPacketEncoder, entityBatches)
}
