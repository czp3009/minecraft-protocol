package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema
import com.hiczp.minecraft.protocol.session.*

data class ForgeHandshakeIntent(
    val forgePeer: Boolean,
    val networkVersion: Int,
    val serverAddress: String,
)

object ForgeHandshake {
    const val MARKER: String = "FORGE"

    fun enhanceHostName(
        hostName: String,
        networkVersion: Int = ForgeProtocol.NETWORK_VERSION,
    ): String {
        require(networkVersion >= 0) {
            "Forge network version must be non-negative"
        }
        val marker = if (networkVersion == 0) MARKER else "$MARKER$networkVersion"
        return "$hostName\u0000$marker".also { enhanced ->
            require(enhanced.length <= 255) {
                "Forge-enhanced Handshake hostname exceeds 255 characters"
            }
        }
    }

    fun enhance(
        handshakePacket: HandshakePacket,
        networkVersion: Int = ForgeProtocol.NETWORK_VERSION,
    ): HandshakePacket = handshakePacket.copy(
        serverAddress = enhanceHostName(handshakePacket.serverAddress, networkVersion),
    )

    fun inspect(hostName: String): ForgeHandshakeIntent {
        val parts = hostName.split('\u0000')
        var forgePeer = false
        var networkVersion = 0
        parts.forEach { part ->
            if (!part.startsWith(MARKER)) return@forEach
            forgePeer = true
            val suffix = part.removePrefix(MARKER)
            networkVersion = if (suffix.isEmpty()) {
                0
            } else {
                suffix.toIntOrNull() ?: throw ForgeNegotiationException(
                    "Invalid Forge network marker $part",
                )
            }
        }
        return ForgeHandshakeIntent(
            forgePeer,
            networkVersion,
            parts.firstOrNull().orEmpty(),
        )
    }
}

data class ForgeClientProfileDefinition(
    val staticRegistrySchema: StaticRegistrySchema,
    val forgeNetworkConfiguration: ForgeNetworkConfiguration = ForgeNetworkConfiguration(),
    val mods: Map<String, ForgeModInfo> = emptyMap(),
    val dataPackRegistryIds: Set<Identifier> = emptySet(),
    val networkVersion: Int = ForgeProtocol.NETWORK_VERSION,
) {
    init {
        require(networkVersion >= 0) {
            "Forge network version must be non-negative"
        }
    }
}

data class ForgeServerProfileDefinition(
    val forgeNetworkConfiguration: ForgeNetworkConfiguration = ForgeNetworkConfiguration(),
    val mods: Map<String, ForgeModInfo> = emptyMap(),
    val forgeRegistrySync: ForgeRegistrySync? = null,
    /** Caller-built context retained by reference across connections. */
    val protocolRegistryContext: ProtocolRegistryContext? = null,
    val configFiles: List<ForgeConfigDataMessage> = emptyList(),
    val networkVersion: Int = ForgeProtocol.NETWORK_VERSION,
) {
    init {
        require(networkVersion >= 0) {
            "Forge network version must be non-negative"
        }
    }
}

data class ForgeNegotiationResult(
    val forgePeer: Boolean,
    val networkVersion: Int?,
    val remoteChannels: Set<Identifier>,
    val remoteMods: Map<String, ForgeModInfo>,
    val remoteChannelVersions: Map<Identifier, Int>,
    val registriesSynchronized: Boolean,
    val configFiles: List<ForgeConfigDataMessage>,
) : NegotiationProfileResult

class ForgeClientProfile(
    val forgeClientProfileDefinition: ForgeClientProfileDefinition,
) : ClientNegotiationProfile {
    private val remoteChannels = linkedSetOf<Identifier>()
    private val remoteMods = linkedMapOf<String, ForgeModInfo>()
    private val remoteChannelVersions = linkedMapOf<Identifier, Int>()
    private val forgeRegistrySnapshots = linkedMapOf<Identifier, ForgeRegistrySnapshot>()
    private val configFiles = mutableListOf<ForgeConfigDataMessage>()
    private var expectedRegistryIds: MutableSet<Identifier>? = null
    private var forgePeer = false
    private var receivedModVersions = false
    private var receivedChannelVersions = false
    private var receivedRegistryList = false
    private var sentRegistration = false
    private var begun = false

    override suspend fun begin(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        check(!begun) { "A ForgeClientProfile can negotiate only one connection" }
        begun = true
        requireForgeCodecs(minecraftClientPacketConnection)
        updateForgeRoutes(
            minecraftClientPacketConnection,
            forgeClientProfileDefinition.forgeNetworkConfiguration.payloadChannels,
            remoteChannels,
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
    }

    override fun prepareHandshake(handshakePacket: HandshakePacket): HandshakePacket =
        ForgeHandshake.enhance(handshakePacket, forgeClientProfileDefinition.networkVersion)

    override suspend fun handleConfigurationPacket(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        clientboundPacket: ClientboundPacket,
    ): Boolean = when (clientboundPacket) {
        is ForgeRegisterChannelsPacket -> {
            remoteChannels += clientboundPacket.channels
            updateForgeRoutes(
                minecraftClientPacketConnection,
                forgeClientProfileDefinition.forgeNetworkConfiguration.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                PacketDirection.SERVERBOUND,
            )
            if (!sentRegistration) {
                sentRegistration = true
                minecraftClientPacketConnection.outgoing.send(
                    ForgeRegisterChannelsPacket(
                        forgeClientProfileDefinition.forgeNetworkConfiguration.payloadChannels,
                    ),
                )
            }
            true
        }

        is ForgeUnregisterChannelsPacket -> {
            remoteChannels -= clientboundPacket.channels
            updateForgeRoutes(
                minecraftClientPacketConnection,
                forgeClientProfileDefinition.forgeNetworkConfiguration.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                PacketDirection.SERVERBOUND,
            )
            true
        }

        is ForgeClientboundHandshakePacket -> {
            handleHandshakeMessage(minecraftClientPacketConnection, clientboundPacket.forgeClientboundHandshakeMessage)
            true
        }

        else -> false
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        ensureCompatiblePeer()
        val expected = expectedRegistryIds
        if (expected != null && expected.isNotEmpty()) {
            throw ForgeNegotiationException(
                "Configuration finished before Forge registries $expected arrived",
            )
        }
        if (!receivedRegistryList) return protocolRegistryContext
        val remoteRegistrySnapshot = forgeRemoteRegistrySnapshot(forgeRegistrySnapshots)
        requireForgeCompatible(forgeClientProfileDefinition.staticRegistrySchema, remoteRegistrySnapshot)
        val resolvedProtocolRegistryContext =
            forgeClientProfileDefinition.staticRegistrySchema.resolve(remoteRegistrySnapshot)
                .withForgeRegistrySizes(forgeRegistrySnapshots)
        return protocolRegistryContext.withStaticRegistryResolution(resolvedProtocolRegistryContext)
    }

    override suspend fun preparePlay(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        ensureCompatiblePeer()
        updateForgeRoutes(
            minecraftClientPacketConnection,
            forgeClientProfileDefinition.forgeNetworkConfiguration.payloadChannels,
            remoteChannels,
            ConnectionState.PLAY,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
    }

    override suspend fun complete(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ): NegotiationProfileResult = ForgeNegotiationResult(
        forgePeer,
        forgeClientProfileDefinition.networkVersion.takeIf { forgePeer },
        remoteChannels.toSet(),
        remoteMods.toMap(),
        remoteChannelVersions.toMap(),
        receivedRegistryList && expectedRegistryIds?.isEmpty() == true,
        configFiles.toList(),
    )

    private suspend fun handleHandshakeMessage(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        forgeClientboundHandshakeMessage: ForgeClientboundHandshakeMessage,
    ) {
        when (forgeClientboundHandshakeMessage) {
            is ForgeModVersionsMessage -> {
                if (receivedModVersions) {
                    throw ForgeNegotiationException(
                        "Server sent more than one Forge mod-version list",
                    )
                }
                receivedModVersions = true
                forgePeer = true
                remoteMods.putAll(forgeClientboundHandshakeMessage.mods)
                minecraftClientPacketConnection.outgoing.send(
                    ForgeServerboundHandshakePacket(
                        ForgeModVersionsMessage(forgeClientProfileDefinition.mods),
                    ),
                )
            }

            is ForgeChannelVersionsMessage -> {
                if (!receivedModVersions || receivedChannelVersions) {
                    throw ForgeNegotiationException(
                        "Forge channel-version list arrived out of order",
                    )
                }
                val forgeChannelValidation =
                    forgeClientProfileDefinition.forgeNetworkConfiguration.validateServer(
                        forgeClientboundHandshakeMessage.channels
                    )
                if (!forgeChannelValidation.successful) {
                    throw ForgeChannelNegotiationException(
                        forgeChannelValidation.toFailureMessage(),
                    )
                }
                receivedChannelVersions = true
                remoteChannelVersions.putAll(forgeClientboundHandshakeMessage.channels)
                minecraftClientPacketConnection.outgoing.send(
                    ForgeServerboundHandshakePacket(
                        forgeClientProfileDefinition.forgeNetworkConfiguration.versionsPacket,
                    ),
                )
            }

            is ForgeRegistryListMessage -> {
                if (!receivedChannelVersions || receivedRegistryList) {
                    throw ForgeNegotiationException(
                        "Forge registry list arrived out of order",
                    )
                }
                val missingDataPackRegistryIds = forgeClientboundHandshakeMessage.dataPackRegistryIds
                    .filterTo(linkedSetOf()) { identifier ->
                        identifier !in forgeClientProfileDefinition.dataPackRegistryIds
                    }
                if (missingDataPackRegistryIds.isNotEmpty()) {
                    throw ForgeMissingDataPackRegistryIdsException(
                        missingDataPackRegistryIds,
                    )
                }
                receivedRegistryList = true
                expectedRegistryIds = forgeClientboundHandshakeMessage.registryIds.toMutableSet()
                forgeRegistrySnapshots.clear()
                acknowledge(minecraftClientPacketConnection, forgeClientboundHandshakeMessage.token)
            }

            is ForgeRegistryDataMessage -> {
                val expected = expectedRegistryIds
                    ?: throw ForgeNegotiationException(
                        "Forge registry data arrived before its registry list",
                    )
                if (!expected.remove(forgeClientboundHandshakeMessage.registryId)) {
                    throw ForgeNegotiationException(
                        "Unexpected Forge registry data ${forgeClientboundHandshakeMessage.registryId}",
                    )
                }
                forgeRegistrySnapshots[forgeClientboundHandshakeMessage.registryId] =
                    forgeClientboundHandshakeMessage.forgeRegistrySnapshot
                acknowledge(minecraftClientPacketConnection, forgeClientboundHandshakeMessage.token)
            }

            is ForgeConfigDataMessage -> {
                if (!receivedRegistryList || expectedRegistryIds?.isNotEmpty() == true) {
                    throw ForgeNegotiationException(
                        "Forge config data arrived before registry synchronization completed",
                    )
                }
                configFiles += forgeClientboundHandshakeMessage
            }

            is ForgeMismatchDataMessage ->
                throw ForgeRemoteMismatchException(forgeClientboundHandshakeMessage)
        }
    }

    private suspend fun acknowledge(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        token: Int,
    ) {
        minecraftClientPacketConnection.outgoing.send(
            ForgeServerboundHandshakePacket(
                ForgeAcknowledgeMessage(token),
            ),
        )
    }

    private fun ensureCompatiblePeer() {
        if (!forgePeer && !forgeClientProfileDefinition.forgeNetworkConfiguration.canConnectToVanillaServer()) {
            throw ForgeVanillaPeerRejectedException(
                "Local Forge channels require a Forge server",
            )
        }
    }
}

class ForgeServerProfile(
    val forgeServerProfileDefinition: ForgeServerProfileDefinition,
) : ServerNegotiationProfile {
    private val remoteChannels = linkedSetOf<Identifier>()
    private val remoteMods = linkedMapOf<String, ForgeModInfo>()
    private val remoteChannelVersions = linkedMapOf<Identifier, Int>()
    private var forgeHandshakeIntent: ForgeHandshakeIntent? = null
    private var expectedResponse: ForgeExpectedResponse? = null
    private var expectedAck: Int? = null
    private var registriesSynchronized = false
    private var forgeServerStage = ForgeServerStage.BEGIN
    private var begun = false

    override suspend fun begin(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        check(!begun) { "A ForgeServerProfile can negotiate only one connection" }
        begun = true
        requireForgeCodecs(minecraftServerPacketConnection)
        updateForgeRoutes(
            minecraftServerPacketConnection,
            forgeServerProfileDefinition.forgeNetworkConfiguration.payloadChannels,
            remoteChannels,
            ConnectionState.CONFIGURATION,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
    }

    override fun acceptHandshake(handshakePacket: HandshakePacket) {
        check(forgeHandshakeIntent == null) {
            "A Forge server profile received more than one Handshake"
        }
        val inspectedForgeHandshakeIntent = ForgeHandshake.inspect(handshakePacket.serverAddress)
        if (
            inspectedForgeHandshakeIntent.forgePeer &&
            inspectedForgeHandshakeIntent.networkVersion != forgeServerProfileDefinition.networkVersion
        ) {
            throw ForgeNetworkVersionException(
                inspectedForgeHandshakeIntent.networkVersion,
                forgeServerProfileDefinition.networkVersion,
            )
        }
        forgeHandshakeIntent = inspectedForgeHandshakeIntent
    }

    override suspend fun negotiateConfigurationStart(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        requireStage(ForgeServerStage.BEGIN)
        val forgeHandshakeIntent = forgeHandshakeIntent
            ?: throw ForgeNegotiationException(
                "Forge profile did not observe the Handshake packet",
            )
        if (!forgeHandshakeIntent.forgePeer) {
            val hasDataPackRegistryIds =
                forgeServerProfileDefinition.forgeRegistrySync?.dataPackRegistryIds?.isNotEmpty() == true
            if (!forgeServerProfileDefinition.forgeNetworkConfiguration.acceptsVanillaClient() || hasDataPackRegistryIds) {
                throw ForgeVanillaPeerRejectedException(
                    "Server Forge channels or data-pack registries require a Forge client",
                )
            }
            forgeServerStage = ForgeServerStage.COMPLETE
            return
        }
        forgeServerStage = ForgeServerStage.NEGOTIATING
        minecraftServerPacketConnection.outgoing.send(
            ForgeRegisterChannelsPacket(forgeServerProfileDefinition.forgeNetworkConfiguration.payloadChannels),
        )

        expectedResponse = ForgeExpectedResponse.MOD_VERSIONS
        minecraftServerPacketConnection.outgoing.send(
            ForgeClientboundHandshakePacket(
                ForgeModVersionsMessage(forgeServerProfileDefinition.mods),
            ),
        )
        awaitExpected(minecraftServerPacketConnection)

        expectedResponse = ForgeExpectedResponse.CHANNEL_VERSIONS
        minecraftServerPacketConnection.outgoing.send(
            ForgeClientboundHandshakePacket(
                forgeServerProfileDefinition.forgeNetworkConfiguration.versionsPacket,
            ),
        )
        awaitExpected(minecraftServerPacketConnection)

        synchronizeRegistries(minecraftServerPacketConnection)
        forgeServerProfileDefinition.configFiles.forEach { forgeConfigDataMessage ->
            minecraftServerPacketConnection.outgoing.send(
                ForgeClientboundHandshakePacket(forgeConfigDataMessage),
            )
        }
        forgeServerStage = ForgeServerStage.COMPLETE
    }

    override suspend fun handleConfigurationPacket(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
        serverboundPacket: ServerboundPacket,
    ): Boolean = when (serverboundPacket) {
        is ForgeRegisterChannelsPacket -> {
            remoteChannels += serverboundPacket.channels
            updateForgeRoutes(
                minecraftServerPacketConnection,
                forgeServerProfileDefinition.forgeNetworkConfiguration.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
                PacketDirection.CLIENTBOUND,
            )
            true
        }

        is ForgeUnregisterChannelsPacket -> {
            remoteChannels -= serverboundPacket.channels
            updateForgeRoutes(
                minecraftServerPacketConnection,
                forgeServerProfileDefinition.forgeNetworkConfiguration.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
                PacketDirection.CLIENTBOUND,
            )
            true
        }

        is ForgeServerboundHandshakePacket -> {
            handleHandshakeMessage(serverboundPacket.forgeServerboundHandshakeMessage)
            true
        }

        else -> false
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        val sharedProtocolRegistryContext =
            forgeServerProfileDefinition.protocolRegistryContext ?: return protocolRegistryContext
        val sectionCount = protocolRegistryContext.chunkSectionCount ?: return sharedProtocolRegistryContext
        return if (sharedProtocolRegistryContext.chunkSectionCount == sectionCount) {
            sharedProtocolRegistryContext
        } else {
            sharedProtocolRegistryContext.withChunkSectionCount(sectionCount)
        }
    }

    override suspend fun preparePlay(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        requireStage(ForgeServerStage.COMPLETE)
        updateForgeRoutes(
            minecraftServerPacketConnection,
            forgeServerProfileDefinition.forgeNetworkConfiguration.payloadChannels,
            remoteChannels,
            ConnectionState.PLAY,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
    }

    override suspend fun complete(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult {
        val forgeHandshakeIntent = checkNotNull(forgeHandshakeIntent)
        return ForgeNegotiationResult(
            forgeHandshakeIntent.forgePeer,
            forgeHandshakeIntent.networkVersion.takeIf { forgeHandshakeIntent.forgePeer },
            remoteChannels.toSet(),
            remoteMods.toMap(),
            remoteChannelVersions.toMap(),
            registriesSynchronized,
            emptyList(),
        )
    }

    private fun handleHandshakeMessage(forgeServerboundHandshakeMessage: ForgeServerboundHandshakeMessage) {
        when (forgeServerboundHandshakeMessage) {
            is ForgeModVersionsMessage -> {
                requireExpected(ForgeExpectedResponse.MOD_VERSIONS)
                remoteMods.clear()
                remoteMods.putAll(forgeServerboundHandshakeMessage.mods)
                expectedResponse = null
            }

            is ForgeChannelVersionsMessage -> {
                requireExpected(ForgeExpectedResponse.CHANNEL_VERSIONS)
                val forgeChannelValidation =
                    forgeServerProfileDefinition.forgeNetworkConfiguration.validateClient(
                        forgeServerboundHandshakeMessage.channels
                    )
                if (!forgeChannelValidation.successful) {
                    throw ForgeChannelNegotiationException(
                        forgeChannelValidation.toFailureMessage(),
                    )
                }
                remoteChannelVersions.clear()
                remoteChannelVersions.putAll(forgeServerboundHandshakeMessage.channels)
                expectedResponse = null
            }

            is ForgeAcknowledgeMessage -> {
                val expected = expectedAck
                    ?: throw ForgeNegotiationException(
                        "Unexpected Forge acknowledgement ${forgeServerboundHandshakeMessage.token}",
                    )
                if (forgeServerboundHandshakeMessage.token != expected) {
                    throw ForgeNegotiationException(
                        "Forge acknowledgement ${forgeServerboundHandshakeMessage.token} does not match $expected",
                    )
                }
                expectedAck = null
            }
        }
    }

    private suspend fun synchronizeRegistries(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        val forgeRegistrySync = forgeServerProfileDefinition.forgeRegistrySync
        var token = 0
        expectedAck = token
        minecraftServerPacketConnection.outgoing.send(
            ForgeClientboundHandshakePacket(
                ForgeRegistryListMessage(
                    token,
                    forgeRegistrySync?.registryIds.orEmpty(),
                    forgeRegistrySync?.dataPackRegistryIds?.toList().orEmpty(),
                ),
            ),
        )
        awaitExpected(minecraftServerPacketConnection)
        forgeRegistrySync?.forgeRegistrySnapshots?.forEach { (registryId, forgeRegistrySnapshot) ->
            token++
            expectedAck = token
            minecraftServerPacketConnection.outgoing.send(
                ForgeClientboundHandshakePacket(
                    ForgeRegistryDataMessage(token, registryId, forgeRegistrySnapshot),
                ),
            )
            awaitExpected(minecraftServerPacketConnection)
        }
        registriesSynchronized = true
    }

    private suspend fun awaitExpected(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        while (expectedResponse != null || expectedAck != null) {
            minecraftServerPacketConnection.requestFlush()
            val serverboundPacket = minecraftServerPacketConnection.incoming.receive()
            if (!handleConfigurationPacket(minecraftServerPacketConnection, serverboundPacket)) {
                throw ForgeNegotiationException(
                    "Unexpected packet during Forge negotiation: ${serverboundPacket::class.simpleName}",
                )
            }
        }
    }

    private fun requireExpected(forgeExpectedResponse: ForgeExpectedResponse) {
        if (expectedResponse != forgeExpectedResponse || expectedAck != null) {
            throw ForgeNegotiationException(
                "Forge response $forgeExpectedResponse arrived while waiting for $expectedResponse and ack $expectedAck",
            )
        }
    }

    private fun requireStage(expected: ForgeServerStage) {
        if (forgeServerStage != expected) {
            throw ForgeNegotiationException(
                "Forge server profile is in $forgeServerStage; expected $expected",
            )
        }
    }
}

class ForgeChannelNegotiationException(
    val failurePacket: ForgeMismatchDataMessage,
) : ForgeNegotiationException(
    "Forge channel negotiation failed for ${failurePacket.missing + failurePacket.mismatched.keys}",
)

class ForgeRemoteMismatchException(
    val failurePacket: ForgeMismatchDataMessage,
) : ForgeNegotiationException(
    "Forge server rejected channels ${failurePacket.missing + failurePacket.mismatched.keys}",
)

class ForgeNetworkVersionException(
    val remoteVersion: Int,
    val localVersion: Int,
) : ForgeNegotiationException(
    "Forge network version $remoteVersion does not match $localVersion",
)

class ForgeMissingDataPackRegistryIdsException(
    val missingDataPackRegistryIds: Set<Identifier>,
) : ForgeNegotiationException(
    "Forge client is missing synchronized data-pack registries $missingDataPackRegistryIds",
)

class ForgeVanillaPeerRejectedException(
    message: String,
) : ForgeNegotiationException(message)

private suspend fun <Incoming : Packet, Outgoing : Packet> updateForgeRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    localChannels: Set<Identifier>,
    remoteChannels: Set<Identifier>,
    connectionState: ConnectionState,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val candidates = minecraftPacketConnection.declaredExtensionRoutes
        .filterIsInstance<PacketRouteKey.CustomPayload>()
        .filter { packetRouteKey -> packetRouteKey.connectionState == connectionState }
        .toSet()
    val accepted = candidates.filter { packetRouteKey ->
        packetRouteKey.channel == ForgeChannels.Register ||
                packetRouteKey.channel == ForgeChannels.Unregister ||
                (
                        connectionState == ConnectionState.CONFIGURATION &&
                                packetRouteKey.channel == ForgeChannels.Handshake
                        ) ||
                (
                        packetRouteKey.packetDirection == incomingDirection &&
                                packetRouteKey.channel in localChannels
                        ) ||
                (
                        packetRouteKey.packetDirection == outgoingDirection &&
                                packetRouteKey.channel in remoteChannels
                        )
    }
    val loginRoutes = minecraftPacketConnection.declaredExtensionRoutes.filter { packetRouteKey ->
        packetRouteKey is PacketRouteKey.LoginQuery
    }
    minecraftPacketConnection.activateExtensionRoutes(buildSet {
        addAll(minecraftPacketConnection.activeExtensionRoutes)
        removeAll(candidates)
        addAll(accepted)
        addAll(loginRoutes)
    })
}

private fun <Incoming : Packet, Outgoing : Packet> requireForgeCodecs(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
) {
    val required = ForgeProtocol.packetCodecs.mapTo(linkedSetOf()) { packetCodecRegistration ->
        packetCodecRegistration.packetRouteKey
    }
    val missing = required - minecraftPacketConnection.declaredExtensionRoutes
    require(missing.isEmpty()) {
        "Forge profile is missing extension packet codecs $missing"
    }
}

private enum class ForgeExpectedResponse {
    MOD_VERSIONS,
    CHANNEL_VERSIONS,
}

private enum class ForgeServerStage {
    BEGIN,
    NEGOTIATING,
    COMPLETE,
}
