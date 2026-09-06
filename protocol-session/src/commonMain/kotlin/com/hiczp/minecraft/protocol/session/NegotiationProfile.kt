package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.ClientIntentionPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext

/** Marker returned by optional negotiation algorithms. */
interface NegotiationProfileResult

data object VanillaNegotiationProfileResult : NegotiationProfileResult

/**
 * Loader-neutral client algorithm hooks. Implementations only receive the same
 * public connection contract exposed by the session layer.
 */
interface ClientNegotiationProfile {
    suspend fun begin(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) = Unit

    /** Returns the Handshake packet to put on the ordinary outgoing channel. */
    fun prepareHandshake(clientIntentionPacket: ClientIntentionPacket): ClientIntentionPacket = clientIntentionPacket

    suspend fun handleLoginPacket(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        clientboundPacket: ClientboundPacket,
    ): Boolean = false

    suspend fun handleConfigurationPacket(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        clientboundPacket: ClientboundPacket,
    ): Boolean = false

    suspend fun resolvePacketCodecContext(
        packetCodecContext: PacketCodecContext,
    ): PacketCodecContext = packetCodecContext

    /** Runs immediately before the client acknowledges Finish Configuration. */
    suspend fun preparePlay(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) = Unit

    suspend fun complete(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ): NegotiationProfileResult = VanillaNegotiationProfileResult
}

/**
 * Loader-neutral server algorithm hooks. A profile may run arbitrary bounded
 * Login and Configuration exchanges through the public packet channels.
 */
interface ServerNegotiationProfile {
    suspend fun begin(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) = Unit

    /** Observes the already-decoded Handshake packet before Login orchestration. */
    fun acceptHandshake(clientIntentionPacket: ClientIntentionPacket) = Unit

    suspend fun negotiateLogin(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) = Unit

    suspend fun handleLoginPacket(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
        serverboundPacket: ServerboundPacket,
    ): Boolean = false

    /** Runs after Client Information and before vanilla Feature Flags. */
    suspend fun negotiateConfigurationStart(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) = Unit

    /** Runs after vanilla Feature Flags and before Known Packs/registry/tag sync. */
    suspend fun negotiateEarlyConfiguration(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) = Unit

    /** Runs after vanilla registry/tag sync and before caller configuration tasks. */
    suspend fun negotiateConfiguration(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) = Unit

    suspend fun handleConfigurationPacket(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
        serverboundPacket: ServerboundPacket,
    ): Boolean = false

    suspend fun resolvePacketCodecContext(
        packetCodecContext: PacketCodecContext,
    ): PacketCodecContext = packetCodecContext

    /** Runs after the server observes Play state and before it sends Play Login. */
    suspend fun preparePlay(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) = Unit

    suspend fun complete(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult = VanillaNegotiationProfileResult
}

/** Vanilla adds no loader-specific client packet exchanges. */
data object VanillaClient : ClientNegotiationProfile

/** Vanilla adds no loader-specific server packet exchanges. */
data object VanillaServer : ServerNegotiationProfile
