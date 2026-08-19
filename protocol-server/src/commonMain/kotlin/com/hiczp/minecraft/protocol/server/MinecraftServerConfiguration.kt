package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.ProtocolDataSet
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.encodeToSink
import kotlin.random.Random
import kotlin.uuid.Uuid

data class MinecraftServerNegotiationOptions(
    val protocolData: ProtocolDataSet = VanillaProtocolData,
    val compressionThreshold: Int? = 256,
    val sessionId: Uuid = Uuid.fromLongs(
        Random.nextLong(),
        Random.nextLong(),
    ),
    val statusEnabled: Boolean = true,
    val acceptsTransfers: Boolean = false,
    val preventProxyConnections: Boolean = false,
    val maximumPlayers: Int = 20,
    val viewDistance: Int = 10,
    val simulationDistance: Int = 10,
    val statusDescription: String = "A Minecraft Server",
    val hardcore: Boolean = false,
    val gameMode: GameMode = GameMode.SURVIVAL,
    val difficulty: Difficulty = Difficulty.EASY,
    val difficultyLocked: Boolean = false,
    /**
     * Requests the protocol-visible secure-chat claim. It is effective only in
     * Online authentication. Set it only when the consuming server actually
     * validates and enforces secure profiles and signed chat.
     */
    val enforcesSecureChat: Boolean = false,
    val maximumPacketsPerPhase: Int = 2_048,
) {
    init {
        require(compressionThreshold == null || compressionThreshold >= 0)
        require(maximumPlayers >= 0)
        require(viewDistance in MIN_VIEW_DISTANCE..MAX_VIEW_DISTANCE) {
            "View distance must be in $MIN_VIEW_DISTANCE..$MAX_VIEW_DISTANCE"
        }
        require(simulationDistance >= 0)
        require(maximumPacketsPerPhase > 0)
        require(protocolData.protocolVersion == MinecraftProtocol.PROTOCOL_VERSION)
    }

    fun effectiveSecureChatEnforcement(onlineMode: Boolean): Boolean =
        onlineMode && enforcesSecureChat

    /** In-memory adapter over [statusJsonToSink]. */
    fun statusJson(
        onlinePlayers: Int = 0,
        onlineMode: Boolean = false,
    ): String {
        val sink = Buffer()
        statusJsonToSink(sink, onlinePlayers, onlineMode)
        return sink.readString()
    }

    /**
     * Serializes the status response directly into [sink] without closing or
     * flushing the caller-owned endpoint.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun statusJsonToSink(
        sink: Sink,
        onlinePlayers: Int = 0,
        onlineMode: Boolean = false,
    ) {
        require(onlinePlayers >= 0) { "Online player count cannot be negative" }
        serverJson.encodeToSink(
            ServerStatus.serializer(),
            ServerStatus(
                version = ServerStatusVersion(
                    protocolData.minecraftVersion,
                    protocolData.protocolVersion,
                ),
                players = ServerStatusPlayers(
                    maximumPlayers,
                    onlinePlayers,
                ),
                description = ServerStatusDescription(statusDescription),
                enforcesSecureChat = effectiveSecureChatEnforcement(onlineMode),
            ),
            sink,
        )
    }

    fun playLogin(
        gameProfile: GameProfile,
        onlineMode: Boolean,
    ): PlayLoginPacket {
        val dimension = Identifier("overworld")
        val dimensionLayout = MinecraftDimensionLayout.from(
            protocolData,
            dimension,
        )
        return PlayLoginPacket(
            playerId = gameProfile.id.hashCode(),
            hardcore = hardcore,
            levels = setOf(dimension),
            maxPlayers = maximumPlayers,
            chunkRadius = viewDistance,
            simulationDistance = simulationDistance,
            reducedDebugInfo = false,
            showDeathScreen = true,
            limitedCrafting = false,
            spawnInfo = CommonPlayerSpawnInfo(
                dimensionTypeId = dimensionLayout.registryId,
                dimension = dimension,
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

    /**
     * Validates the server-policy fields of [login] without reading connection
     * state or performing I/O. Registry and active-dimension consistency is
     * validated by `withPlayLoginDimension` in `protocol-vanilla-data`.
     */
    fun validatePlayLogin(login: PlayLoginPacket) {
        require(login.maxPlayers >= 0) {
            "Play Login maximum players must be non-negative"
        }
        val chunkRadiusRange = MIN_VIEW_DISTANCE..MAX_VIEW_DISTANCE
        require(login.chunkRadius in chunkRadiusRange) {
            "Play Login chunk radius must be in $chunkRadiusRange"
        }
        require(login.simulationDistance >= 0) {
            "Play Login simulation distance must be non-negative"
        }
    }

    companion object {
        const val MIN_VIEW_DISTANCE: Int = 2
        const val MAX_VIEW_DISTANCE: Int = 32
    }
}

@Serializable
private data class ServerStatus(
    val version: ServerStatusVersion,
    val players: ServerStatusPlayers,
    val description: ServerStatusDescription,
    val enforcesSecureChat: Boolean,
)

@Serializable
private data class ServerStatusVersion(
    val name: String,
    val protocol: Int,
)

@Serializable
private data class ServerStatusPlayers(
    val max: Int,
    val online: Int,
)

@Serializable
private data class ServerStatusDescription(val text: String)

private val serverJson = Json { prettyPrint = true }
