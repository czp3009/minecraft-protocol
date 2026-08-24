package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.ProtocolDataSet
import com.hiczp.minecraft.protocol.datapack.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaDataPacks
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
    val dimensionLayout: MinecraftDimensionLayout,
    val profile: NegotiationProfileResult,
) {
    /** The world-Chunk layout selected by the server for the initial Play dimension. */
    val chunkLayout: ChunkLayout = ChunkLayout(
        minSectionY = MinecraftCoordinates.sectionCoordinate(dimensionLayout.minY),
        sectionCount = dimensionLayout.sectionCount,
    )
}

sealed interface ClientNegotiationQueryResult {
    data object Pass : ClientNegotiationQueryResult

    data class Respond(
        val packets: List<ServerboundPacket>,
    ) : ClientNegotiationQueryResult

    data class Reject(
        val reason: String,
    ) : ClientNegotiationQueryResult
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
    val protocolData: ProtocolDataSet = VanillaDataPacks.protocolData,
    loginCookies: Map<Identifier, ByteString> = emptyMap(),
    configurationCookies: Map<Identifier, ByteString> = emptyMap(),
    acceptedKnownPacks: Set<KnownPack> = protocolData.knownPacks.toSet(),
    val acceptCodeOfConduct: Boolean = true,
    val resourcePackResult: ResourcePackResult = ResourcePackResult.DECLINED,
    val staticRegistries: StaticRegistrySchema = protocolData.staticRegistries,
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
    val response = incoming.receive()
    if (response !is StatusResponsePacket) {
        throw MinecraftClientException(
            "Expected Status Response, received ${response::class.simpleName}",
        )
    }
    outgoing.send(StatusPingRequestPacket(pingPayload))
    requestFlush()
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
 * [outgoing]. Callers must guarantee that no other coroutine receives or sends
 * until this method returns; violating that precondition is a programming
 * error. This method runs sequentially in the calling coroutine, does not
 * launch a negotiation scope or select a dispatcher, and uses no lock to
 * arbitrate competing channel users. The implementation uses only this
 * connection's public API.
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
    options: MinecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(),
): MinecraftClientNegotiationResult {
    require(state == ConnectionState.HANDSHAKE) {
        "Login requires a fresh Handshake connection"
    }
    val login = negotiateLogin(identity, sessionHttpClient, profile, options)
    val configuration = negotiateConfiguration(profile, options)
    val play = awaitPlayLogin(configuration.registries, options)
    val profileResult = profile.complete(this)
    return MinecraftClientNegotiationResult(
        login = login,
        configuration = configuration,
        playLogin = play.packet,
        dimensionLayout = play.dimensionLayout,
        profile = profileResult,
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
): MinecraftClientConfiguration {
    outgoing.send(ConfigurationClientInformationPacket(options.information))
    requestFlush()
    var knownPacks: ConfigurationClientboundKnownPacksPacket? = null
    var featureFlags: FeatureFlagsPacket? = null
    var tags: ConfigurationUpdateTagsPacket? = null
    val registryPackets = mutableListOf<RegistryDataPacket>()
    val storedCookies = linkedMapOf<Identifier, ByteString>()
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
                val baseContext = registryContextOrClientFailure {
                    options.protocolData.resolveSynchronizedRegistryContext(
                        registries = registryPackets,
                        staticRegistries = options.staticRegistries,
                    )
                }
                val context = profile.resolveRegistryContext(baseContext)
                installRegistryContext(context)
                profile.preparePlay(this)
                outgoing.send(AcknowledgeFinishConfigurationPacket)
                requestFlush()
                awaitState(ConnectionState.PLAY)
                return MinecraftClientConfiguration(
                    knownPacks = knownPacks,
                    featureFlags = featureFlags,
                    registries = registryPackets.toList(),
                    tags = tags,
                    storedCookies = storedCookies.toMap(),
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
    registryPackets: List<RegistryDataPacket>,
    options: MinecraftClientNegotiationOptions,
): MinecraftClientPlayLogin {
    while (true) {
        when (val packet = incoming.receive()) {
            is PlayLoginPacket -> {
                val dimensionLayout = registryContextOrClientFailure {
                    MinecraftDimensionLayout.from(
                        login = packet,
                        registries = registryPackets,
                        protocolData = options.protocolData,
                    )
                }
                val activeContext = registries.withChunkSectionCount(dimensionLayout.sectionCount)
                installRegistryContext(activeContext)
                return MinecraftClientPlayLogin(packet, dimensionLayout)
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
    val packet: PlayLoginPacket,
    val dimensionLayout: MinecraftDimensionLayout,
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
            decision.packets.forEach { outgoing.send(it) }
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
