package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftEncryption
import com.hiczp.minecraft.protocol.auth.minecraftServerHash
import com.hiczp.minecraft.protocol.auth.offlineProfile
import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.completeRegistryPackets
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
        val transferred: Boolean = false,
    ) : MinecraftServerNegotiationResult
}

class MinecraftServerProtocol(
    val session: MinecraftSession,
    val configuration: MinecraftServerConfiguration =
        MinecraftServerConfiguration(),
    val handler: MinecraftServerHandler = DefaultMinecraftServerHandler,
    val clientIpAddress: String? = null,
) {
    internal var negotiatedPlayLogin: PlayLoginPacket? = null
        private set

    suspend fun negotiate(): MinecraftServerNegotiationResult {
        val handshake = requirePacket<HandshakePacket>(session.receive())
        return when (session.state) {
            com.hiczp.minecraft.protocol.model.packet.ConnectionState.STATUS ->
                if (configuration.statusEnabled) {
                    handleStatus()
                } else {
                    throw MinecraftServerException(
                        "Status requests are disabled by configuration",
                    )
                }

            com.hiczp.minecraft.protocol.model.packet.ConnectionState.LOGIN -> {
                val transferred =
                    handshake.nextState == HandshakeNextState.TRANSFER
                if (transferred && !configuration.acceptsTransfers) {
                    session.send(
                        LoginDisconnectPacket(
                            com.hiczp.minecraft.protocol.model.type
                                .JsonTextComponent(
                                    """{"translate":"multiplayer.disconnect.transfers_disabled"}""",
                                ),
                        ),
                    )
                    throw MinecraftServerException(
                        "Transfer connections are disabled by configuration",
                    )
                }
                if (
                    handshake.protocolVersion !=
                    configuration.protocolData.protocolVersion
                ) {
                    val message =
                        "Unsupported protocol version " +
                                "${handshake.protocolVersion}; expected " +
                                configuration.protocolData.protocolVersion
                    session.send(
                        LoginDisconnectPacket(
                            com.hiczp.minecraft.protocol.model.type
                                .JsonTextComponent(
                                    """{"text":"${escapeJson(message)}"}""",
                                ),
                        ),
                    )
                    throw MinecraftServerException(
                        message,
                    )
                }
                handleLogin(transferred)
            }

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

    private suspend fun handleLogin(
        transferred: Boolean,
    ): MinecraftServerNegotiationResult.PlayReady {
        val start = requirePacket<LoginStartPacket>(session.receive())
        val profile = authenticate(start)
        val rejection = handler.profileRejection(
            profile = profile,
            transferred = transferred,
            configuration = configuration,
        )
        if (rejection != null) {
            session.send(LoginDisconnectPacket(rejection))
            throw MinecraftServerException(
                "Profile ${profile.name} was rejected: ${rejection.json}",
            )
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
        val extensionPackets = handler.configurationPackets(
            profile = profile,
            clientInformation = clientInformation,
            acceptedKnownPacks = acceptedKnownPacks,
            transferred = transferred,
            configuration = configuration,
        )
        val extensionTasks = handler.configurationTasks(
            profile = profile,
            clientInformation = clientInformation,
            acceptedKnownPacks = acceptedKnownPacks,
            transferred = transferred,
            configuration = configuration,
        )
        (
                extensionPackets +
                        extensionTasks.flatMap(
                            MinecraftServerConfigurationTask::packets,
                        )
                ).forEach(::validateConfigurationExtensionPacket)
        extensionPackets.forEach { session.send(it) }
        extensionTasks.forEach { task ->
            task.packets.forEach { session.send(it) }
            awaitConfigurationTask(task)
        }
        session.send(FinishConfigurationPacket)
        awaitFinishConfigurationAcknowledgement()

        val playLogin = handler.playLogin(
            profile,
            clientInformation,
            transferred,
            configuration,
        )
        validatePlayLogin(playLogin)
        session.send(playLogin)
        negotiatedPlayLogin = playLogin
        return MinecraftServerNegotiationResult.PlayReady(
            profile = profile,
            clientInformation = clientInformation,
            acceptedKnownPacks = acceptedKnownPacks,
            login = playLogin,
            transferred = transferred,
        )
    }

    internal fun validatePlayLogin(login: PlayLoginPacket) {
        if (login.maxPlayers < 0) {
            throw MinecraftServerException(
                "Play Login maximum players must be non-negative",
            )
        }
        if (
            login.chunkRadius !in
            MinecraftServerConfiguration.MIN_VIEW_DISTANCE..
            MinecraftServerConfiguration.MAX_VIEW_DISTANCE
        ) {
            throw MinecraftServerException(
                "Play Login chunk radius must be in " +
                        "${MinecraftServerConfiguration.MIN_VIEW_DISTANCE}.." +
                        MinecraftServerConfiguration.MAX_VIEW_DISTANCE,
            )
        }
        if (login.simulationDistance < 0) {
            throw MinecraftServerException(
                "Play Login simulation distance must be non-negative",
            )
        }
        if (login.spawnInfo.dimension !in login.levels) {
            throw MinecraftServerException(
                "Play Login dimension is absent from its advertised levels",
            )
        }
        try {
            MinecraftDimensionLayout.from(
                configuration.protocolData.completeRegistryPackets(),
                login.spawnInfo.dimensionTypeId,
            )
        } catch (cause: IllegalStateException) {
            throw MinecraftServerException(
                "Play Login references an absent dimension-type registry ID",
                cause,
            )
        } catch (cause: IllegalArgumentException) {
            throw MinecraftServerException(
                "Play Login references invalid dimension-type registry data",
                cause,
            )
        }
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
                    ipAddress =
                        if (configuration.preventProxyConnections) {
                            clientIpAddress ?: throw MinecraftServerException(
                                "Proxy prevention requires the client IP address",
                            )
                        } else {
                            null
                        },
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

    private suspend fun awaitFinishConfigurationAcknowledgement() {
        repeat(configuration.maximumPacketsPerPhase) {
            when (val packet = session.receive()) {
                AcknowledgeFinishConfigurationPacket -> return
                else -> handler.onPacket(packet)
            }
        }
        throw MinecraftServerException(
            "Finish Configuration acknowledgement packet limit exceeded",
        )
    }

    private suspend fun awaitConfigurationTask(
        task: MinecraftServerConfigurationTask,
    ) {
        repeat(configuration.maximumPacketsPerPhase) {
            val packet = session.receive()
            handler.onPacket(packet)
            if (task.isComplete(packet)) return
        }
        throw MinecraftServerException(
            "Configuration task '${task.name}' packet limit exceeded",
        )
    }

    private fun validateConfigurationExtensionPacket(packet: Packet) {
        if (
            packet !is ConfigurationStatePacket ||
            packet !is ClientboundPacket
        ) {
            throw MinecraftServerException(
                "Configuration extension ${packet::class.simpleName} must be " +
                        "a clientbound Configuration packet",
            )
        }
        if (
            packet is FeatureFlagsPacket ||
            packet is ConfigurationClientboundKnownPacksPacket ||
            packet is RegistryDataPacket ||
            packet is ConfigurationUpdateTagsPacket ||
            packet is FinishConfigurationPacket
        ) {
            throw MinecraftServerException(
                "Configuration extension ${packet::class.simpleName} is " +
                        "managed by MinecraftServerProtocol",
            )
        }
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
