package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.datapack.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.datapack.withPlayLoginDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Facts produced when the peer completes Login and Configuration negotiation.
 * [playLoginPacket] is the exact Play Login packet sent at the end of that successful transition, while
 * [MinecraftServerConnection.protocolRegistryContext] remains the connection's authoritative registry
 * context.
 */
data class MinecraftServerNegotiationResult(
    val gameProfile: GameProfile,
    val clientInformation: ClientInformation,
    val acceptedKnownPacks: List<KnownPack>,
    val playLoginPacket: PlayLoginPacket,
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
 * [MinecraftServerNegotiationResult.playLoginPacket], and has the negotiated registry context installed;
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
    val handshakePacket = requirePacket<HandshakePacket>(incoming.receive())
    serverNegotiationProfile.acceptHandshake(handshakePacket)
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
            val transferred = handshakePacket.nextState == HandshakeNextState.TRANSFER
            if (transferred && !minecraftServerNegotiationOptions.acceptsTransfers) {
                throw MinecraftLoginRejectedException(
                    reason = JsonTextComponent(
                        buildJsonObject { put("translate", "multiplayer.disconnect.transfers_disabled") }.toString(),
                    ),
                    message = "Transfer connections are disabled by configuration",
                )
            }
            val actualVersion = handshakePacket.protocolVersion
            val expectedVersion = minecraftServerNegotiationOptions.protocolData.protocolVersion
            if (actualVersion != expectedVersion) {
                val message = "Unsupported protocol version $actualVersion; expected $expectedVersion"
                throw MinecraftLoginRejectedException(
                    reason = JsonTextComponent(buildJsonObject { put("text", message) }.toString()),
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
            "Handshake ${handshakePacket.nextState} entered unsupported state $connectionState",
        )
    }
}

private suspend fun MinecraftServerConnection.handleStatus(
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
) {
    requirePacket<StatusRequestPacket>(incoming.receive())
    outgoing.send(
        StatusResponsePacket(
            minecraftServerNegotiationPolicy.statusJson(
                minecraftServerNegotiationOptions,
                minecraftServerAuthentication is MinecraftServerAuthentication.Online,
            ),
        ),
    )
    requestFlush()
    val statusPingRequestPacket = requirePacket<StatusPingRequestPacket>(incoming.receive())
    outgoing.send(StatusPongResponsePacket(statusPingRequestPacket.timestamp))
    outgoing.close()
    awaitClosed()
}

private suspend fun MinecraftServerConnection.handleLogin(
    transferred: Boolean,
    serverNegotiationProfile: ServerNegotiationProfile,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult {
    val loginStartPacket =
        awaitLoginPacket<LoginStartPacket>(serverNegotiationProfile, minecraftServerNegotiationPolicy)
    val gameProfile =
        authenticate(
            loginStartPacket,
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
        outgoing.send(SetCompressionPacket(threshold))
    }
    outgoing.send(LoginSuccessPacket(gameProfile, minecraftServerNegotiationOptions.sessionId))
    awaitLoginPacket<LoginAcknowledgedPacket>(serverNegotiationProfile, minecraftServerNegotiationPolicy)
    awaitState(ConnectionState.CONFIGURATION)
    enableConfigurationKeepAlive()

    val clientInformation =
        awaitConfigurationPacket<ConfigurationClientInformationPacket>(
            serverNegotiationProfile,
            minecraftServerNegotiationPolicy,
        ).information
    serverNegotiationProfile.negotiateConfigurationStart(this)
    outgoing.send(FeatureFlagsPacket(minecraftServerNegotiationOptions.protocolData.enabledFeatureFlags))
    serverNegotiationProfile.negotiateEarlyConfiguration(this)
    outgoing.send(
        ConfigurationClientboundKnownPacksPacket(
            minecraftServerNegotiationOptions.protocolData.offeredKnownPacks,
        ),
    )
    val acceptedKnownPacks =
        awaitConfigurationPacket<ConfigurationServerboundKnownPacksPacket>(
            serverNegotiationProfile,
            minecraftServerNegotiationPolicy,
        ).knownPacks
    val synchronizedRegistryPackets =
        minecraftServerNegotiationOptions.protocolData.synchronizedRegistryPackets(acceptedKnownPacks)
    synchronizedRegistryPackets.forEach { registryDataPacket -> outgoing.send(registryDataPacket) }
    outgoing.send(ConfigurationUpdateTagsPacket(minecraftServerNegotiationOptions.protocolData.registryTags))

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
    val playLoginPacket = minecraftServerNegotiationPolicy.createPlayLoginPacket(
        gameProfile,
        clientInformation,
        transferred,
        onlineMode,
        minecraftServerNegotiationOptions,
    )
    val baseProtocolRegistryContext = try {
        minecraftServerNegotiationOptions.protocolData
            .resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
            .withPlayLoginDimensionLayout(
                playLoginPacket,
                synchronizedRegistryPackets,
                minecraftServerNegotiationOptions.protocolData
            )
    } catch (failure: IllegalArgumentException) {
        throw MinecraftServerException(
            failure.message ?: "Invalid Play Login or registry context",
            failure,
        )
    }
    val protocolRegistryContext = serverNegotiationProfile.resolveProtocolRegistryContext(baseProtocolRegistryContext)
    installProtocolRegistryContext(protocolRegistryContext)

    outgoing.send(FinishConfigurationPacket)
    awaitConfigurationPacket<AcknowledgeFinishConfigurationPacket>(
        serverNegotiationProfile,
        minecraftServerNegotiationPolicy,
    )
    disableKeepAlive()
    awaitState(ConnectionState.PLAY)
    enablePlayKeepAlive()
    serverNegotiationProfile.preparePlay(this)
    outgoing.send(playLoginPacket)
    requestFlush()
    val negotiationProfileResult = serverNegotiationProfile.complete(this)
    return MinecraftServerNegotiationResult(
        gameProfile = gameProfile,
        clientInformation = clientInformation,
        acceptedKnownPacks = acceptedKnownPacks,
        playLoginPacket = playLoginPacket,
        negotiationProfileResult = negotiationProfileResult,
        transferred = transferred,
    )
}

private suspend fun MinecraftServerConnection.authenticate(
    loginStartPacket: LoginStartPacket,
    serverNegotiationProfile: ServerNegotiationProfile,
    minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    minecraftServerNegotiationPolicy: MinecraftServerNegotiationPolicy,
): GameProfile = when (val configured = minecraftServerAuthentication) {
    MinecraftServerAuthentication.Offline ->
        MinecraftOfflineIdentity(loginStartPacket.name).toGameProfile()

    is MinecraftServerAuthentication.Online -> {
        val minecraftServerChallenge = configured.minecraftServerKeyPair.createChallenge(
            shouldAuthenticate = true,
        )
        outgoing.send(minecraftServerChallenge.toEncryptionRequestPacket())
        val encryptionResponsePacket = awaitLoginPacket<EncryptionResponsePacket>(
            serverNegotiationProfile,
            minecraftServerNegotiationPolicy,
        )
        val minecraftServerKeyExchangeResult = minecraftServerChallenge.accept(encryptionResponsePacket)
        val sharedSecret = minecraftServerKeyExchangeResult.sharedSecret
        try {
            enableEncryption(sharedSecret)
            val minecraftSessionHasJoinedResponse = MinecraftSessionApi(
                configured.sessionHttpClient,
            ).hasJoined(
                MinecraftSessionHasJoinedRequest(
                    username = loginStartPacket.name,
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
                "Session server did not verify ${loginStartPacket.name}",
            )
            minecraftSessionHasJoinedResponse.toGameProfile(loginStartPacket.name)
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
        serverboundPacket is ConfigurationServerboundPluginMessagePacket &&
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
 * never sends [LoginDisconnectPacket]; callers may send it explicitly.
 */
class MinecraftLoginRejectedException(
    val reason: JsonTextComponent,
    message: String,
) : MinecraftServerException(message) {
    /** Ready-to-send default reply; the library never sends it automatically. */
    val failurePacket: LoginDisconnectPacket = LoginDisconnectPacket(reason)
}
