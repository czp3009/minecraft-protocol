package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.datapack.ProtocolData
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.world.format.DimensionId
import kotlin.random.Random
import kotlin.uuid.Uuid

/** Values consumed while serving Status or moving one connection from Handshake through its first Play Login. */
data class MinecraftServerNegotiationOptions(
    val protocolData: ProtocolData = VanillaProtocolData,
    val initialDimensionId: DimensionId = DimensionId.Overworld,
    val dimensionIds: Set<DimensionId> = setOf(initialDimensionId),
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
    /**
     * Requests the protocol-visible secure-chat claim. It is effective only in
     * Online authentication. Set it only when the consuming server actually
     * validates and enforces secure profiles and signed chat.
     */
    val enforcesSecureChat: Boolean = false,
) {
    init {
        require(initialDimensionId in dimensionIds) {
            "Initial dimension $initialDimensionId is absent from the advertised dimensions"
        }
    }
}
