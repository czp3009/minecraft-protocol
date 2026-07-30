package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.ProtocolDataSet
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.random.Random

data class MinecraftServerConfiguration(
    val authentication: MinecraftServerAuthentication =
        MinecraftServerAuthentication.Offline,
    val protocolData: ProtocolDataSet = VanillaProtocolData,
    val compressionThreshold: Int? = 256,
    val sessionId: Uuid = Uuid(
        Random.Default.nextLong(),
        Random.Default.nextLong(),
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

    val effectiveSecureChatEnforcement: Boolean
        get() =
            enforcesSecureChat &&
                    authentication is MinecraftServerAuthentication.Online

    fun statusJson(onlinePlayers: Int = 0): String =
        """
        |{
        |  "version": {
        |    "name": "${escapeJson(protocolData.minecraftVersion)}",
        |    "protocol": ${protocolData.protocolVersion}
        |  },
        |  "players": {
        |    "max": $maximumPlayers,
        |    "online": $onlinePlayers
        |  },
        |  "description": {
        |    "text": "${escapeJson(statusDescription)}"
        |  },
        |  "enforcesSecureChat": $effectiveSecureChatEnforcement
        |}
        """.trimMargin()

    fun playLogin(profile: GameProfile): PlayLoginPacket {
        val dimension = Identifier("overworld")
        val dimensionLayout = MinecraftDimensionLayout.from(
            protocolData,
            dimension,
        )
        return PlayLoginPacket(
            playerId = profile.id.hashCode(),
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
            onlineMode = authentication is MinecraftServerAuthentication.Online,
            enforcesSecureChat = effectiveSecureChatEnforcement,
        )
    }

    companion object {
        const val MIN_VIEW_DISTANCE: Int = 2
        const val MAX_VIEW_DISTANCE: Int = 32
    }
}

internal fun escapeJson(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
        }
    }
}
