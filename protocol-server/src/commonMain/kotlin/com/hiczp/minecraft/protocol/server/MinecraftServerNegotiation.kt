package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionContext
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.configuration.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.ServerNegotiationProfile
import com.hiczp.minecraft.protocol.session.VanillaServer
import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Facts produced when the peer completes Login and Configuration negotiation.
 * [clientboundLoginPacket] is the exact Play Login packet sent at the end of that successful transition, while
 * [minecraftDimensionContext] carries the selected dimension and active context. The connection's
 * [MinecraftServerConnection.packetCodecContext] remains authoritative if a later reconfiguration replaces it.
 */
data class MinecraftServerNegotiationResult(
    val gameProfile: GameProfile,
    val clientInformation: ClientInformation,
    val acceptedKnownPacks: List<KnownPack>,
    val clientboundLoginPacket: ClientboundLoginPacket,
    val minecraftDimensionContext: MinecraftDimensionContext,
    val negotiationProfileResult: NegotiationProfileResult,
    val transferred: Boolean = false,
)

/**
 * Runs the preset negotiation while exclusively borrowing [MinecraftServerConnection.incoming]
 * and [MinecraftServerConnection.outgoing]. Callers must guarantee that no
 * other coroutine receives or sends until this method returns; violating that
 * precondition is a programming error. This method runs sequentially in the
 * calling coroutine, does not launch a negotiation scope or select a
 * dispatcher, and uses no lock to arbitrate competing channel users.
 *
 * Returns null when a non-login connection (a status ping) was answered and closed completely
 * before returning; the caller has nothing left to do. A returned [MinecraftServerNegotiationResult]
 * means the open connection reached Play, contains the exact Play Login in
 * [MinecraftServerNegotiationResult.clientboundLoginPacket], and has the negotiated registry context installed;
 * further traffic and closing then belong to the caller. Negotiation failures raised by this library,
 * such as [MinecraftLoginRejectedException], leave the connection open so the caller may send the
 * rejection's failure packet explicitly before closing. Wire and pump failures surface as their
 * original exception with the connection already terminated; only closing remains.
 */
suspend fun MinecraftServerConnection.negotiate(
    serverNegotiationProfile: ServerNegotiationProfile = VanillaServer,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(),
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy = DefaultMinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult? {
    require(
        connectionState == ConnectionState.HANDSHAKE ||
                connectionState == ConnectionState.STATUS ||
                connectionState == ConnectionState.LOGIN,
    ) {
        "Negotiation must begin before Configuration"
    }
    serverNegotiationProfile.begin(this)
    val clientIntentionPacket = requirePacket<ClientIntentionPacket>(incoming.receive())
    serverNegotiationProfile.acceptHandshake(clientIntentionPacket)
    return when (connectionState) {
        ConnectionState.STATUS -> {
            if (!minecraftServerNegotiationOptions.statusEnabled) {
                throw MinecraftServerException(
                    "Status requests are disabled by configuration",
                )
            }
            handleStatus(minecraftServerNegotiationOptions, minecraftServerNegotiationPolicy)
            null
        }

        ConnectionState.LOGIN -> {
            val transferred = clientIntentionPacket.intention == ClientIntent.TRANSFER
            if (transferred && !minecraftServerNegotiationOptions.acceptsTransfers) {
                throw MinecraftLoginRejectedException(
                    reason = JsonTextComponent(
                        buildJsonObject { put("translate", "multiplayer.disconnect.transfers_disabled") }.toString(),
                    ),
                    message = "Transfer connections are disabled by configuration",
                )
            }
            val actualVersion = clientIntentionPacket.protocolVersion
            val expectedVersion = MinecraftProtocol.PROTOCOL_VERSION
            if (actualVersion != expectedVersion) {
                val message = "Unsupported protocol version $actualVersion; expected $expectedVersion"
                throw MinecraftLoginRejectedException(
                    reason = JsonTextComponent.literal(message),
                    message = message,
                )
            }
            handleLogin(
                transferred,
                serverNegotiationProfile,
                minecraftServerNegotiationOptions,
                minecraftServerNegotiationPolicy
            )
        }

        else -> throw MinecraftServerException(
            "Handshake ${clientIntentionPacket.intention} entered unsupported state $connectionState",
        )
    }
}

private suspend fun MinecraftServerConnection.handleStatus(
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
) {
    requirePacket<ServerboundStatusRequestPacket>(incoming.receive())
    outgoing.send(
        ClientboundStatusResponsePacket(
            minecraftServerNegotiationPolicy.serverStatus(
                minecraftServerNegotiationOptions,
                minecraftServerAuthentication is MinecraftServerAuthentication.Online,
            ),
        ),
    )
    requestFlush()
    val serverboundPingRequestPacket = requirePacket<ServerboundPingRequestPacket>(incoming.receive())
    outgoing.send(ClientboundPongResponsePacket(serverboundPingRequestPacket.time))
    outgoing.close()
    awaitClosed()
}

private suspend fun MinecraftServerConnection.handleLogin(
    transferred: Boolean,
    serverNegotiationProfile: ServerNegotiationProfile,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult {
    val serverboundHelloPacket =
        awaitLoginPacket<ServerboundHelloPacket>(serverNegotiationProfile, minecraftServerNegotiationPolicy)
    val gameProfile =
        authenticate(
            serverboundHelloPacket,
            serverNegotiationProfile,
            minecraftServerNegotiationOptions,
            minecraftServerNegotiationPolicy
        )
    val rejection = minecraftServerNegotiationPolicy.profileRejection(
        gameProfile,
        transferred,
        minecraftServerNegotiationOptions,
    )
    if (rejection != null) {
        throw MinecraftLoginRejectedException(
            reason = rejection,
            message = "Profile ${gameProfile.name} was rejected: ${rejection.json}",
        )
    }

    serverNegotiationProfile.negotiateLogin(this)
    minecraftServerNegotiationOptions.compressionThreshold?.let { threshold ->
        outgoing.send(ClientboundLoginCompressionPacket(threshold))
    }
    outgoing.send(ClientboundLoginFinishedPacket(gameProfile, minecraftServerNegotiationOptions.sessionId))
    awaitLoginPacket<ServerboundLoginAcknowledgedPacket>(serverNegotiationProfile, minecraftServerNegotiationPolicy)
    awaitState(ConnectionState.CONFIGURATION)
    enableKeepAlive()

    val clientInformation =
        awaitConfigurationPacket<ServerboundClientInformationPacket>(
            serverNegotiationProfile,
            minecraftServerNegotiationPolicy,
        ).information
    serverNegotiationProfile.negotiateConfigurationStart(this)
    outgoing.send(ClientboundUpdateEnabledFeaturesPacket(minecraftServerNegotiationOptions.configurationData.enabledFeatureFlags))
    serverNegotiationProfile.negotiateEarlyConfiguration(this)
    outgoing.send(
        ClientboundSelectKnownPacks(
            minecraftServerNegotiationOptions.configurationData.offeredKnownPacks,
        ),
    )
    val acceptedKnownPacks =
        awaitConfigurationPacket<ServerboundSelectKnownPacks>(
            serverNegotiationProfile,
            minecraftServerNegotiationPolicy,
        ).knownPacks
    val synchronizedRegistryPackets =
        minecraftServerNegotiationOptions.configurationData.synchronizedRegistryPackets(acceptedKnownPacks)
    synchronizedRegistryPackets.forEach { clientboundRegistryDataPacket -> outgoing.send(clientboundRegistryDataPacket) }
    outgoing.send(ClientboundUpdateTagsPacket(minecraftServerNegotiationOptions.configurationData.registryTags))

    serverNegotiationProfile.negotiateConfiguration(this)
    val extensionPackets = minecraftServerNegotiationPolicy.configurationPackets(
        gameProfile,
        clientInformation,
        acceptedKnownPacks,
        transferred,
        minecraftServerNegotiationOptions,
    )
    val extensionTasks = minecraftServerNegotiationPolicy.configurationTasks(
        gameProfile,
        clientInformation,
        acceptedKnownPacks,
        transferred,
        minecraftServerNegotiationOptions,
    )
    extensionPackets.forEach { outgoing.send(it) }
    extensionTasks.forEach { minecraftServerNegotiationTask ->
        minecraftServerNegotiationTask.clientboundPackets.forEach { outgoing.send(it) }
        awaitConfigurationTask(
            minecraftServerNegotiationTask,
            serverNegotiationProfile,
            minecraftServerNegotiationPolicy
        )
    }

    val onlineMode = minecraftServerAuthentication is MinecraftServerAuthentication.Online
    val clientboundLoginPacket = minecraftServerNegotiationPolicy.createClientboundLoginPacket(
        gameProfile,
        clientInformation,
        transferred,
        onlineMode,
        minecraftServerNegotiationOptions,
    )
    val minecraftDimensionLayout = try {
        require(clientboundLoginPacket.commonPlayerSpawnInfo.dimension in clientboundLoginPacket.levels) {
            val dimensionId = clientboundLoginPacket.commonPlayerSpawnInfo.dimension
            "Play Login selected dimension $dimensionId, but it is absent from the advertised levels"
        }
        MinecraftDimensionLayout.from(
            dimensionTypeRawId = clientboundLoginPacket.commonPlayerSpawnInfo.dimensionTypeId,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            configurationData = minecraftServerNegotiationOptions.configurationData,
        )
    } catch (failure: IllegalArgumentException) {
        throw MinecraftServerException(
            failure.message ?: "Invalid Play Login or registry context",
            failure,
        )
    }
    val basePacketCodecContext = try {
        minecraftServerNegotiationOptions.configurationData
            .resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
    } catch (failure: IllegalArgumentException) {
        throw MinecraftServerException(
            failure.message ?: "Invalid Play Login or registry context",
            failure,
        )
    }
    val packetCodecContext = serverNegotiationProfile.resolvePacketCodecContext(basePacketCodecContext)
    val minecraftDimensionContext = try {
        MinecraftDimensionContext(
            dimensionId = DimensionId.parse(clientboundLoginPacket.commonPlayerSpawnInfo.dimension.toString()),
            minecraftDimensionLayout = minecraftDimensionLayout,
            packetCodecContext = packetCodecContext,
        )
    } catch (failure: IllegalArgumentException) {
        throw MinecraftServerException(
            failure.message ?: "Invalid Play Login or registry context",
            failure,
        )
    }
    installPacketCodecContext(minecraftDimensionContext.packetCodecContext)

    outgoing.send(ClientboundFinishConfigurationPacket)
    awaitConfigurationPacket<ServerboundFinishConfigurationPacket>(
        serverNegotiationProfile,
        minecraftServerNegotiationPolicy,
    )
    disableKeepAlive()
    awaitState(ConnectionState.PLAY)
    enableKeepAlive()
    serverNegotiationProfile.preparePlay(this)
    outgoing.send(clientboundLoginPacket)
    requestFlush()
    val negotiationProfileResult = serverNegotiationProfile.complete(this)
    return MinecraftServerNegotiationResult(
        gameProfile = gameProfile,
        clientInformation = clientInformation,
        acceptedKnownPacks = acceptedKnownPacks,
        clientboundLoginPacket = clientboundLoginPacket,
        minecraftDimensionContext = minecraftDimensionContext,
        negotiationProfileResult = negotiationProfileResult,
        transferred = transferred,
    )
}

private suspend fun MinecraftServerConnection.authenticate(
    serverboundHelloPacket: ServerboundHelloPacket,
    serverNegotiationProfile: ServerNegotiationProfile,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
): GameProfile = when (val configured = minecraftServerAuthentication) {
    MinecraftServerAuthentication.Offline ->
        MinecraftOfflineIdentity(serverboundHelloPacket.name).toGameProfile()

    is MinecraftServerAuthentication.Online -> {
        val minecraftServerChallenge = configured.minecraftServerKeyPair.createChallenge(
            shouldAuthenticate = true,
        )
        outgoing.send(minecraftServerChallenge.toClientboundHelloPacket())
        val serverboundKeyPacket = awaitLoginPacket<ServerboundKeyPacket>(
            serverNegotiationProfile,
            minecraftServerNegotiationPolicy,
        )
        val minecraftServerKeyExchangeResult = minecraftServerChallenge.accept(serverboundKeyPacket)
        val sharedSecret = minecraftServerKeyExchangeResult.sharedSecret
        try {
            enableEncryption(sharedSecret)
            val minecraftSessionHasJoinedResponse = MinecraftSessionApi(
                configured.sessionHttpClient,
            ).hasJoined(
                MinecraftSessionHasJoinedRequest(
                    username = serverboundHelloPacket.name,
                    serverId = minecraftServerKeyExchangeResult.minecraftServerHash.value,
                    ip =
                        if (minecraftServerNegotiationOptions.preventProxyConnections) {
                            clientIpAddress ?: throw MinecraftServerException(
                                "Proxy prevention requires the client IP address",
                            )
                        } else {
                            null
                        },
                ),
            ) ?: throw MinecraftServerException(
                "Session server did not verify ${serverboundHelloPacket.name}",
            )
            minecraftSessionHasJoinedResponse.toGameProfile(serverboundHelloPacket.name)
        } finally {
            sharedSecret.fill(0)
        }
    }
}

private suspend inline fun <reified T : ServerboundPacket>
        MinecraftServerConnection.awaitLoginPacket(
    serverNegotiationProfile: ServerNegotiationProfile,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
): T {
    while (true) {
        requestFlush()
        val packet = incoming.receive()
        if (packet is T) return packet
        if (serverNegotiationProfile.handleLoginPacket(this, packet)) continue
        handleUnexpected(packet, minecraftServerNegotiationPolicy)
    }
}

private suspend inline fun <reified T : ServerboundPacket>
        MinecraftServerConnection.awaitConfigurationPacket(
    serverNegotiationProfile: ServerNegotiationProfile,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
): T {
    while (true) {
        requestFlush()
        val packet = incoming.receive()
        if (packet is T) return packet
        if (serverNegotiationProfile.handleConfigurationPacket(this, packet)) continue
        handleUnexpected(packet, minecraftServerNegotiationPolicy)
    }
}

private suspend fun MinecraftServerConnection.awaitConfigurationTask(
    minecraftServerNegotiationTask: MinecraftServerNegotiationTask,
    serverNegotiationProfile: ServerNegotiationProfile,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
) {
    while (true) {
        requestFlush()
        val serverboundPacket = incoming.receive()
        if (minecraftServerNegotiationTask.isComplete(serverboundPacket)) return
        if (serverNegotiationProfile.handleConfigurationPacket(this, serverboundPacket)) continue
        handleUnexpected(serverboundPacket, minecraftServerNegotiationPolicy)
    }
}

private suspend fun MinecraftServerConnection.handleUnexpected(
    serverboundPacket: ServerboundPacket,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
) {
    if (
        serverboundPacket is ServerboundCustomPayloadPacket &&
        serverboundPacket.payload is CustomPayload.Brand
    ) {
        return
    }
    if (serverboundPacket !is UnknownPacket.Serverbound) {
        throw MinecraftServerException(
            "Unexpected negotiation packet ${serverboundPacket::class.simpleName}",
        )
    }
    when (val serverNegotiationQueryResult = minecraftServerNegotiationPolicy.onUnhandledQuery(serverboundPacket)) {
        ServerNegotiationQueryResult.Pass -> Unit
        is ServerNegotiationQueryResult.Reject ->
            throw MinecraftServerException(serverNegotiationQueryResult.reason)

        is ServerNegotiationQueryResult.Respond ->
            serverNegotiationQueryResult.clientboundPackets.forEach { outgoing.send(it) }
    }
}

private inline fun <reified T : ServerboundPacket> requirePacket(
    serverboundPacket: ServerboundPacket,
): T = serverboundPacket as? T ?: throw MinecraftServerException(
    "Expected ${T::class.simpleName}, received ${serverboundPacket::class.simpleName}",
)

/** Invalid server-side protocol orchestration or peer behavior. */
open class MinecraftServerException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * A preset Login policy rejection. The connection stays open and this class
 * never sends [ClientboundLoginDisconnectPacket]; callers may send it explicitly.
 */
class MinecraftLoginRejectedException(
    val reason: JsonTextComponent,
    message: String,
) : MinecraftServerException(message) {
    /** Ready-to-send default reply; the library never sends it automatically. */
    val failurePacket: ClientboundLoginDisconnectPacket = ClientboundLoginDisconnectPacket(reason)
}
