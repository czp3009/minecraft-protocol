package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.*
import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.completeRegistryPackets
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.ServerNegotiationProfile
import com.hiczp.minecraft.protocol.session.VanillaServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Facts produced when the peer completes Login and Configuration negotiation.
 * [login] is the exact Play Login packet sent at the end of that successful transition, while
 * [MinecraftServerConnection.registries] remains the connection's authoritative mutable registry
 * context.
 */
data class MinecraftServerPlayReady(
    val profile: GameProfile,
    val clientInformation: ClientInformation,
    val acceptedKnownPacks: List<KnownPack>,
    val login: PlayLoginPacket,
    val negotiationProfile: NegotiationProfileResult,
    val transferred: Boolean = false,
)

/**
 * Runs the preset negotiation while exclusively borrowing [MinecraftServerConnection.incoming]
 * and [MinecraftServerConnection.outgoing]. Callers must not concurrently
 * receive or send until this method returns.
 *
 * Returns null when a non-login connection (a status ping) was answered and closed completely
 * before returning; the caller has nothing left to do. A returned [MinecraftServerPlayReady]
 * means the open connection reached Play, contains the exact Play Login in
 * [MinecraftServerPlayReady.login], and has the negotiated registry context installed; further
 * traffic and closing then belong to the caller. Negotiation failures raised by this library,
 * such as [MinecraftLoginRejectedException], leave the connection open so the caller may send the
 * rejection's failure packet explicitly before closing. Wire and pump failures surface as their
 * original exception with the connection already terminated; only closing remains.
 */
suspend fun MinecraftServerConnection.negotiate(
    profile: ServerNegotiationProfile = VanillaServer,
    options: MinecraftServerNegotiationOptions =
        MinecraftServerNegotiationOptions(),
    policy: MinecraftServerNegotiationPolicy =
        DefaultMinecraftServerNegotiationPolicy,
): MinecraftServerPlayReady? {
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
): MinecraftServerPlayReady? {
    requirePacket<StatusRequestPacket>(incoming.receive())
    outgoing.send(
        StatusResponsePacket(
            policy.statusJson(
                options,
                authentication is MinecraftServerAuthentication.Online,
            ),
        ),
    )
    val ping = requirePacket<StatusPingRequestPacket>(incoming.receive())
    outgoing.send(StatusPongResponsePacket(ping.timestamp))
    outgoing.close()
    awaitClosed()
    return null
}

private suspend fun MinecraftServerConnection.handleLogin(
    transferred: Boolean,
    profile: ServerNegotiationProfile,
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
): MinecraftServerPlayReady {
    val start = awaitLoginPacket<LoginStartPacket>(profile, options, policy)
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
    awaitLoginPacket<LoginAcknowledgedPacket>(profile, options, policy)
    awaitState(ConnectionState.CONFIGURATION)

    val clientInformation =
        awaitConfigurationPacket<ConfigurationClientInformationPacket>(
            profile,
            options,
            policy,
            "Client Information",
        ).information
    profile.negotiateConfigurationStart(this)
    outgoing.send(options.protocolData.featureFlags)
    profile.negotiateEarlyConfiguration(this)
    outgoing.send(
        ConfigurationClientboundKnownPacksPacket(
            options.protocolData.knownPacks,
        ),
    )
    val acceptedKnownPacks =
        awaitConfigurationPacket<ConfigurationServerboundKnownPacksPacket>(
            profile,
            options,
            policy,
            "Known Packs",
        ).knownPacks
    options.protocolData.registryPackets(acceptedKnownPacks)
        .forEach { outgoing.send(it) }
    outgoing.send(options.protocolData.tags)

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
    extensionPackets.forEach(::validateConfigurationExtensionPacket)
    extensionTasks.flatMap(MinecraftServerNegotiationTask::packets)
        .forEach(::validateConfigurationExtensionPacket)
    extensionPackets.forEach { outgoing.send(it) }
    extensionTasks.forEach { task ->
        task.packets.forEach { outgoing.send(it) }
        awaitConfigurationTask(task, profile, options, policy)
    }

    val onlineMode = authentication is MinecraftServerAuthentication.Online
    val login = policy.playLogin(
        gameProfile,
        clientInformation,
        transferred,
        onlineMode,
        options,
    )
    validatePlayLogin(login, options)
    val registryContext = profile.resolveRegistryContext(
        activeRegistryContext(options, login),
    )
    installRegistryContext(registryContext)

    outgoing.send(FinishConfigurationPacket)
    awaitConfigurationPacket<AcknowledgeFinishConfigurationPacket>(
        profile,
        options,
        policy,
        "Finish Configuration acknowledgement",
    )
    awaitState(ConnectionState.PLAY)
    profile.preparePlay(this)
    outgoing.send(login)
    playLogin = login
    val profileResult = profile.complete(this)
    return MinecraftServerPlayReady(
        profile = gameProfile,
        clientInformation = clientInformation,
        acceptedKnownPacks = acceptedKnownPacks,
        login = login,
        negotiationProfile = profileResult,
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
            options,
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
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
): T {
    repeat(options.maximumPacketsPerPhase) {
        val packet = incoming.receive()
        if (packet is T) return packet
        if (profile.handleLoginPacket(this, packet)) return@repeat
        handleUnexpected(packet, policy)
    }
    throw MinecraftServerException(
        "${T::class.simpleName} Login packet limit exceeded",
    )
}

private suspend inline fun <reified T : ServerboundPacket>
        MinecraftServerConnection.awaitConfigurationPacket(
    profile: ServerNegotiationProfile,
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
    description: String,
): T {
    repeat(options.maximumPacketsPerPhase) {
        val packet = incoming.receive()
        if (packet is T) return packet
        if (profile.handleConfigurationPacket(this, packet)) return@repeat
        handleUnexpected(packet, policy)
    }
    throw MinecraftServerException("$description packet limit exceeded")
}

private suspend fun MinecraftServerConnection.awaitConfigurationTask(
    task: MinecraftServerNegotiationTask,
    profile: ServerNegotiationProfile,
    options: MinecraftServerNegotiationOptions,
    policy: MinecraftServerNegotiationPolicy,
) {
    repeat(options.maximumPacketsPerPhase) {
        val packet = incoming.receive()
        if (task.isComplete(packet)) return
        if (profile.handleConfigurationPacket(this, packet)) return@repeat
        handleUnexpected(packet, policy)
    }
    throw MinecraftServerException(
        "Configuration task '${task.name}' packet limit exceeded",
    )
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
            result.packets.forEach { outgoing.send(it) }
    }
}

private fun validateConfigurationExtensionPacket(packet: ClientboundPacket) {
    if (
        packet is FeatureFlagsPacket ||
        packet is ConfigurationClientboundKnownPacksPacket ||
        packet is RegistryDataPacket ||
        packet is ConfigurationUpdateTagsPacket ||
        packet is FinishConfigurationPacket
    ) {
        throw MinecraftServerException(
            "Configuration extension ${packet::class.simpleName} is managed by the Vanilla negotiator",
        )
    }
}

private fun validatePlayLogin(
    login: PlayLoginPacket,
    options: MinecraftServerNegotiationOptions,
) {
    if (login.maxPlayers < 0) {
        throw MinecraftServerException(
            "Play Login maximum players must be non-negative",
        )
    }
    if (
        login.chunkRadius !in
        MinecraftServerNegotiationOptions.MIN_VIEW_DISTANCE..
        MinecraftServerNegotiationOptions.MAX_VIEW_DISTANCE
    ) {
        throw MinecraftServerException(
            "Play Login chunk radius must be in ${MinecraftServerNegotiationOptions.MIN_VIEW_DISTANCE}..${MinecraftServerNegotiationOptions.MAX_VIEW_DISTANCE}",
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
    MinecraftDimensionLayout.from(
        options.protocolData.completeRegistryPackets(),
        login.spawnInfo.dimensionTypeId,
    )
}

private fun activeRegistryContext(
    options: MinecraftServerNegotiationOptions,
    login: PlayLoginPacket,
): ProtocolRegistryContext {
    val dimension = MinecraftDimensionLayout.from(
        options.protocolData.completeRegistryPackets(),
        login.spawnInfo.dimensionTypeId,
    )
    return options.protocolData.registryContext.withChunkSectionCount(
        dimension.sectionCount,
    )
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
