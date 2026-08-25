package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.packet.UnknownPacket
import com.hiczp.minecraft.protocol.model.type.ClientInformation
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.JsonTextComponent
import com.hiczp.minecraft.protocol.model.type.KnownPack

/** Application decisions used only while one preset negotiation is running. */
interface MinecraftServerNegotiationPolicy {
    suspend fun statusJson(
        options: MinecraftServerNegotiationOptions,
        onlineMode: Boolean,
    ): String = options.statusJson(onlineMode = onlineMode)

    suspend fun profileRejection(
        gameProfile: GameProfile,
        transferred: Boolean,
        options: MinecraftServerNegotiationOptions,
    ): JsonTextComponent? = null

    suspend fun createPlayLoginPacket(
        gameProfile: GameProfile,
        clientInformation: ClientInformation,
        transferred: Boolean,
        onlineMode: Boolean,
        options: MinecraftServerNegotiationOptions,
    ): PlayLoginPacket = options.createPlayLoginPacket(gameProfile, onlineMode)

    suspend fun configurationPackets(
        gameProfile: GameProfile,
        clientInformation: ClientInformation,
        acceptedKnownPacks: List<KnownPack>,
        transferred: Boolean,
        options: MinecraftServerNegotiationOptions,
    ): List<ClientboundPacket> = emptyList()

    suspend fun configurationTasks(
        gameProfile: GameProfile,
        clientInformation: ClientInformation,
        acceptedKnownPacks: List<KnownPack>,
        transferred: Boolean,
        options: MinecraftServerNegotiationOptions,
    ): List<MinecraftServerNegotiationTask> = emptyList()

    suspend fun onUnhandledQuery(
        packet: UnknownPacket.Serverbound,
    ): ServerNegotiationQueryResult = ServerNegotiationQueryResult.Pass
}

data object DefaultMinecraftServerNegotiationPolicy :
    MinecraftServerNegotiationPolicy

sealed interface ServerNegotiationQueryResult {
    data object Pass : ServerNegotiationQueryResult

    data class Respond(
        val clientboundPackets: List<ClientboundPacket>,
    ) : ServerNegotiationQueryResult

    data class Reject(
        val reason: String,
    ) : ServerNegotiationQueryResult
}

class MinecraftServerNegotiationTask(
    clientboundPackets: List<ClientboundPacket>,
    private val completion: suspend (ServerboundPacket) -> Boolean,
) {
    val clientboundPackets: List<ClientboundPacket> = clientboundPackets.toList()

    suspend fun isComplete(packet: ServerboundPacket): Boolean =
        completion(packet)
}
