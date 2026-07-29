package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftEncryption
import com.hiczp.minecraft.protocol.auth.minecraftServerHash
import com.hiczp.minecraft.protocol.auth.offlineProfile
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ClientInformation
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.session.MinecraftSession

sealed interface MinecraftServerNegotiationResult {
    data object StatusCompleted : MinecraftServerNegotiationResult

    data class PlayReady(
        val profile: GameProfile,
        val clientInformation: ClientInformation,
        val acceptedKnownPacks: List<KnownPack>,
        val login: com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket,
    ) : MinecraftServerNegotiationResult
}

class MinecraftServerProtocol(
    val session: MinecraftSession,
    val configuration: MinecraftServerConfiguration =
        MinecraftServerConfiguration(),
    val handler: MinecraftServerHandler = DefaultMinecraftServerHandler,
) {
    suspend fun negotiate(): MinecraftServerNegotiationResult {
        val handshake = requirePacket<HandshakePacket>(session.receive())
        return when (session.state) {
            com.hiczp.minecraft.protocol.model.packet.ConnectionState.STATUS ->
                handleStatus()

            com.hiczp.minecraft.protocol.model.packet.ConnectionState.LOGIN ->
                handleLogin()

            else -> throw MinecraftServerException(
                "Handshake ${handshake.nextState} entered unsupported state ${session.state}",
            )
        }
    }

    private suspend fun handleStatus(): MinecraftServerNegotiationResult {
        requirePacket<StatusRequestPacket>(session.receive())
        session.send(StatusResponsePacket(handler.statusJson(configuration)))
        val ping = requirePacket<StatusPingRequestPacket>(session.receive())
        session.send(StatusPongResponsePacket(ping.timestamp))
        return MinecraftServerNegotiationResult.StatusCompleted
    }

    private suspend fun handleLogin(): MinecraftServerNegotiationResult.PlayReady {
        val start = requirePacket<LoginStartPacket>(session.receive())
        val profile = authenticate(start)
        if (!handler.acceptProfile(profile)) {
            throw MinecraftServerException("Profile ${profile.name} was rejected")
        }
        configuration.compressionThreshold?.let { threshold ->
            session.send(SetCompressionPacket(threshold))
        }
        session.send(LoginSuccessPacket(profile, configuration.sessionId))
        requirePacket<LoginAcknowledgedPacket>(session.receive())

        val clientInformation = awaitClientInformation()
        session.send(configuration.protocolData.featureFlags)
        session.send(
            com.hiczp.minecraft.protocol.model.packet.ConfigurationClientboundKnownPacksPacket(
                configuration.protocolData.knownPacks,
            ),
        )
        val acceptedKnownPacks = awaitKnownPacks()
        configuration.protocolData.registryPackets(acceptedKnownPacks)
            .forEach { session.send(it) }
        session.send(configuration.protocolData.tags)
        session.send(FinishConfigurationPacket)
        requirePacket<AcknowledgeFinishConfigurationPacket>(session.receive())

        val playLogin = handler.playLogin(
            profile,
            clientInformation,
            configuration,
        )
        session.send(playLogin)
        return MinecraftServerNegotiationResult.PlayReady(
            profile = profile,
            clientInformation = clientInformation,
            acceptedKnownPacks = acceptedKnownPacks,
            login = playLogin,
        )
    }

    private suspend fun authenticate(start: LoginStartPacket): GameProfile =
        when (val authentication = configuration.authentication) {
            MinecraftServerAuthentication.Offline ->
                offlineProfile(start.name)

            is MinecraftServerAuthentication.Online -> {
                val challenge = MinecraftEncryption.createServerChallenge(
                    cryptography = authentication.cryptography,
                    keyPair = authentication.keyPair,
                    shouldAuthenticate = true,
                )
                session.send(challenge.request)
                val response = requirePacket<EncryptionResponsePacket>(
                    session.receive(),
                )
                val sharedSecret = MinecraftEncryption.acceptClientResponse(
                    challenge,
                    response,
                    authentication.cryptography,
                )
                session.enableEncryption(sharedSecret)
                val joined = authentication.sessionService.hasJoined(
                    username = start.name,
                    serverHash = minecraftServerHash(
                        serverId = challenge.request.serverId,
                        sharedSecret = sharedSecret,
                        encodedPublicKey = challenge.request.publicKey.toByteArray(),
                    ),
                ) ?: throw MinecraftServerException(
                    "Session server did not verify ${start.name}",
                )
                joined.profile
            }
        }

    private suspend fun awaitClientInformation(): ClientInformation {
        repeat(configuration.maximumPacketsPerPhase) {
            when (val packet = session.receive()) {
                is ConfigurationClientInformationPacket ->
                    return packet.information

                else -> handler.onPacket(packet)
            }
        }
        throw MinecraftServerException("Client Information packet limit exceeded")
    }

    private suspend fun awaitKnownPacks(): List<KnownPack> {
        repeat(configuration.maximumPacketsPerPhase) {
            when (val packet = session.receive()) {
                is ConfigurationServerboundKnownPacksPacket ->
                    return packet.knownPacks

                else -> handler.onPacket(packet)
            }
        }
        throw MinecraftServerException("Known Packs packet limit exceeded")
    }

    private inline fun <reified T : Packet> requirePacket(packet: Packet): T =
        packet as? T ?: throw MinecraftServerException(
            "Expected ${T::class.simpleName}, received ${packet::class.simpleName}",
        )
}

class MinecraftServerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
