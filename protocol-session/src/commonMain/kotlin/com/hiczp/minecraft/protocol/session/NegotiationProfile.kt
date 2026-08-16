package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.HandshakePacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext

/** Marker returned by optional negotiation algorithms. */
interface NegotiationProfileResult

data object VanillaNegotiationProfileResult : NegotiationProfileResult

/**
 * Loader-neutral client algorithm hooks. Implementations only receive the same
 * public connection contract available to an application-written negotiator.
 */
interface ClientNegotiationProfile {
    suspend fun begin(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ) = Unit

    /** Returns the Handshake packet to put on the ordinary outgoing channel. */
    fun prepareHandshake(packet: HandshakePacket): HandshakePacket = packet

    suspend fun handleLoginPacket(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
        packet: ClientboundPacket,
    ): Boolean = false

    suspend fun handleConfigurationPacket(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
        packet: ClientboundPacket,
    ): Boolean = false

    suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext = context

    /** Runs immediately before the client acknowledges Finish Configuration. */
    suspend fun preparePlay(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ) = Unit

    suspend fun complete(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ): NegotiationProfileResult = VanillaNegotiationProfileResult
}

/**
 * Loader-neutral server algorithm hooks. A profile may run arbitrary bounded
 * Login and Configuration exchanges through the public packet channels.
 */
interface ServerNegotiationProfile {
    suspend fun begin(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) = Unit

    /** Observes the already-decoded Handshake packet before Login orchestration. */
    fun acceptHandshake(packet: HandshakePacket) = Unit

    suspend fun negotiateLogin(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) = Unit

    suspend fun handleLoginPacket(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
        packet: ServerboundPacket,
    ): Boolean = false

    /** Runs after Client Information and before vanilla Feature Flags. */
    suspend fun negotiateConfigurationStart(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) = Unit

    /** Runs after vanilla Feature Flags and before Known Packs/registry/tag sync. */
    suspend fun negotiateEarlyConfiguration(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) = Unit

    /** Runs after vanilla registry/tag sync and before caller configuration tasks. */
    suspend fun negotiateConfiguration(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) = Unit

    suspend fun handleConfigurationPacket(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
        packet: ServerboundPacket,
    ): Boolean = false

    suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext = context

    /** Runs after the server observes Play state and before it sends Play Login. */
    suspend fun preparePlay(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) = Unit

    suspend fun complete(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ): NegotiationProfileResult = VanillaNegotiationProfileResult
}

/** Vanilla adds no loader-specific client packet exchanges. */
data object VanillaClient : ClientNegotiationProfile

/** Vanilla adds no loader-specific server packet exchanges. */
data object VanillaServer : ServerNegotiationProfile
