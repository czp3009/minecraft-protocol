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
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        onlineMode: Boolean,
    ): String = minecraftServerNegotiationOptions.statusJson(onlineMode = onlineMode)

    suspend fun profileRejection(
        gameProfile: GameProfile,
        transferred: Boolean,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): JsonTextComponent? = null

    suspend fun createPlayLoginPacket(
        gameProfile: GameProfile,
        clientInformation: ClientInformation,
        transferred: Boolean,
        onlineMode: Boolean,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ): PlayLoginPacket = minecraftServerNegotiationOptions.createPlayLoginPacket(gameProfile, onlineMode)

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

    suspend fun isComplete(serverboundPacket: ServerboundPacket): Boolean =
        completion(serverboundPacket)
}
