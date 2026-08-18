package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.data.*
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.ClientNegotiationProfile
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.VanillaClient
import io.ktor.client.*

data class MinecraftStatusExchange(
    val response: StatusResponsePacket,
    val pong: StatusPongResponsePacket,
)

data class MinecraftClientConfiguration(
    val knownPacks: ConfigurationClientboundKnownPacksPacket?,
    val featureFlags: FeatureFlagsPacket?,
    val registries: List<RegistryDataPacket>,
    val tags: ConfigurationUpdateTagsPacket?,
    val storedCookies: Map<Identifier, ByteString>,
)

/**
 * Client-side negotiation facts. The installed, potentially replaceable registry context remains
 * connection state: [MinecraftClientConnection.registries] holds its authoritative value once
 * negotiation reaches Play.
 */
data class MinecraftClientNegotiationResult(
    val login: LoginSuccessPacket,
    val configuration: MinecraftClientConfiguration,
    val playLogin: PlayLoginPacket,
    val profile: NegotiationProfileResult,
)

sealed interface NegotiationQueryResult {
    data object Pass : NegotiationQueryResult

    data class Respond(
        val packets: List<ServerboundPacket>,
    ) : NegotiationQueryResult

    data class Reject(
        val reason: String,
    ) : NegotiationQueryResult
}

class MinecraftClientNegotiationOptions(
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
    val protocolData: ProtocolDataSet = VanillaProtocolData,
    loginCookies: Map<Identifier, ByteString> = emptyMap(),
    configurationCookies: Map<Identifier, ByteString> = emptyMap(),
    acceptedKnownPacks: Set<KnownPack> = protocolData.knownPacks.toSet(),
    val acceptCodeOfConduct: Boolean = true,
    val resourcePackResult: ResourcePackResult = ResourcePackResult.DECLINED,
    val staticRegistries: StaticRegistrySchema = protocolData.staticRegistries,
    val maximumPacketsPerPhase: Int = 2_048,
    val onUnhandledQuery: (suspend (UnknownPacket.Clientbound) -> NegotiationQueryResult)? = null,
) {
    val loginCookies: Map<Identifier, ByteString> = loginCookies.toMap()
    val configurationCookies: Map<Identifier, ByteString> = configurationCookies.toMap()
    val acceptedKnownPacks: Set<KnownPack> = acceptedKnownPacks.toSet()

    init {
        require(maximumPacketsPerPhase > 0)
    }
}

/**
 * Runs one status exchange on a fresh Handshake connection: Handshake into Status, one
 * request/response, one ping/pong. Status has no continuation after the pong; this method does not
 * close the local connection, which remains the caller's responsibility whether or not the peer
 * closes first.
 */
suspend fun MinecraftClientConnection.queryStatus(
    pingPayload: Long = 0,
): MinecraftStatusExchange {
    require(state == ConnectionState.HANDSHAKE) {
        "Status requires a fresh Handshake connection"
    }
    outgoing.send(handshake(HandshakeNextState.STATUS))
    outgoing.send(StatusRequestPacket)
    val response = incoming.receive()
    if (response !is StatusResponsePacket) {
        throw MinecraftClientException(
            "Expected Status Response, received ${response::class.simpleName}",
        )
    }
    outgoing.send(StatusPingRequestPacket(pingPayload))
    val pong = incoming.receive()
    if (pong !is StatusPongResponsePacket || pong.timestamp != pingPayload) {
        throw MinecraftClientException(
            "Status pong did not preserve payload $pingPayload: $pong",
        )
    }
    return MinecraftStatusExchange(response, pong)
}

/**
 * Runs the preset negotiation while exclusively borrowing [incoming] and
 * [outgoing]. Callers must not concurrently receive or send until this method
 * returns. The implementation uses only this connection's public API.
 *
 * On return the open connection has reached Play with the negotiated registry context installed
 * in [MinecraftClientConnection.registries]; further traffic and closing then belong to the
 * caller. Failures raised by this library, including server rejections and
 * [MinecraftClientTransferException] (whose host and port describe the reconnection target),
 * leave the connection open for the caller to close. Wire and pump failures surface as their
 * original exception with the connection already terminated; only closing remains.
 */
suspend fun MinecraftClientConnection.negotiate(
    identity: MinecraftIdentity,
    sessionHttpClient: HttpClient? = null,
    profile: ClientNegotiationProfile = VanillaClient,
    options: MinecraftClientNegotiationOptions =
        MinecraftClientNegotiationOptions(),
): MinecraftClientNegotiationResult {
    require(state == ConnectionState.HANDSHAKE) {
        "Login requires a fresh Handshake connection"
    }
    profile.begin(this)
    outgoing.send(
        profile.prepareHandshake(handshake(HandshakeNextState.LOGIN)),
    )
    outgoing.send(LoginStartPacket(identity.name, identity.id))

    var loginSuccess: LoginSuccessPacket? = null
    var loginPackets = 0
    while (loginSuccess == null) {
        if (++loginPackets > options.maximumPacketsPerPhase) {
            throw MinecraftClientException("Login packet limit exceeded")
        }
        when (val packet = incoming.receive()) {
            is LoginDisconnectPacket ->
                throw MinecraftClientException(
                    "Server rejected Login: ${packet.reason.json}",
                )

            is EncryptionRequestPacket -> answerEncryptionRequest(
                packet,
                identity,
                sessionHttpClient,
            )

            is LoginCookieRequestPacket -> outgoing.send(
                LoginCookieResponsePacket(
                    packet.key,
                    options.loginCookies[packet.key],
                ),
            )

            is SetCompressionPacket -> Unit

            is LoginSuccessPacket -> {
                loginSuccess = packet
                outgoing.send(LoginAcknowledgedPacket)
                awaitState(ConnectionState.CONFIGURATION)
            }

            else -> handleLoginExtension(profile, packet, options)
        }
    }
    val login = checkNotNull(loginSuccess)

    outgoing.send(ConfigurationClientInformationPacket(options.information))
    var knownPacks: ConfigurationClientboundKnownPacksPacket? = null
    var featureFlags: FeatureFlagsPacket? = null
    var tags: ConfigurationUpdateTagsPacket? = null
    val registryPackets = mutableListOf<RegistryDataPacket>()
    val storedCookies = linkedMapOf<Identifier, ByteString>()
    var resolvedContext: ProtocolRegistryContext? = null
    var configurationFinished = false
    var configurationPackets = 0
    while (!configurationFinished) {
        if (++configurationPackets > options.maximumPacketsPerPhase) {
            throw MinecraftClientException("Configuration packet limit exceeded")
        }
        when (val packet = incoming.receive()) {
            is ConfigurationDisconnectPacket ->
                throw MinecraftClientException(
                    "Server rejected Configuration: ${packet.reason}",
                )

            is ConfigurationCookieRequestPacket -> outgoing.send(
                ConfigurationCookieResponsePacket(
                    packet.key,
                    options.configurationCookies[packet.key],
                ),
            )

            is ConfigurationClientboundKeepAlivePacket -> outgoing.send(
                ConfigurationServerboundKeepAlivePacket(packet.id),
            )

            is ConfigurationPingPacket ->
                outgoing.send(ConfigurationPongPacket(packet.id))

            is ConfigurationClientboundKnownPacksPacket -> {
                knownPacks = packet
                outgoing.send(
                    ConfigurationServerboundKnownPacksPacket(
                        packet.knownPacks.filter(options.acceptedKnownPacks::contains),
                    ),
                )
            }

            is FeatureFlagsPacket -> featureFlags = packet

            is RegistryDataPacket -> {
                if (registryPackets.any { it.registryId == packet.registryId }) {
                    throw MinecraftClientException(
                        "Server sent duplicate registry ${packet.registryId}",
                    )
                }
                registryPackets += packet
            }

            is ConfigurationUpdateTagsPacket -> tags = packet

            is ConfigurationStoreCookiePacket ->
                storedCookies[packet.key] = packet.payload

            is ConfigurationAddResourcePackPacket -> outgoing.send(
                ConfigurationResourcePackResponsePacket(
                    packet.uuid,
                    options.resourcePackResult,
                ),
            )

            is CodeOfConductPacket -> {
                if (!options.acceptCodeOfConduct) {
                    throw MinecraftClientException(
                        "Code of Conduct was not accepted",
                    )
                }
                outgoing.send(AcceptCodeOfConductPacket)
            }

            is ConfigurationTransferPacket ->
                throw MinecraftClientTransferException(packet.host, packet.port)

            is FinishConfigurationPacket -> {
                val context = resolveRegistryContext(
                    registryPackets,
                    options,
                    profile,
                )
                resolvedContext = context
                installRegistryContext(context)
                profile.preparePlay(this)
                outgoing.send(AcknowledgeFinishConfigurationPacket)
                awaitState(ConnectionState.PLAY)
                configurationFinished = true
            }

            is ConfigurationRemoveResourcePackPacket,
            is ConfigurationCustomReportDetailsPacket,
            is ConfigurationServerLinksPacket,
            ConfigurationClearDialogPacket,
            is ConfigurationShowDialogPacket,
            ResetChatPacket,
                -> Unit

            else -> handleConfigurationExtension(profile, packet, options)
        }
    }

    var playLogin: PlayLoginPacket? = null
    var playPackets = 0
    while (playLogin == null) {
        if (++playPackets > options.maximumPacketsPerPhase) {
            throw MinecraftClientException("Play Login packet limit exceeded")
        }
        when (val packet = incoming.receive()) {
            is PlayLoginPacket -> {
                val context = checkNotNull(resolvedContext) {
                    "Configuration did not resolve registry context"
                }
                val activeContext = configureActiveDimension(
                    context,
                    registryPackets,
                    packet,
                    options.protocolData,
                )
                installRegistryContext(activeContext)
                playLogin = packet
            }

            else -> {
                if (packet is UnknownPacket.Clientbound) {
                    handleUnknownQuery(packet, options)
                } else {
                    throw MinecraftClientException(
                        "Expected Play Login, received ${packet::class.simpleName}",
                    )
                }
            }
        }
    }
    val actualPlayLogin = checkNotNull(playLogin)
    val profileResult = profile.complete(this)
    return MinecraftClientNegotiationResult(
        login = login,
        configuration = MinecraftClientConfiguration(
            knownPacks,
            featureFlags,
            registryPackets.toList(),
            tags,
            storedCookies.toMap(),
        ),
        playLogin = actualPlayLogin,
        profile = profileResult,
    )
}

private suspend fun MinecraftClientConnection.handleLoginExtension(
    profile: ClientNegotiationProfile,
    packet: ClientboundPacket,
    options: MinecraftClientNegotiationOptions,
) {
    if (profile.handleLoginPacket(this, packet)) return
    if (packet is UnknownPacket.Clientbound) {
        handleUnknownQuery(packet, options)
        return
    }
    throw MinecraftClientException(
        "Unexpected Login packet ${packet::class.simpleName}",
    )
}

private suspend fun MinecraftClientConnection.handleConfigurationExtension(
    profile: ClientNegotiationProfile,
    packet: ClientboundPacket,
    options: MinecraftClientNegotiationOptions,
) {
    if (profile.handleConfigurationPacket(this, packet)) return
    if (packet is UnknownPacket.Clientbound) {
        handleUnknownQuery(packet, options)
        return
    }
    if (
        packet is ConfigurationClientboundPluginMessagePacket &&
        packet.payload is CustomPayload.Brand
    ) {
        return
    }
    throw MinecraftClientException(
        "Unexpected Configuration packet ${packet::class.simpleName}",
    )
}

private suspend fun MinecraftClientConnection.handleUnknownQuery(
    packet: UnknownPacket.Clientbound,
    options: MinecraftClientNegotiationOptions,
) {
    val decision = options.onUnhandledQuery?.invoke(packet)
        ?: defaultUnknownQueryResult(packet)
    when (decision) {
        NegotiationQueryResult.Pass -> Unit
        is NegotiationQueryResult.Reject ->
            throw MinecraftClientException(decision.reason)

        is NegotiationQueryResult.Respond ->
            decision.packets.forEach { outgoing.send(it) }
    }
}

private fun defaultUnknownQueryResult(
    packet: UnknownPacket.Clientbound,
): NegotiationQueryResult {
    val route = packet.route as? PacketRoute.LoginQuery
        ?: return NegotiationQueryResult.Pass
    return NegotiationQueryResult.Respond(
        listOf(
            UnknownPacket.Serverbound(
                PacketRoute.LoginQuery(
                    direction = PacketDirection.SERVERBOUND,
                    transactionId = route.transactionId,
                    channel = route.channel,
                    hasPayload = false,
                ),
                ByteString(byteArrayOf()),
            ),
        ),
    )
}

private suspend fun MinecraftClientConnection.answerEncryptionRequest(
    request: EncryptionRequestPacket,
    identity: MinecraftIdentity,
    sessionHttpClient: HttpClient?,
) {
    val onlineIdentity =
        if (request.shouldAuthenticate) {
            identity as? MinecraftOnlineIdentity
                ?: throw MinecraftClientException(
                    "Server requested online authentication for an offline identity",
                )
        } else {
            null
        }
    val api =
        if (request.shouldAuthenticate) {
            MinecraftSessionApi(
                sessionHttpClient ?: throw MinecraftClientException(
                    "Server requested online authentication, but no Session Server HttpClient was supplied",
                ),
            )
        } else {
            null
        }
    val exchange = MinecraftClientKeyExchange.respond(request)
    if (onlineIdentity != null && api != null) {
        api.join(onlineIdentity, exchange.serverHash)
    }
    val sharedSecret = exchange.sharedSecret
    try {
        prepareOutboundEncryption(sharedSecret)
        outgoing.send(exchange.toEncryptionResponsePacket())
    } finally {
        sharedSecret.fill(0)
    }
}

private suspend fun MinecraftClientConnection.resolveRegistryContext(
    registries: List<RegistryDataPacket>,
    options: MinecraftClientNegotiationOptions,
    profile: ClientNegotiationProfile,
): ProtocolRegistryContext {
    var context = options.protocolData.registryContext
    if (options.staticRegistries !== options.protocolData.staticRegistries) {
        context = context.withStaticRegistryResolution(
            options.staticRegistries.resolve(),
        )
    }
    context = context.withRegistries(
        registries.map { packet ->
            ProtocolRegistry(
                packet.registryId,
                packet.entries.mapIndexed { rawId, entry ->
                    ProtocolRegistryEntry(entry.id, rawId)
                },
            )
        },
    )
    val biomeSize = context.registrySize(ProtocolRegistryContext.BIOME_REGISTRY)
        ?: throw MinecraftClientException(
            "Configuration did not provide a biome registry and the static schema has none",
        )
    if (biomeSize == 0) {
        throw MinecraftClientException("The synchronized biome registry is empty")
    }
    return profile.resolveRegistryContext(context)
}

private fun configureActiveDimension(
    context: ProtocolRegistryContext,
    registries: List<RegistryDataPacket>,
    playLogin: PlayLoginPacket,
    protocolData: ProtocolDataSet,
): ProtocolRegistryContext {
    if (playLogin.spawnInfo.dimension !in playLogin.levels) {
        throw MinecraftClientException(
            "Play Login selected dimension ${playLogin.spawnInfo.dimension}, but it is absent from the advertised levels",
        )
    }
    val dimensionTypeRegistryId = Identifier("dimension_type")
    val dimensionTypeRegistry =
        registries.registry(dimensionTypeRegistryId)
            ?: protocolData.requireRegistry(dimensionTypeRegistryId)
    val dimensionType = dimensionTypeRegistry.entries.getOrNull(
        playLogin.spawnInfo.dimensionTypeId,
    ) ?: throw MinecraftClientException(
        "Play Login selected absent dimension-type registry ID ${playLogin.spawnInfo.dimensionTypeId}",
    )
    val dimension =
        if (dimensionType.data == null) {
            MinecraftDimensionLayout.from(
                protocolData,
                dimensionType.id,
            )
        } else {
            MinecraftDimensionLayout.from(
                listOf(dimensionTypeRegistry),
                playLogin.spawnInfo.dimensionTypeId,
            )
        }
    return context.withChunkSectionCount(dimension.sectionCount)
}

private fun MinecraftClientConnection.handshake(
    nextState: HandshakeNextState,
): HandshakePacket = HandshakePacket(
    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
    serverAddress = serverAddress,
    serverPort = serverPort,
    nextState = nextState,
)

/** Invalid client-side protocol orchestration or peer behavior. */
open class MinecraftClientException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class MinecraftClientTransferException(
    val host: String,
    val port: Int,
) : MinecraftClientException("Server transferred the client to $host:$port")
