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
        profile: GameProfile,
        transferred: Boolean,
        options: MinecraftServerNegotiationOptions,
    ): JsonTextComponent? = null

    suspend fun playLogin(
        profile: GameProfile,
        clientInformation: ClientInformation,
        transferred: Boolean,
        onlineMode: Boolean,
        options: MinecraftServerNegotiationOptions,
    ): PlayLoginPacket = options.playLogin(profile, onlineMode)

    suspend fun configurationPackets(
        profile: GameProfile,
        clientInformation: ClientInformation,
        acceptedKnownPacks: List<KnownPack>,
        transferred: Boolean,
        options: MinecraftServerNegotiationOptions,
    ): List<ClientboundPacket> = emptyList()

    suspend fun configurationTasks(
        profile: GameProfile,
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
        val packets: List<ClientboundPacket>,
    ) : ServerNegotiationQueryResult

    data class Reject(
        val reason: String,
    ) : ServerNegotiationQueryResult
}

class MinecraftServerNegotiationTask(
    val name: String,
    packets: List<ClientboundPacket>,
    private val completion: suspend (ServerboundPacket) -> Boolean,
) {
    val packets: List<ClientboundPacket> = packets.toList()

    init {
        require(name.isNotBlank()) {
            "Configuration task name must not be blank"
        }
    }

    suspend fun isComplete(packet: ServerboundPacket): Boolean =
        completion(packet)
}
