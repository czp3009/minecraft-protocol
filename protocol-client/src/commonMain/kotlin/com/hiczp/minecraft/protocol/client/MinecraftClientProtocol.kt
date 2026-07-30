package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftEncryption
import com.hiczp.minecraft.protocol.auth.minecraftServerHash
import com.hiczp.minecraft.protocol.data.*
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.MinecraftFormat
import com.hiczp.minecraft.protocol.session.MinecraftSession

data class MinecraftStatusExchange(
    val response: StatusResponsePacket,
    val pong: StatusPongResponsePacket,
)

data class MinecraftClientConfiguration(
    val knownPacks: ConfigurationClientboundKnownPacksPacket?,
    val featureFlags: FeatureFlagsPacket?,
    val registries: List<RegistryDataPacket>,
    val tags: ConfigurationUpdateTagsPacket?,
)

data class MinecraftClientLoginResult(
    val login: LoginSuccessPacket,
    val configuration: MinecraftClientConfiguration,
    val playLogin: PlayLoginPacket,
)

data class MinecraftClientOptions(
    val information: ClientInformation = ClientInformation(
        locale = "en_us",
        viewDistance = 8,
        chatMode = ChatMode.ENABLED,
        chatColors = true,
        displayedSkinParts = 0x7F,
        mainHand = MainHand.RIGHT,
        enableTextFiltering = false,
        allowServerListings = true,
        particleStatus = ParticleStatus.ALL,
    ),
    val maximumPacketsPerPhase: Int = 2_048,
) {
    init {
        require(maximumPacketsPerPhase > 0)
    }
}

class MinecraftClientProtocol(
    val session: MinecraftSession,
    val serverAddress: String,
    val serverPort: Int,
) {
    suspend fun queryStatus(
        pingPayload: Long = 0,
    ): MinecraftStatusExchange {
        sendHandshake(HandshakeNextState.STATUS)
        session.send(StatusRequestPacket)
        val response = session.receive()
        if (response !is StatusResponsePacket) {
            throw MinecraftClientException(
                "Expected Status Response, received ${response::class.simpleName}",
            )
        }
        session.send(StatusPingRequestPacket(pingPayload))
        val pong = session.receive()
        if (pong !is StatusPongResponsePacket || pong.timestamp != pingPayload) {
            throw MinecraftClientException(
                "Status pong did not preserve payload $pingPayload: $pong",
            )
        }
        return MinecraftStatusExchange(response, pong)
    }

    suspend fun login(
        identity: MinecraftClientIdentity,
        options: MinecraftClientOptions = MinecraftClientOptions(),
        handler: MinecraftClientHandler = DefaultMinecraftClientHandler,
    ): MinecraftClientLoginResult {
        sendHandshake(HandshakeNextState.LOGIN)
        session.send(LoginStartPacket(identity.name, identity.id))

        var loginSuccess: LoginSuccessPacket? = null
        var loginPackets = 0
        while (loginSuccess == null) {
            if (++loginPackets > options.maximumPacketsPerPhase) {
                throw MinecraftClientException("Login packet limit exceeded")
            }
            val packet = session.receive()
            when (packet) {
                is LoginDisconnectPacket ->
                    throw MinecraftClientException(
                        "Server rejected Login: ${packet.reason.json}",
                    )

                is EncryptionRequestPacket ->
                    answerEncryptionRequest(packet, identity)

                is LoginCookieRequestPacket ->
                    session.send(
                        LoginCookieResponsePacket(
                            key = packet.key,
                            payload = handler.loginCookie(packet),
                        ),
                    )

                is LoginPluginRequestPacket ->
                    session.send(
                        LoginPluginResponsePacket(
                            messageId = packet.messageId,
                            data = handler.loginPlugin(packet),
                        ),
                    )

                is LoginSuccessPacket -> {
                    loginSuccess = packet
                    session.send(LoginAcknowledgedPacket)
                }

                else -> handler.onPacket(packet)
            }
        }

        session.send(ConfigurationClientInformationPacket(options.information))
        var knownPacks: ConfigurationClientboundKnownPacksPacket? = null
        var featureFlags: FeatureFlagsPacket? = null
        var tags: ConfigurationUpdateTagsPacket? = null
        val registries = mutableListOf<RegistryDataPacket>()
        var configurationPackets = 0
        while (session.state == com.hiczp.minecraft.protocol.model.packet.ConnectionState.CONFIGURATION) {
            if (++configurationPackets > options.maximumPacketsPerPhase) {
                throw MinecraftClientException("Configuration packet limit exceeded")
            }
            val packet = session.receive()
            when (packet) {
                is ConfigurationDisconnectPacket ->
                    throw MinecraftClientException(
                        "Server rejected Configuration: ${packet.reason}",
                    )

                is ConfigurationCookieRequestPacket ->
                    session.send(
                        ConfigurationCookieResponsePacket(
                            key = packet.key,
                            payload = handler.configurationCookie(packet),
                        ),
                    )

                is ConfigurationClientboundKeepAlivePacket ->
                    session.send(ConfigurationServerboundKeepAlivePacket(packet.id))

                is ConfigurationPingPacket ->
                    session.send(ConfigurationPongPacket(packet.id))

                is ConfigurationClientboundKnownPacksPacket -> {
                    knownPacks = packet
                    session.send(
                        ConfigurationServerboundKnownPacksPacket(
                            handler.selectKnownPacks(packet.knownPacks),
                        ),
                    )
                }

                is FeatureFlagsPacket -> featureFlags = packet
                is RegistryDataPacket -> {
                    if (
                        registries.any {
                            it.registryId == packet.registryId
                        }
                    ) {
                        throw MinecraftClientException(
                            "Server sent duplicate registry " +
                                    packet.registryId,
                        )
                    }
                    registries += packet
                }
                is ConfigurationUpdateTagsPacket -> tags = packet
                is CodeOfConductPacket -> {
                    if (!handler.acceptCodeOfConduct(packet)) {
                        throw MinecraftClientException(
                            "Code of Conduct was not accepted",
                        )
                    }
                    session.send(AcceptCodeOfConductPacket)
                }

                is FinishConfigurationPacket ->
                    session.send(AcknowledgeFinishConfigurationPacket)

                else -> handler.onPacket(packet)
            }
        }

        var playPackets = 0
        while (true) {
            if (++playPackets > options.maximumPacketsPerPhase) {
                throw MinecraftClientException(
                    "Play Login packet limit exceeded",
                )
            }
            when (val packet = session.receive()) {
                is PlayLoginPacket -> {
                    configurePlayFormat(registries, packet)
                    return MinecraftClientLoginResult(
                        login = loginSuccess,
                        configuration = MinecraftClientConfiguration(
                            knownPacks = knownPacks,
                            featureFlags = featureFlags,
                            registries = registries.toList(),
                            tags = tags,
                        ),
                        playLogin = packet,
                    )
                }

                else -> handler.onPacket(packet)
            }
        }
    }

    private fun configurePlayFormat(
        registries: List<RegistryDataPacket>,
        playLogin: PlayLoginPacket,
    ) {
        if (playLogin.spawnInfo.dimension !in playLogin.levels) {
            throw MinecraftClientException(
                "Play Login selected dimension " +
                        "${playLogin.spawnInfo.dimension}, but it is absent " +
                        "from the advertised levels",
            )
        }
        val dimensionTypeRegistryId = Identifier("dimension_type")
        val dimensionTypeRegistry =
            registries.registry(dimensionTypeRegistryId)
                ?: VanillaProtocolData.requireRegistry(dimensionTypeRegistryId)
        val dimensionType = dimensionTypeRegistry.entries.getOrNull(
            playLogin.spawnInfo.dimensionTypeId,
        ) ?: throw MinecraftClientException(
            "Play Login selected absent dimension-type registry ID " +
                    playLogin.spawnInfo.dimensionTypeId,
        )
        val dimension =
            if (dimensionType.data == null) {
                MinecraftDimensionLayout.from(
                    VanillaProtocolData,
                    dimensionType.id,
                )
            } else {
                MinecraftDimensionLayout.from(
                    listOf(dimensionTypeRegistry),
                    playLogin.spawnInfo.dimensionTypeId,
                )
            }
        val biomeRegistryId = Identifier("worldgen/biome")
        val biomeRegistrySize = (
                registries.registry(biomeRegistryId)
                    ?: VanillaProtocolData.requireRegistry(biomeRegistryId)
                )
            .entries
            .size
        if (biomeRegistrySize == 0) {
            throw MinecraftClientException(
                "The synchronized biome registry is empty",
            )
        }
        session.format = MinecraftFormat(
            configuration = session.format.configuration.copy(
                chunkSectionCount = dimension.sectionCount,
                blockStateRegistrySize = VanillaStaticData.blockStates.size,
                biomeRegistrySize = biomeRegistrySize,
            ),
            serializersModule = session.format.serializersModule,
        )
    }

    private suspend fun answerEncryptionRequest(
        request: EncryptionRequestPacket,
        identity: MinecraftClientIdentity,
    ) {
        if (identity !is MinecraftOnlineIdentity) {
            throw MinecraftClientException(
                "Server requested encrypted online Login for an offline identity",
            )
        }
        val encryption = MinecraftEncryption.answerServerChallenge(
            request,
            identity.cryptography,
        )
        if (request.shouldAuthenticate) {
            identity.sessionService.join(
                accessToken = identity.accessToken,
                selectedProfile = identity.id,
                serverHash = minecraftServerHash(
                    serverId = request.serverId,
                    sharedSecret = encryption.sharedSecret,
                    encodedPublicKey = request.publicKey.toByteArray(),
                ),
            )
        }
        session.send(encryption.response)
        session.enableEncryption(encryption.sharedSecret)
    }

    private suspend fun sendHandshake(nextState: HandshakeNextState) {
        session.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = serverAddress,
                serverPort = serverPort,
                nextState = nextState,
            ),
        )
    }
}

class MinecraftClientException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
