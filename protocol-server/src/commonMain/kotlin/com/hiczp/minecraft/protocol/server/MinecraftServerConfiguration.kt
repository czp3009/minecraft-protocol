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
    val maximumPlayers: Int = 20,
    val viewDistance: Int = 8,
    val simulationDistance: Int = 8,
    val statusDescription: String = "Kotlin Multiplatform Minecraft server",
    val maximumPacketsPerPhase: Int = 2_048,
) {
    init {
        require(compressionThreshold == null || compressionThreshold >= 0)
        require(maximumPlayers >= 0)
        require(viewDistance >= 0)
        require(simulationDistance >= 0)
        require(maximumPacketsPerPhase > 0)
        require(protocolData.protocolVersion == MinecraftProtocol.PROTOCOL_VERSION)
    }

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
        |  "enforcesSecureChat": false
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
            hardcore = false,
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
                gameMode = GameMode.CREATIVE,
                previousGameMode = null,
                isDebug = false,
                isFlat = true,
                lastDeathLocation = null,
                portalCooldown = 0,
                seaLevel = 63,
            ),
            onlineMode = authentication is MinecraftServerAuthentication.Online,
            enforcesSecureChat = false,
        )
    }
}

private fun escapeJson(value: String): String = buildString {
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
