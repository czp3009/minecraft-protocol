package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.datapack.DataPackConfigurationSnapshot
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.ProtocolData
import com.hiczp.minecraft.protocol.datapack.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.ClientNegotiationProfile
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.VanillaClient
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.MinecraftCoordinates
import io.ktor.client.*

data class MinecraftStatusExchange(
    val statusResponsePacket: StatusResponsePacket,
    val statusPongResponsePacket: StatusPongResponsePacket,
)

private data class MinecraftClientConfigurationResult(
    val dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    val storedConfigurationCookies: Map<Identifier, ByteString>,
)

/**
 * Client-side negotiation facts. The installed, potentially replaceable registry context remains
 * connection state: [MinecraftClientConnection.protocolRegistryContext] holds its authoritative value once
 * negotiation reaches Play.
 */
data class MinecraftClientNegotiationResult(
    val loginSuccessPacket: LoginSuccessPacket,
    val dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    val storedConfigurationCookies: Map<Identifier, ByteString>,
    val playLoginPacket: PlayLoginPacket,
    val minecraftDimensionLayout: MinecraftDimensionLayout,
    val negotiationProfileResult: NegotiationProfileResult,
) {
    /** The world-Chunk layout selected by the server for the initial Play dimension. */
    val chunkLayout: ChunkLayout = ChunkLayout(
        minSectionY = MinecraftCoordinates.sectionCoordinate(minecraftDimensionLayout.minY),
        sectionCount = minecraftDimensionLayout.sectionCount,
    )
}

sealed interface ClientNegotiationQueryResult {
    data object Pass : ClientNegotiationQueryResult

    data class Respond(
        val serverboundPackets: List<ServerboundPacket>,
    ) : ClientNegotiationQueryResult

    data class Reject(
        val reason: String,
    ) : ClientNegotiationQueryResult
}

class MinecraftClientNegotiationOptions(
    val clientInformation: ClientInformation = ClientInformation(
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
    val protocolData: ProtocolData = VanillaProtocolData,
    loginCookies: Map<Identifier, ByteString> = emptyMap(),
    configurationCookies: Map<Identifier, ByteString> = emptyMap(),
    acceptedKnownPacks: Set<KnownPack> = protocolData.offeredKnownPacks.toSet(),
    val acceptCodeOfConduct: Boolean = true,
    val resourcePackResult: ResourcePackResult = ResourcePackResult.DECLINED,
    val staticRegistrySchema: StaticRegistrySchema = protocolData.staticRegistrySchema,
    val onUnhandledQuery: (suspend (UnknownPacket.Clientbound) -> ClientNegotiationQueryResult)? = null,
) {
    val loginCookies: Map<Identifier, ByteString> = loginCookies.toMap()
    val configurationCookies: Map<Identifier, ByteString> = configurationCookies.toMap()
    val acceptedKnownPacks: Set<KnownPack> = acceptedKnownPacks.toSet()
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
    requestFlush()
    val statusResponsePacket = incoming.receive()
    if (statusResponsePacket !is StatusResponsePacket) {
        throw MinecraftClientException(
            "Expected Status Response, received ${statusResponsePacket::class.simpleName}",
        )
    }
    outgoing.send(StatusPingRequestPacket(pingPayload))
    requestFlush()
    val statusPongResponsePacket = incoming.receive()
    if (statusPongResponsePacket !is StatusPongResponsePacket || statusPongResponsePacket.timestamp != pingPayload) {
        throw MinecraftClientException(
            "Status pong did not preserve payload $pingPayload: $statusPongResponsePacket",
        )
    }
    return MinecraftStatusExchange(statusResponsePacket, statusPongResponsePacket)
}

/**
 * Runs the preset negotiation while exclusively borrowing [incoming] and
 * [outgoing]. Callers must guarantee that no other coroutine receives or sends
 * until this method returns; violating that precondition is a programming
 * error. This method runs sequentially in the calling coroutine, does not
 * launch a negotiation scope or select a dispatcher, and uses no lock to
 * arbitrate competing channel users. The implementation uses only this
 * connection's public API.
 *
 * On return the open connection has reached Play with the negotiated registry context installed
 * in [MinecraftClientConnection.protocolRegistryContext]; further traffic and closing then belong to the
 * caller. Failures raised by this library, including server rejections and
 * [MinecraftClientTransferException] (whose host and port describe the reconnection target),
 * leave the connection open for the caller to close. Wire and pump failures surface as their
 * original exception with the connection already terminated; only closing remains.
 */
suspend fun MinecraftClientConnection.negotiate(
    identity: MinecraftIdentity,
    sessionHttpClient: HttpClient? = null,
    profile: ClientNegotiationProfile = VanillaClient,
    options: MinecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(),
): MinecraftClientNegotiationResult {
    require(state == ConnectionState.HANDSHAKE) {
        "Login requires a fresh Handshake connection"
    }
    val loginSuccessPacket = negotiateLogin(identity, sessionHttpClient, profile, options)
    val minecraftClientConfigurationResult = negotiateConfiguration(profile, options)
    val minecraftClientPlayLogin = awaitPlayLogin(
        minecraftClientConfigurationResult.dataPackConfigurationSnapshot.synchronizedRegistryPackets,
        options,
    )
    val negotiationProfileResult = profile.complete(this)
    return MinecraftClientNegotiationResult(
        loginSuccessPacket = loginSuccessPacket,
        dataPackConfigurationSnapshot = minecraftClientConfigurationResult.dataPackConfigurationSnapshot,
        storedConfigurationCookies = minecraftClientConfigurationResult.storedConfigurationCookies,
        playLoginPacket = minecraftClientPlayLogin.playLoginPacket,
        minecraftDimensionLayout = minecraftClientPlayLogin.minecraftDimensionLayout,
        negotiationProfileResult = negotiationProfileResult,
    )
}

private suspend fun MinecraftClientConnection.negotiateLogin(
    identity: MinecraftIdentity,
    sessionHttpClient: HttpClient?,
    profile: ClientNegotiationProfile,
    options: MinecraftClientNegotiationOptions,
): LoginSuccessPacket {
    profile.begin(this)
    outgoing.send(
        profile.prepareHandshake(handshake(HandshakeNextState.LOGIN)),
    )
    outgoing.send(LoginStartPacket(identity.name, identity.id))
    requestFlush()

    while (true) {
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
                outgoing.send(LoginAcknowledgedPacket)
                awaitState(ConnectionState.CONFIGURATION)
                return packet
            }

            else -> handleLoginExtension(profile, packet, options)
        }
        requestFlush()
    }
}

private suspend fun MinecraftClientConnection.negotiateConfiguration(
    profile: ClientNegotiationProfile,
    options: MinecraftClientNegotiationOptions,
): MinecraftClientConfigurationResult {
    outgoing.send(ConfigurationClientInformationPacket(options.clientInformation))
    requestFlush()
    var configurationClientboundKnownPacksPacket: ConfigurationClientboundKnownPacksPacket? = null
    var featureFlagsPacket: FeatureFlagsPacket? = null
    var configurationUpdateTagsPacket: ConfigurationUpdateTagsPacket? = null
    val synchronizedRegistryPackets = mutableListOf<RegistryDataPacket>()
    val storedConfigurationCookies = linkedMapOf<Identifier, ByteString>()
    while (true) {
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
                configurationClientboundKnownPacksPacket = packet
                outgoing.send(
                    ConfigurationServerboundKnownPacksPacket(
                        packet.knownPacks.filter(options.acceptedKnownPacks::contains),
                    ),
                )
            }

            is FeatureFlagsPacket -> featureFlagsPacket = packet

            is RegistryDataPacket -> {
                if (synchronizedRegistryPackets.any { it.registryId == packet.registryId }) {
                    throw MinecraftClientException(
                        "Server sent duplicate registry ${packet.registryId}",
                    )
                }
                synchronizedRegistryPackets += packet
            }

            is ConfigurationUpdateTagsPacket -> configurationUpdateTagsPacket = packet

            is ConfigurationStoreCookiePacket ->
                storedConfigurationCookies[packet.key] = packet.payload

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
                val baseProtocolRegistryContext = registryContextOrClientFailure {
                    options.protocolData.resolveSynchronizedRegistryContext(
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        staticRegistrySchema = options.staticRegistrySchema,
                    )
                }
                val protocolRegistryContext = profile.resolveProtocolRegistryContext(baseProtocolRegistryContext)
                installProtocolRegistryContext(protocolRegistryContext)
                profile.preparePlay(this)
                outgoing.send(AcknowledgeFinishConfigurationPacket)
                requestFlush()
                awaitState(ConnectionState.PLAY)
                return MinecraftClientConfigurationResult(
                    dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
                        offeredKnownPacks = configurationClientboundKnownPacksPacket?.knownPacks.orEmpty(),
                        enabledFeatureFlags = featureFlagsPacket?.featureFlags.orEmpty(),
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        registryTags = configurationUpdateTagsPacket?.tags.orEmpty(),
                    ),
                    storedConfigurationCookies = storedConfigurationCookies.toMap(),
                )
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
        requestFlush()
    }
}

private suspend fun MinecraftClientConnection.awaitPlayLogin(
    synchronizedRegistryPackets: List<RegistryDataPacket>,
    options: MinecraftClientNegotiationOptions,
): MinecraftClientPlayLogin {
    while (true) {
        when (val packet = incoming.receive()) {
            is PlayLoginPacket -> {
                val minecraftDimensionLayout = registryContextOrClientFailure {
                    MinecraftDimensionLayout.from(
                        playLoginPacket = packet,
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        protocolData = options.protocolData,
                    )
                }
                val activeProtocolRegistryContext =
                    protocolRegistryContext.withChunkSectionCount(minecraftDimensionLayout.sectionCount)
                installProtocolRegistryContext(activeProtocolRegistryContext)
                return MinecraftClientPlayLogin(packet, minecraftDimensionLayout)
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
        requestFlush()
    }
}

private data class MinecraftClientPlayLogin(
    val playLoginPacket: PlayLoginPacket,
    val minecraftDimensionLayout: MinecraftDimensionLayout,
)

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
        ClientNegotiationQueryResult.Pass -> Unit
        is ClientNegotiationQueryResult.Reject ->
            throw MinecraftClientException(decision.reason)

        is ClientNegotiationQueryResult.Respond ->
            decision.serverboundPackets.forEach { outgoing.send(it) }
    }
}

private fun defaultUnknownQueryResult(
    packet: UnknownPacket.Clientbound,
): ClientNegotiationQueryResult {
    val route = packet.route as? PacketRoute.LoginQuery
        ?: return ClientNegotiationQueryResult.Pass
    return ClientNegotiationQueryResult.Respond(
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

private inline fun <T> registryContextOrClientFailure(
    operation: () -> T,
): T = try {
    operation()
} catch (failure: IllegalArgumentException) {
    throw MinecraftClientException(
        failure.message ?: "Invalid negotiated registry context",
        failure,
    )
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
