package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.ClientboundLoginPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.packet.UnknownPacket
import com.hiczp.minecraft.protocol.model.type.*

/** Application decisions used only while one preset negotiation is running. */
interface MinecraftServerNegotiationPolicy {
    suspend fun onlinePlayerCount(
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): Int = 0

    suspend fun serverStatus(
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        onlineMode: Boolean,
    ): ServerStatus = DefaultMinecraftServerNegotiationPolicy.createServerStatus(
        minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
        onlinePlayers = onlinePlayerCount(minecraftServerNegotiationOptions),
        onlineMode = onlineMode,
    )

    suspend fun profileRejection(
        gameProfile: GameProfile,
        transferred: Boolean,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): JsonTextComponent? = null

    suspend fun createClientboundLoginPacket(
        gameProfile: GameProfile,
        clientInformation: ClientInformation,
        transferred: Boolean,
        onlineMode: Boolean,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): ClientboundLoginPacket = DefaultMinecraftServerNegotiationPolicy.createClientboundLoginPacket(
        minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
        gameProfile = gameProfile,
        onlineMode = onlineMode,
    )

    suspend fun configurationPackets(
        gameProfile: GameProfile,
        clientInformation: ClientInformation,
        acceptedKnownPacks: List<KnownPack>,
        transferred: Boolean,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): List<ClientboundPacket> = emptyList()

    suspend fun configurationTasks(
        gameProfile: GameProfile,
        clientInformation: ClientInformation,
        acceptedKnownPacks: List<KnownPack>,
        transferred: Boolean,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): List<MinecraftServerNegotiationTask> = emptyList()

    suspend fun onUnhandledQuery(
        packet: UnknownPacket.Serverbound,
    ): ServerNegotiationQueryResult = ServerNegotiationQueryResult.Pass
}

data object DefaultMinecraftServerNegotiationPolicy : MinecraftServerNegotiationPolicy {
    /** Builds the default Status response with the policy's current online-player count. */
    fun createServerStatus(
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        onlinePlayers: Int = 0,
        onlineMode: Boolean = false,
    ): ServerStatus = minecraftServerNegotiationOptions.createDefaultServerStatus(onlinePlayers, onlineMode)

    /** Builds the default Play Login selected by [minecraftServerNegotiationOptions]. */
    fun createClientboundLoginPacket(
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        gameProfile: GameProfile,
        onlineMode: Boolean,
    ): ClientboundLoginPacket =
        minecraftServerNegotiationOptions.createDefaultClientboundLoginPacket(gameProfile, onlineMode)
}

sealed interface ServerNegotiationQueryResult {
    data object Pass : ServerNegotiationQueryResult

    data class Respond(
        val clientboundPackets: List<ClientboundPacket>,
    ) : ServerNegotiationQueryResult

    data class Reject(
        val reason: String,
    ) : ServerNegotiationQueryResult
}

/** One ordered Configuration exchange; progress packets can be consumed without completing the task. */
data class MinecraftServerNegotiationTask(
    val clientboundPackets: List<ClientboundPacket>,
    val handlePacket: suspend (ServerboundPacket) -> ServerNegotiationTaskResult,
)

/** Whether an ordered task recognized a packet and whether it can advance to the next task. */
enum class ServerNegotiationTaskResult {
    /** Let the negotiation profile or unhandled-packet policy process this packet. */
    PASS,

    /** This task consumed the packet and still awaits further responses. */
    CONTINUE,

    /** This task consumed the packet and finished. */
    COMPLETE,
}

private fun MinecraftServerNegotiationOptions.createDefaultServerStatus(
    onlinePlayers: Int = 0,
    onlineMode: Boolean = false,
): ServerStatus =
    ServerStatus(
        description = JsonTextComponent.literal(statusDescription),
        players = ServerStatus.Players(
            maximumPlayers,
            online = onlinePlayers,
        ),
        version = ServerStatus.Version(
            MinecraftProtocol.MINECRAFT_VERSION,
            MinecraftProtocol.PROTOCOL_VERSION,
        ),
        enforcesSecureChat = effectiveSecureChatEnforcement(onlineMode),
    )

private fun MinecraftServerNegotiationOptions.createDefaultClientboundLoginPacket(
    gameProfile: GameProfile,
    onlineMode: Boolean,
): ClientboundLoginPacket {
    val dimensionId = Identifier.parse(initialDimensionId.toString())
    val minecraftDimensionLayout = MinecraftDimensionLayout.from(
        configurationData,
        initialDimensionTypeId,
    )
    return ClientboundLoginPacket(
        playerId = gameProfile.id.hashCode(),
        hardcore = hardcore,
        levels = dimensionIds.mapTo(linkedSetOf()) { advertisedDimensionId ->
            Identifier.parse(advertisedDimensionId.toString())
        },
        maxPlayers = maximumPlayers,
        chunkRadius = viewDistance,
        simulationDistance = simulationDistance,
        reducedDebugInfo = false,
        showDeathScreen = true,
        doLimitedCrafting = false,
        commonPlayerSpawnInfo = CommonPlayerSpawnInfo(
            dimensionTypeId = minecraftDimensionLayout.dimensionTypeRawId,
            dimension = dimensionId,
            seed = 0,
            gameMode = gameMode,
            previousGameMode = null,
            isDebug = false,
            isFlat = true,
            lastDeathLocation = null,
            portalCooldown = 0,
            seaLevel = 63,
        ),
        onlineMode = onlineMode,
        enforcesSecureChat = effectiveSecureChatEnforcement(onlineMode),
    )
}

private fun MinecraftServerNegotiationOptions.effectiveSecureChatEnforcement(onlineMode: Boolean): Boolean =
    onlineMode && enforcesSecureChat
