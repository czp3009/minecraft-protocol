package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.datapack.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.datapack.withPlayLoginDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.ServerNegotiationProfile
import com.hiczp.minecraft.protocol.session.VanillaServer
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
    profile: ServerNegotiationProfile = VanillaServer,
    options: MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(),
    policy: MinecraftServerNegotiationPolicy = DefaultMinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult? {
    require(
        state == ConnectionState.HANDSHAKE ||
                state == ConnectionState.STATUS ||
                state == ConnectionState.LOGIN,
    ) {
        "Negotiation must begin before Configuration"
    }
    profile.begin(this)
    val handshake = requirePacket<HandshakePacket>(incoming.receive())
    profile.acceptHandshake(handshake)
    return when (state) {
        ConnectionState.STATUS -> {
            if (!options.statusEnabled) {
                throw MinecraftServerException(
                    "Status requests are disabled by configuration",
                )
            }
            handleStatus(options, policy)
            null
        }

        ConnectionState.LOGIN -> {
            val transferred = handshake.nextState == HandshakeNextState.TRANSFER
            if (transferred && !options.acceptsTransfers) {
                throw MinecraftLoginRejectedException(
                    reason = JsonTextComponent(
                        buildJsonObject { put("translate", "multiplayer.disconnect.transfers_disabled") }.toString(),
                    ),
                    message = "Transfer connections are disabled by configuration",
                )
            }
            val actualVersion = handshake.protocolVersion
            val expectedVersion = options.protocolData.protocolVersion
            if (actualVersion != expectedVersion) {
                val message = "Unsupported protocol version $actualVersion; expected $expectedVersion"
                throw MinecraftLoginRejectedException(
                    reason = JsonTextComponent(buildJsonObject { put("text", message) }.toString()),
                    message = message,
                )
            }
            handleLogin(transferred, profile, options, policy)
        }

        else -> throw MinecraftServerException(
            "Handshake ${handshake.nextState} entered unsupported state $state",
        )
    }
}

private suspend fun MinecraftServerConnection.handleStatus(
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
) {
    requirePacket<StatusRequestPacket>(incoming.receive())
    outgoing.send(
        StatusResponsePacket(
            policy.statusJson(
                options,
                authentication is MinecraftServerAuthentication.Online,
            ),
        ),
    )
    requestFlush()
    val ping = requirePacket<StatusPingRequestPacket>(incoming.receive())
    outgoing.send(StatusPongResponsePacket(ping.timestamp))
    outgoing.close()
    awaitClosed()
}

private suspend fun MinecraftServerConnection.handleLogin(
    transferred: Boolean,
    profile: ServerNegotiationProfile,
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
): MinecraftServerNegotiationResult {
    val start = awaitLoginPacket<LoginStartPacket>(profile, policy)
    val gameProfile = authenticate(start, profile, options, policy)
    val rejection = policy.profileRejection(
        gameProfile,
        transferred,
        options,
    )
    if (rejection != null) {
        throw MinecraftLoginRejectedException(
            reason = rejection,
            message = "Profile ${gameProfile.name} was rejected: ${rejection.json}",
        )
    }

    profile.negotiateLogin(this)
    options.compressionThreshold?.let { threshold ->
        outgoing.send(SetCompressionPacket(threshold))
    }
    outgoing.send(LoginSuccessPacket(gameProfile, options.sessionId))
    awaitLoginPacket<LoginAcknowledgedPacket>(profile, policy)
    awaitState(ConnectionState.CONFIGURATION)

    val clientInformation =
        awaitConfigurationPacket<ConfigurationClientInformationPacket>(
            profile,
            policy,
        ).information
    profile.negotiateConfigurationStart(this)
    outgoing.send(FeatureFlagsPacket(options.protocolData.enabledFeatureFlags))
    profile.negotiateEarlyConfiguration(this)
    outgoing.send(
        ConfigurationClientboundKnownPacksPacket(
            options.protocolData.offeredKnownPacks,
        ),
    )
    val acceptedKnownPacks =
        awaitConfigurationPacket<ConfigurationServerboundKnownPacksPacket>(
            profile,
            policy,
        ).knownPacks
    val synchronizedRegistryPackets = options.protocolData.synchronizedRegistryPackets(acceptedKnownPacks)
    synchronizedRegistryPackets.forEach { registryDataPacket -> outgoing.send(registryDataPacket) }
    outgoing.send(ConfigurationUpdateTagsPacket(options.protocolData.registryTags))

    profile.negotiateConfiguration(this)
    val extensionPackets = policy.configurationPackets(
        gameProfile,
        clientInformation,
        acceptedKnownPacks,
        transferred,
        options,
    )
    val extensionTasks = policy.configurationTasks(
        gameProfile,
        clientInformation,
        acceptedKnownPacks,
        transferred,
        options,
    )
    extensionPackets.forEach { outgoing.send(it) }
    extensionTasks.forEach { task ->
        task.clientboundPackets.forEach { outgoing.send(it) }
        awaitConfigurationTask(task, profile, policy)
    }

    val onlineMode = authentication is MinecraftServerAuthentication.Online
    val playLoginPacket = policy.createPlayLoginPacket(
        gameProfile,
        clientInformation,
        transferred,
        onlineMode,
        options,
    )
    val baseProtocolRegistryContext = try {
        options.protocolData
            .resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
            .withPlayLoginDimensionLayout(playLoginPacket, synchronizedRegistryPackets, options.protocolData)
    } catch (failure: IllegalArgumentException) {
        throw MinecraftServerException(
            failure.message ?: "Invalid Play Login or registry context",
            failure,
        )
    }
    val protocolRegistryContext = profile.resolveProtocolRegistryContext(baseProtocolRegistryContext)
    installProtocolRegistryContext(protocolRegistryContext)

    outgoing.send(FinishConfigurationPacket)
    awaitConfigurationPacket<AcknowledgeFinishConfigurationPacket>(
        profile,
        policy,
    )
    awaitState(ConnectionState.PLAY)
    profile.preparePlay(this)
    outgoing.send(playLoginPacket)
    requestFlush()
    val negotiationProfileResult = profile.complete(this)
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
    start: LoginStartPacket,
    profile: ServerNegotiationProfile,
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
): GameProfile = when (val configured = authentication) {
    MinecraftServerAuthentication.Offline ->
        MinecraftOfflineIdentity(start.name).toGameProfile()

    is MinecraftServerAuthentication.Online -> {
        val challenge = configured.keyPair.createChallenge(
            shouldAuthenticate = true,
        )
        outgoing.send(challenge.toEncryptionRequestPacket())
        val response = awaitLoginPacket<EncryptionResponsePacket>(
            profile,
            policy,
        )
        val exchange = challenge.accept(response)
        val sharedSecret = exchange.sharedSecret
        try {
            enableEncryption(sharedSecret)
            val joined = MinecraftSessionApi(
                configured.sessionHttpClient,
            ).hasJoined(
                MinecraftSessionHasJoinedRequest(
                    username = start.name,
                    serverId = exchange.serverHash.value,
                    ip =
                        if (options.preventProxyConnections) {
                            clientIpAddress ?: throw MinecraftServerException(
                                "Proxy prevention requires the client IP address",
                            )
                        } else {
                            null
                        },
                ),
            ) ?: throw MinecraftServerException(
                "Session server did not verify ${start.name}",
            )
            joined.toGameProfile(start.name)
        } finally {
            sharedSecret.fill(0)
        }
    }
}

private suspend inline fun <reified T : ServerboundPacket>
        MinecraftServerConnection.awaitLoginPacket(
    profile: ServerNegotiationProfile,
    policy: MinecraftServerNegotiationPolicy,
): T {
    while (true) {
        requestFlush()
        val packet = incoming.receive()
        if (packet is T) return packet
        if (profile.handleLoginPacket(this, packet)) continue
        handleUnexpected(packet, policy)
    }
}

private suspend inline fun <reified T : ServerboundPacket>
        MinecraftServerConnection.awaitConfigurationPacket(
    profile: ServerNegotiationProfile,
    policy: MinecraftServerNegotiationPolicy,
): T {
    while (true) {
        requestFlush()
        val packet = incoming.receive()
        if (packet is T) return packet
        if (profile.handleConfigurationPacket(this, packet)) continue
        handleUnexpected(packet, policy)
    }
}

private suspend fun MinecraftServerConnection.awaitConfigurationTask(
    task: MinecraftServerNegotiationTask,
    profile: ServerNegotiationProfile,
    policy: MinecraftServerNegotiationPolicy,
) {
    while (true) {
        requestFlush()
        val packet = incoming.receive()
        if (task.isComplete(packet)) return
        if (profile.handleConfigurationPacket(this, packet)) continue
        handleUnexpected(packet, policy)
    }
}

private suspend fun MinecraftServerConnection.handleUnexpected(
    packet: ServerboundPacket,
    policy: MinecraftServerNegotiationPolicy,
) {
    if (
        packet is ConfigurationServerboundPluginMessagePacket &&
        packet.payload is CustomPayload.Brand
    ) {
        return
    }
    if (packet !is UnknownPacket.Serverbound) {
        throw MinecraftServerException(
            "Unexpected negotiation packet ${packet::class.simpleName}",
        )
    }
    when (val result = policy.onUnhandledQuery(packet)) {
        ServerNegotiationQueryResult.Pass -> Unit
        is ServerNegotiationQueryResult.Reject ->
            throw MinecraftServerException(result.reason)

        is ServerNegotiationQueryResult.Respond ->
            result.clientboundPackets.forEach { outgoing.send(it) }
    }
}

private inline fun <reified T : ServerboundPacket> requirePacket(
    packet: ServerboundPacket,
): T = packet as? T ?: throw MinecraftServerException(
    "Expected ${T::class.simpleName}, received ${packet::class.simpleName}",
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
