package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.datapack.*
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.ClientNegotiationProfile
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.VanillaClient
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.DimensionId
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
 * negotiation reaches Play. [minecraftDimensionContext] is the validated handoff for the initial Play dimension;
 * semantic block and biome defaults are selected only when the application creates a Chunk context.
 */
data class MinecraftClientNegotiationResult(
    val loginSuccessPacket: LoginSuccessPacket,
    val dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    val storedConfigurationCookies: Map<Identifier, ByteString>,
    val playLoginPacket: PlayLoginPacket,
    val minecraftDimensionContext: MinecraftDimensionContext,
    val negotiationProfileResult: NegotiationProfileResult,
) {
    val minecraftDimensionLayout: MinecraftDimensionLayout
        get() = minecraftDimensionContext.minecraftDimensionLayout

    /** The world-Chunk layout selected by the server for the initial Play dimension. */
    val chunkLayout: ChunkLayout
        get() = minecraftDimensionContext.chunkLayout
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

/** Values consumed while moving one client connection from Handshake through its first Play Login. */
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
    require(connectionState == ConnectionState.HANDSHAKE) {
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
    minecraftIdentity: MinecraftIdentity,
    sessionHttpClient: HttpClient? = null,
    clientNegotiationProfile: ClientNegotiationProfile = VanillaClient,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(),
): MinecraftClientNegotiationResult {
    require(connectionState == ConnectionState.HANDSHAKE) {
        "Login requires a fresh Handshake connection"
    }
    val loginSuccessPacket =
        negotiateLogin(
            minecraftIdentity,
            sessionHttpClient,
            clientNegotiationProfile,
            minecraftClientNegotiationOptions
        )
    val minecraftClientConfigurationResult =
        negotiateConfiguration(clientNegotiationProfile, minecraftClientNegotiationOptions)
    val minecraftClientPlayLogin = awaitPlayLogin(
        minecraftClientConfigurationResult.dataPackConfigurationSnapshot.synchronizedRegistryPackets,
        minecraftClientNegotiationOptions,
    )
    val negotiationProfileResult = clientNegotiationProfile.complete(this)
    return MinecraftClientNegotiationResult(
        loginSuccessPacket = loginSuccessPacket,
        dataPackConfigurationSnapshot = minecraftClientConfigurationResult.dataPackConfigurationSnapshot,
        storedConfigurationCookies = minecraftClientConfigurationResult.storedConfigurationCookies,
        playLoginPacket = minecraftClientPlayLogin.playLoginPacket,
        minecraftDimensionContext = minecraftClientPlayLogin.minecraftDimensionContext,
        negotiationProfileResult = negotiationProfileResult,
    )
}

private suspend fun MinecraftClientConnection.negotiateLogin(
    minecraftIdentity: MinecraftIdentity,
    sessionHttpClient: HttpClient?,
    clientNegotiationProfile: ClientNegotiationProfile,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
): LoginSuccessPacket {
    clientNegotiationProfile.begin(this)
    outgoing.send(
        clientNegotiationProfile.prepareHandshake(handshake(HandshakeNextState.LOGIN)),
    )
    outgoing.send(LoginStartPacket(minecraftIdentity.name, minecraftIdentity.id))
    requestFlush()

    while (true) {
        when (val clientboundPacket = incoming.receive()) {
            is LoginDisconnectPacket ->
                throw MinecraftClientException(
                    "Server rejected Login: ${clientboundPacket.reason.json}",
                )

            is EncryptionRequestPacket -> answerEncryptionRequest(
                clientboundPacket,
                minecraftIdentity,
                sessionHttpClient,
            )

            is LoginCookieRequestPacket -> outgoing.send(
                LoginCookieResponsePacket(
                    clientboundPacket.key,
                    minecraftClientNegotiationOptions.loginCookies[clientboundPacket.key],
                ),
            )

            is SetCompressionPacket -> Unit

            is LoginSuccessPacket -> {
                outgoing.send(LoginAcknowledgedPacket)
                awaitState(ConnectionState.CONFIGURATION)
                return clientboundPacket
            }

            else -> handleLoginExtension(clientNegotiationProfile, clientboundPacket, minecraftClientNegotiationOptions)
        }
        requestFlush()
    }
}

private suspend fun MinecraftClientConnection.negotiateConfiguration(
    clientNegotiationProfile: ClientNegotiationProfile,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
): MinecraftClientConfigurationResult {
    outgoing.send(ConfigurationClientInformationPacket(minecraftClientNegotiationOptions.clientInformation))
    requestFlush()
    var configurationClientboundKnownPacksPacket: ConfigurationClientboundKnownPacksPacket? = null
    var featureFlagsPacket: FeatureFlagsPacket? = null
    var configurationUpdateTagsPacket: ConfigurationUpdateTagsPacket? = null
    val synchronizedRegistryPackets = mutableListOf<RegistryDataPacket>()
    val storedConfigurationCookies = linkedMapOf<Identifier, ByteString>()
    while (true) {
        when (val clientboundPacket = incoming.receive()) {
            is ConfigurationDisconnectPacket ->
                throw MinecraftClientException(
                    "Server rejected Configuration: ${clientboundPacket.reason}",
                )

            is ConfigurationCookieRequestPacket -> outgoing.send(
                ConfigurationCookieResponsePacket(
                    clientboundPacket.key,
                    minecraftClientNegotiationOptions.configurationCookies[clientboundPacket.key],
                ),
            )

            is ConfigurationPingPacket ->
                outgoing.send(ConfigurationPongPacket(clientboundPacket.id))

            is ConfigurationClientboundKnownPacksPacket -> {
                configurationClientboundKnownPacksPacket = clientboundPacket
                outgoing.send(
                    ConfigurationServerboundKnownPacksPacket(
                        clientboundPacket.knownPacks.filter(minecraftClientNegotiationOptions.acceptedKnownPacks::contains),
                    ),
                )
            }

            is FeatureFlagsPacket -> featureFlagsPacket = clientboundPacket

            is RegistryDataPacket -> {
                if (synchronizedRegistryPackets.any { it.registryId == clientboundPacket.registryId }) {
                    throw MinecraftClientException(
                        "Server sent duplicate registry ${clientboundPacket.registryId}",
                    )
                }
                synchronizedRegistryPackets += clientboundPacket
            }

            is ConfigurationUpdateTagsPacket -> configurationUpdateTagsPacket = clientboundPacket

            is ConfigurationStoreCookiePacket ->
                storedConfigurationCookies[clientboundPacket.key] = clientboundPacket.payload

            is ConfigurationAddResourcePackPacket -> outgoing.send(
                ConfigurationResourcePackResponsePacket(
                    clientboundPacket.uuid,
                    minecraftClientNegotiationOptions.resourcePackResult,
                ),
            )

            is CodeOfConductPacket -> {
                if (!minecraftClientNegotiationOptions.acceptCodeOfConduct) {
                    throw MinecraftClientException(
                        "Code of Conduct was not accepted",
                    )
                }
                outgoing.send(AcceptCodeOfConductPacket)
            }

            is ConfigurationTransferPacket ->
                throw MinecraftClientTransferException(clientboundPacket.host, clientboundPacket.port)

            is FinishConfigurationPacket -> {
                val baseProtocolRegistryContext = registryContextOrClientFailure {
                    minecraftClientNegotiationOptions.protocolData.resolveSynchronizedRegistryContext(
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        staticRegistrySchema = minecraftClientNegotiationOptions.staticRegistrySchema,
                    )
                }
                val protocolRegistryContext =
                    clientNegotiationProfile.resolveProtocolRegistryContext(baseProtocolRegistryContext)
                installProtocolRegistryContext(protocolRegistryContext)
                clientNegotiationProfile.preparePlay(this)
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

            else -> handleConfigurationExtension(
                clientNegotiationProfile,
                clientboundPacket,
                minecraftClientNegotiationOptions
            )
        }
        requestFlush()
    }
}

private suspend fun MinecraftClientConnection.awaitPlayLogin(
    synchronizedRegistryPackets: List<RegistryDataPacket>,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
): MinecraftClientPlayLogin {
    while (true) {
        when (val clientboundPacket = incoming.receive()) {
            is PlayLoginPacket -> {
                val minecraftDimensionContext = registryContextOrClientFailure {
                    val minecraftDimensionLayout = MinecraftDimensionLayout.from(
                        playLoginPacket = clientboundPacket,
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        protocolData = minecraftClientNegotiationOptions.protocolData,
                    )
                    MinecraftDimensionContext.create(
                        dimensionId = DimensionId.parse(clientboundPacket.spawnInfo.dimension.toString()),
                        minecraftDimensionLayout = minecraftDimensionLayout,
                        protocolRegistryContext = protocolRegistryContext,
                    )
                }
                installProtocolRegistryContext(minecraftDimensionContext.protocolRegistryContext)
                return MinecraftClientPlayLogin(clientboundPacket, minecraftDimensionContext)
            }

            else -> {
                if (clientboundPacket is UnknownPacket.Clientbound) {
                    handleUnknownQuery(clientboundPacket, minecraftClientNegotiationOptions)
                } else {
                    throw MinecraftClientException(
                        "Expected Play Login, received ${clientboundPacket::class.simpleName}",
                    )
                }
            }
        }
        requestFlush()
    }
}

private data class MinecraftClientPlayLogin(
    val playLoginPacket: PlayLoginPacket,
    val minecraftDimensionContext: MinecraftDimensionContext,
)

private suspend fun MinecraftClientConnection.handleLoginExtension(
    clientNegotiationProfile: ClientNegotiationProfile,
    clientboundPacket: ClientboundPacket,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
) {
    if (clientNegotiationProfile.handleLoginPacket(this, clientboundPacket)) return
    if (clientboundPacket is UnknownPacket.Clientbound) {
        handleUnknownQuery(clientboundPacket, minecraftClientNegotiationOptions)
        return
    }
    throw MinecraftClientException(
        "Unexpected Login packet ${clientboundPacket::class.simpleName}",
    )
}

private suspend fun MinecraftClientConnection.handleConfigurationExtension(
    clientNegotiationProfile: ClientNegotiationProfile,
    clientboundPacket: ClientboundPacket,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
) {
    if (clientNegotiationProfile.handleConfigurationPacket(this, clientboundPacket)) return
    if (clientboundPacket is UnknownPacket.Clientbound) {
        handleUnknownQuery(clientboundPacket, minecraftClientNegotiationOptions)
        return
    }
    if (
        clientboundPacket is ConfigurationClientboundPluginMessagePacket &&
        clientboundPacket.payload is CustomPayload.Brand
    ) {
        return
    }
    throw MinecraftClientException(
        "Unexpected Configuration packet ${clientboundPacket::class.simpleName}",
    )
}

private suspend fun MinecraftClientConnection.handleUnknownQuery(
    packet: UnknownPacket.Clientbound,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
) {
    val decision = minecraftClientNegotiationOptions.onUnhandledQuery?.invoke(packet)
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
    val loginQuery = packet.packetRoute as? PacketRoute.LoginQuery
        ?: return ClientNegotiationQueryResult.Pass
    return ClientNegotiationQueryResult.Respond(
        listOf(
            UnknownPacket.Serverbound(
                PacketRoute.LoginQuery(
                    packetDirection = PacketDirection.SERVERBOUND,
                    transactionId = loginQuery.transactionId,
                    channel = loginQuery.channel,
                    hasPayload = false,
                ),
                ByteString(byteArrayOf()),
            ),
        ),
    )
}

private suspend fun MinecraftClientConnection.answerEncryptionRequest(
    encryptionRequestPacket: EncryptionRequestPacket,
    minecraftIdentity: MinecraftIdentity,
    sessionHttpClient: HttpClient?,
) {
    val minecraftOnlineIdentity =
        if (encryptionRequestPacket.shouldAuthenticate) {
            minecraftIdentity as? MinecraftOnlineIdentity
                ?: throw MinecraftClientException(
                    "Server requested online authentication for an offline identity",
                )
        } else {
            null
        }
    val minecraftSessionApi =
        if (encryptionRequestPacket.shouldAuthenticate) {
            MinecraftSessionApi(
                sessionHttpClient ?: throw MinecraftClientException(
                    "Server requested online authentication, but no Session Server HttpClient was supplied",
                ),
            )
        } else {
            null
        }
    val minecraftClientKeyExchangeResult = MinecraftClientKeyExchange.respond(encryptionRequestPacket)
    if (minecraftOnlineIdentity != null && minecraftSessionApi != null) {
        minecraftSessionApi.join(minecraftOnlineIdentity, minecraftClientKeyExchangeResult.minecraftServerHash)
    }
    val sharedSecret = minecraftClientKeyExchangeResult.sharedSecret
    try {
        prepareOutboundEncryption(sharedSecret)
        outgoing.send(minecraftClientKeyExchangeResult.toEncryptionResponsePacket())
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
    handshakeNextState: HandshakeNextState,
): HandshakePacket = HandshakePacket(
    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
    serverAddress = serverAddress,
    serverPort = serverPort,
    nextState = handshakeNextState,
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
