package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.configuration.*
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.ClientNegotiationProfile
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.VanillaClient
import com.hiczp.minecraft.world.format.ChunkLayout
import com.hiczp.minecraft.world.format.DimensionId
import io.ktor.client.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.uuid.Uuid

/** The Status response and following Pong received by [queryStatus] on one connection. */
data class MinecraftStatusExchange(
    val clientboundStatusResponsePacket: ClientboundStatusResponsePacket,
    val clientboundPongResponsePacket: ClientboundPongResponsePacket,
)

private data class MinecraftClientConfigurationResult(
    val dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    val storedConfigurationCookies: Map<Identifier, ByteString>,
)

/**
 * Captured Login and Configuration facts for the initial Play dimension.
 *
 * [minecraftDimensionContext] retains this negotiation's resolved registry context even if the connection later
 * replaces its context or closes. Use it to construct world codecs and [resolveClientRegistryView] to inspect tags.
 * The result performs no I/O and does not contain initial-world packets; those remain on the connection's incoming
 * channel. The application supplies semantic block/biome defaults when constructing its Chunk context.
 */
data class MinecraftClientNegotiationResult(
    val clientboundLoginFinishedPacket: ClientboundLoginFinishedPacket,
    val dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    val storedConfigurationCookies: Map<Identifier, ByteString>,
    val clientboundLoginPacket: ClientboundLoginPacket,
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
data class MinecraftClientNegotiationOptions(
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
    val configurationData: ConfigurationData = VanillaConfigurationData,
    val loginCookies: Map<Identifier, ByteString> = emptyMap(),
    val configurationCookies: Map<Identifier, ByteString> = emptyMap(),
    val acceptedKnownPacks: Set<KnownPack> = configurationData.offeredKnownPacks.toSet(),
    val acceptCodeOfConduct: Boolean = true,
    /**
     * Downloads/applies an offered pack using application code and returns its terminal response. The second argument
     * reports ACCEPTED or DOWNLOADED immediately. The default declines; the library never downloads or applies a pack.
     * Each request runs in a child coroutine while negotiation keeps receiving packets. Exceptions fail negotiation;
     * cancellation, replacement of this ID, or a Pop cancels the callback. Do not access connection channels here.
     */
    val onResourcePack: suspend (
        ClientboundResourcePackPushPacket,
        suspend (ServerboundResourcePackPacket.Action) -> Unit,
    ) -> ServerboundResourcePackPacket.Action = { _, _ -> ServerboundResourcePackPacket.Action.DECLINED },
    /** Removes an applied pack, or all server packs for a null ID, after pending callbacks have been cancelled. */
    val onResourcePackPop: suspend (ClientboundResourcePackPopPacket) -> Unit = {},
    val staticRegistrySchema: StaticRegistrySchema = configurationData.staticRegistrySchema,
    val onUnhandledQuery: (suspend (UnknownPacket.Clientbound) -> ClientNegotiationQueryResult)? = null,
)

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
    outgoing.send(handshake(ClientIntent.STATUS))
    outgoing.send(ServerboundStatusRequestPacket)
    requestFlush()
    val clientboundStatusResponsePacket = incoming.receive()
    if (clientboundStatusResponsePacket !is ClientboundStatusResponsePacket) {
        throw MinecraftClientException(
            "Expected Status Response, received ${clientboundStatusResponsePacket::class.simpleName}",
        )
    }
    outgoing.send(ServerboundPingRequestPacket(pingPayload))
    requestFlush()
    val clientboundPongResponsePacket = incoming.receive()
    if (clientboundPongResponsePacket !is ClientboundPongResponsePacket) {
        throw MinecraftClientException(
            "Expected Status Pong, received ${clientboundPongResponsePacket::class.simpleName}",
        )
    }
    return MinecraftStatusExchange(clientboundStatusResponsePacket, clientboundPongResponsePacket)
}

/**
 * Runs the preset negotiation while exclusively borrowing [incoming] and
 * [outgoing]. Callers must guarantee that no other coroutine receives or sends
 * until this method returns; violating that precondition is a programming
 * error. The receive loop runs in the calling coroutine; resource-pack callbacks run in structured child coroutines
 * so downloads do not block Configuration traffic. This method selects no dispatcher and uses only this connection's
 * public API. Outstanding resource-pack callbacks finish before acknowledging Finish Configuration and are cancelled
 * when negotiation fails or is cancelled.
 *
 * Returns after consuming the first [ClientboundLoginPacket], before initial-world reception. Online identities
 * require the caller-owned [sessionHttpClient] for the Session Server join; this method never closes that client.
 *
 * On return the open connection has reached Play with the negotiated registry context installed
 * in [MinecraftClientConnection.packetCodecContext]; further traffic and closing then belong to the
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
    val clientboundLoginFinishedPacket =
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
        clientboundLoginFinishedPacket = clientboundLoginFinishedPacket,
        dataPackConfigurationSnapshot = minecraftClientConfigurationResult.dataPackConfigurationSnapshot,
        storedConfigurationCookies = minecraftClientConfigurationResult.storedConfigurationCookies,
        clientboundLoginPacket = minecraftClientPlayLogin.clientboundLoginPacket,
        minecraftDimensionContext = minecraftClientPlayLogin.minecraftDimensionContext,
        negotiationProfileResult = negotiationProfileResult,
    )
}

private suspend fun MinecraftClientConnection.negotiateLogin(
    minecraftIdentity: MinecraftIdentity,
    sessionHttpClient: HttpClient?,
    clientNegotiationProfile: ClientNegotiationProfile,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
): ClientboundLoginFinishedPacket {
    clientNegotiationProfile.begin(this)
    outgoing.send(
        clientNegotiationProfile.prepareHandshake(handshake(ClientIntent.LOGIN)),
    )
    outgoing.send(ServerboundHelloPacket(minecraftIdentity.name, minecraftIdentity.id))
    requestFlush()

    while (true) {
        when (val clientboundPacket = incoming.receive()) {
            is ClientboundLoginDisconnectPacket ->
                throw MinecraftClientException(
                    "Server rejected Login: ${clientboundPacket.reason.json}",
                )

            is ClientboundHelloPacket -> answerEncryptionRequest(
                clientboundPacket,
                minecraftIdentity,
                sessionHttpClient,
            )

            is ClientboundCookieRequestPacket -> outgoing.send(
                ServerboundCookieResponsePacket(
                    clientboundPacket.key,
                    minecraftClientNegotiationOptions.loginCookies[clientboundPacket.key],
                ),
            )

            is ClientboundLoginCompressionPacket -> Unit

            is ClientboundLoginFinishedPacket -> {
                outgoing.send(ServerboundLoginAcknowledgedPacket)
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
): MinecraftClientConfigurationResult = coroutineScope {
    outgoing.send(ServerboundClientInformationPacket(minecraftClientNegotiationOptions.clientInformation))
    requestFlush()
    var clientboundSelectKnownPacks: ClientboundSelectKnownPacks? = null
    var clientboundUpdateEnabledFeaturesPacket: ClientboundUpdateEnabledFeaturesPacket? = null
    var clientboundUpdateTagsPacket: ClientboundUpdateTagsPacket? = null
    val synchronizedRegistryPackets = mutableListOf<ClientboundRegistryDataPacket>()
    val storedConfigurationCookies = linkedMapOf<Identifier, ByteString>()
    val resourcePackTasks = mutableMapOf<Uuid, Deferred<ServerboundResourcePackPacket.Action>>()
    suspend fun discardResourcePack(id: Uuid) {
        val task = resourcePackTasks.remove(id) ?: return
        task.cancelAndJoin()
        outgoing.send(ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.DISCARDED))
    }

    var configurationResult: MinecraftClientConfigurationResult? = null
    while (configurationResult == null) {
        val clientboundPacket = select<ClientboundPacket?> {
            incoming.onReceive { it }
            resourcePackTasks.forEach { (id, task) ->
                task.onAwait { action ->
                    resourcePackTasks.remove(id)
                    outgoing.send(ServerboundResourcePackPacket(id, action))
                    requestFlush()
                    null
                }
            }
        } ?: continue
        when (clientboundPacket) {
            is ClientboundDisconnectPacket ->
                throw MinecraftClientException(
                    "Server rejected Configuration: ${clientboundPacket.reason}",
                )

            is ClientboundCookieRequestPacket -> outgoing.send(
                ServerboundCookieResponsePacket(
                    clientboundPacket.key,
                    minecraftClientNegotiationOptions.configurationCookies[clientboundPacket.key],
                ),
            )

            is ClientboundPingPacket ->
                outgoing.send(ServerboundPongPacket(clientboundPacket.id))

            is ClientboundSelectKnownPacks -> {
                clientboundSelectKnownPacks = clientboundPacket
                outgoing.send(
                    ServerboundSelectKnownPacks(
                        clientboundPacket.knownPacks.filter(minecraftClientNegotiationOptions.acceptedKnownPacks::contains),
                    ),
                )
            }

            is ClientboundUpdateEnabledFeaturesPacket -> clientboundUpdateEnabledFeaturesPacket = clientboundPacket

            is ClientboundRegistryDataPacket -> synchronizedRegistryPackets += clientboundPacket

            is ClientboundUpdateTagsPacket -> clientboundUpdateTagsPacket = clientboundPacket

            is ClientboundStoreCookiePacket ->
                storedConfigurationCookies[clientboundPacket.key] = clientboundPacket.payload

            is ClientboundResourcePackPushPacket -> {
                discardResourcePack(clientboundPacket.id)
                resourcePackTasks[clientboundPacket.id] = async {
                    val action = minecraftClientNegotiationOptions.onResourcePack(clientboundPacket) { progress ->
                        require(!progress.isTerminal()) { "Resource-pack progress must be ACCEPTED or DOWNLOADED" }
                        outgoing.send(ServerboundResourcePackPacket(clientboundPacket.id, progress))
                        requestFlush()
                    }
                    require(action.isTerminal()) { "A resource-pack handler must return a terminal response" }
                    action
                }
            }

            is ClientboundResourcePackPopPacket -> {
                val ids = clientboundPacket.id?.let(::listOf) ?: resourcePackTasks.keys.toList()
                ids.forEach { discardResourcePack(it) }
                minecraftClientNegotiationOptions.onResourcePackPop(clientboundPacket)
            }

            is ClientboundCodeOfConductPacket -> {
                if (!minecraftClientNegotiationOptions.acceptCodeOfConduct) {
                    throw MinecraftClientException(
                        "Code of Conduct was not accepted",
                    )
                }
                outgoing.send(ServerboundAcceptCodeOfConductPacket)
            }

            is ClientboundTransferPacket ->
                throw MinecraftClientTransferException(clientboundPacket.host, clientboundPacket.port)

            is ClientboundFinishConfigurationPacket -> {
                resourcePackTasks.forEach { (id, task) ->
                    outgoing.send(ServerboundResourcePackPacket(id, task.await()))
                }
                resourcePackTasks.clear()
                val basePacketCodecContext = registryContextOrClientFailure {
                    minecraftClientNegotiationOptions.configurationData.resolveSynchronizedRegistryContext(
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        staticRegistrySchema = minecraftClientNegotiationOptions.staticRegistrySchema,
                    )
                }
                val packetCodecContext =
                    clientNegotiationProfile.resolvePacketCodecContext(basePacketCodecContext)
                installPacketCodecContext(packetCodecContext)
                clientNegotiationProfile.preparePlay(this@negotiateConfiguration)
                outgoing.send(ServerboundFinishConfigurationPacket)
                requestFlush()
                awaitState(ConnectionState.PLAY)
                configurationResult = MinecraftClientConfigurationResult(
                    dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
                        offeredKnownPacks = clientboundSelectKnownPacks?.knownPacks.orEmpty(),
                        enabledFeatureFlags = clientboundUpdateEnabledFeaturesPacket?.features.orEmpty(),
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        registryTags = clientboundUpdateTagsPacket?.tags.orEmpty(),
                    ),
                    storedConfigurationCookies = storedConfigurationCookies.toMap(),
                )
            }

            is ClientboundCustomReportDetailsPacket,
            is ClientboundServerLinksPacket,
            ClientboundClearDialogPacket,
            is ClientboundShowDialogPacket,
            ClientboundResetChatPacket,
                -> Unit

            else -> handleConfigurationExtension(
                clientNegotiationProfile,
                clientboundPacket,
                minecraftClientNegotiationOptions
            )
        }
        requestFlush()
    }
    configurationResult
}

private suspend fun MinecraftClientConnection.awaitPlayLogin(
    synchronizedRegistryPackets: List<ClientboundRegistryDataPacket>,
    minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
): MinecraftClientPlayLogin {
    while (true) {
        when (val clientboundPacket = incoming.receive()) {
            is ClientboundLoginPacket -> {
                val minecraftDimensionContext = registryContextOrClientFailure {
                    val minecraftDimensionLayout = MinecraftDimensionLayout.from(
                        dimensionTypeRawId = clientboundPacket.commonPlayerSpawnInfo.dimensionTypeId,
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        configurationData = minecraftClientNegotiationOptions.configurationData,
                    )
                    MinecraftDimensionContext(
                        dimensionId = DimensionId.parse(clientboundPacket.commonPlayerSpawnInfo.dimension.toString()),
                        minecraftDimensionLayout = minecraftDimensionLayout,
                        packetCodecContext = packetCodecContext,
                    )
                }
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
    val clientboundLoginPacket: ClientboundLoginPacket,
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
        clientboundPacket is ClientboundCustomPayloadPacket &&
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
    clientboundHelloPacket: ClientboundHelloPacket,
    minecraftIdentity: MinecraftIdentity,
    sessionHttpClient: HttpClient?,
) {
    val minecraftOnlineIdentity =
        if (clientboundHelloPacket.shouldAuthenticate) {
            minecraftIdentity as? MinecraftOnlineIdentity
                ?: throw MinecraftClientException(
                    "Server requested online authentication for an offline identity",
                )
        } else {
            null
        }
    val minecraftSessionApi =
        if (clientboundHelloPacket.shouldAuthenticate) {
            MinecraftSessionApi(
                sessionHttpClient ?: throw MinecraftClientException(
                    "Server requested online authentication, but no Session Server HttpClient was supplied",
                ),
            )
        } else {
            null
        }
    val minecraftClientKeyExchangeResult = MinecraftClientKeyExchange.respond(clientboundHelloPacket)
    if (minecraftOnlineIdentity != null && minecraftSessionApi != null) {
        minecraftSessionApi.join(minecraftOnlineIdentity, minecraftClientKeyExchangeResult.minecraftServerHash)
    }
    val sharedSecret = minecraftClientKeyExchangeResult.sharedSecret
    try {
        prepareOutboundEncryption(sharedSecret)
        outgoing.send(minecraftClientKeyExchangeResult.toServerboundKeyPacket())
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
    handshakeNextState: ClientIntent,
): ClientIntentionPacket = ClientIntentionPacket(
    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
    hostName = serverAddress,
    port = serverPort,
    intention = handshakeNextState,
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
