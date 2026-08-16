package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema
import com.hiczp.minecraft.protocol.session.ClientNegotiationProfile
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.ServerNegotiationProfile

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
        packet: HandshakePacket,
        networkVersion: Int = ForgeProtocol.NETWORK_VERSION,
    ): HandshakePacket = packet.copy(
        serverAddress = enhanceHostName(packet.serverAddress, networkVersion),
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

class ForgeClientProfileDefinition(
    val staticRegistries: StaticRegistrySchema,
    val network: ForgeNetworkConfiguration = ForgeNetworkConfiguration(),
    mods: Map<String, ForgeModInfo> = emptyMap(),
    dataPackRegistries: Set<Identifier> = emptySet(),
    val networkVersion: Int = ForgeProtocol.NETWORK_VERSION,
) {
    val mods: Map<String, ForgeModInfo> = mods.toMap()
    val dataPackRegistries: Set<Identifier> = dataPackRegistries.toSet()

    init {
        require(networkVersion >= 0) {
            "Forge network version must be non-negative"
        }
    }
}

class ForgeServerProfileDefinition(
    val network: ForgeNetworkConfiguration = ForgeNetworkConfiguration(),
    mods: Map<String, ForgeModInfo> = emptyMap(),
    val registrySync: ForgeRegistrySync? = null,
    /** Caller-built immutable context retained by reference across connections. */
    val resolvedRegistryContext: ProtocolRegistryContext? = null,
    configFiles: List<ForgeConfigDataMessage> = emptyList(),
    val networkVersion: Int = ForgeProtocol.NETWORK_VERSION,
) {
    val mods: Map<String, ForgeModInfo> = mods.toMap()
    val configFiles: List<ForgeConfigDataMessage> = configFiles.toList()

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
    val registrySynchronized: Boolean,
    val configFiles: List<ForgeConfigDataMessage>,
) : NegotiationProfileResult

class ForgeClientProfile(
    val definition: ForgeClientProfileDefinition,
) : ClientNegotiationProfile {
    private val remoteChannels = linkedSetOf<Identifier>()
    private val remoteMods = linkedMapOf<String, ForgeModInfo>()
    private val remoteChannelVersions = linkedMapOf<Identifier, Int>()
    private val registrySnapshots = linkedMapOf<Identifier, ForgeRegistrySnapshot>()
    private val configFiles = mutableListOf<ForgeConfigDataMessage>()
    private var expectedRegistries: MutableSet<Identifier>? = null
    private var forgePeer = false
    private var receivedModVersions = false
    private var receivedChannelVersions = false
    private var receivedRegistryList = false
    private var sentRegistration = false
    private var begun = false

    override suspend fun begin(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ) {
        check(!begun) { "A ForgeClientProfile can negotiate only one connection" }
        begun = true
        requireForgeCodecs(connection)
        updateForgeRoutes(
            connection,
            definition.network.payloadChannels,
            remoteChannels,
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
    }

    override fun prepareHandshake(packet: HandshakePacket): HandshakePacket =
        ForgeHandshake.enhance(packet, definition.networkVersion)

    override suspend fun handleConfigurationPacket(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
        packet: ClientboundPacket,
    ): Boolean = when (packet) {
        is ForgeRegisterChannelsPacket -> {
            remoteChannels += packet.channels
            updateForgeRoutes(
                connection,
                definition.network.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                PacketDirection.SERVERBOUND,
            )
            if (!sentRegistration) {
                sentRegistration = true
                connection.outgoing.send(
                    ForgeRegisterChannelsPacket(
                        definition.network.payloadChannels,
                    ),
                )
            }
            true
        }

        is ForgeUnregisterChannelsPacket -> {
            remoteChannels -= packet.channels
            updateForgeRoutes(
                connection,
                definition.network.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                PacketDirection.SERVERBOUND,
            )
            true
        }

        is ForgeClientboundHandshakePacket -> {
            handleHandshakeMessage(connection, packet.message)
            true
        }

        else -> false
    }

    override suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        ensureCompatiblePeer()
        val expected = expectedRegistries
        if (expected != null && expected.isNotEmpty()) {
            throw ForgeNegotiationException(
                "Configuration finished before Forge registries $expected arrived",
            )
        }
        if (!receivedRegistryList) return context
        val remote = forgeRemoteRegistrySnapshot(registrySnapshots)
        requireForgeCompatible(definition.staticRegistries, remote)
        val resolved = definition.staticRegistries.resolve(remote)
            .withForgeRegistrySizes(registrySnapshots)
        return context.withStaticRegistryResolution(resolved)
    }

    override suspend fun preparePlay(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ) {
        ensureCompatiblePeer()
        updateForgeRoutes(
            connection,
            definition.network.payloadChannels,
            remoteChannels,
            ConnectionState.PLAY,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
    }

    override suspend fun complete(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ): NegotiationProfileResult = ForgeNegotiationResult(
        forgePeer,
        definition.networkVersion.takeIf { forgePeer },
        remoteChannels.toSet(),
        remoteMods.toMap(),
        remoteChannelVersions.toMap(),
        receivedRegistryList && expectedRegistries?.isEmpty() == true,
        configFiles.toList(),
    )

    private suspend fun handleHandshakeMessage(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
        message: ForgeClientboundHandshakeMessage,
    ) {
        when (message) {
            is ForgeModVersionsMessage -> {
                if (receivedModVersions) {
                    throw ForgeNegotiationException(
                        "Server sent more than one Forge mod-version list",
                    )
                }
                receivedModVersions = true
                forgePeer = true
                remoteMods.putAll(message.mods)
                connection.outgoing.send(
                    ForgeServerboundHandshakePacket(
                        ForgeModVersionsMessage(definition.mods),
                    ),
                )
            }

            is ForgeChannelVersionsMessage -> {
                if (!receivedModVersions || receivedChannelVersions) {
                    throw ForgeNegotiationException(
                        "Forge channel-version list arrived out of order",
                    )
                }
                val validation = definition.network.validateServer(message.channels)
                if (!validation.successful) {
                    throw ForgeChannelNegotiationException(
                        validation.toFailureMessage(),
                    )
                }
                receivedChannelVersions = true
                remoteChannelVersions.putAll(message.channels)
                connection.outgoing.send(
                    ForgeServerboundHandshakePacket(
                        definition.network.versionsPacket,
                    ),
                )
            }

            is ForgeRegistryListMessage -> {
                if (!receivedChannelVersions || receivedRegistryList) {
                    throw ForgeNegotiationException(
                        "Forge registry list arrived out of order",
                    )
                }
                val missingDataPacks =
                    message.dataPacks.toSet() - definition.dataPackRegistries
                if (missingDataPacks.isNotEmpty()) {
                    throw ForgeMissingDataPackRegistriesException(
                        missingDataPacks,
                    )
                }
                receivedRegistryList = true
                expectedRegistries = message.normal.toMutableSet()
                registrySnapshots.clear()
                acknowledge(connection, message.token)
            }

            is ForgeRegistryDataMessage -> {
                val expected = expectedRegistries
                    ?: throw ForgeNegotiationException(
                        "Forge registry data arrived before its registry list",
                    )
                if (!expected.remove(message.registry)) {
                    throw ForgeNegotiationException(
                        "Unexpected Forge registry data ${message.registry}",
                    )
                }
                registrySnapshots[message.registry] = message.snapshot
                acknowledge(connection, message.token)
            }

            is ForgeConfigDataMessage -> {
                if (!receivedRegistryList || expectedRegistries?.isNotEmpty() == true) {
                    throw ForgeNegotiationException(
                        "Forge config data arrived before registry synchronization completed",
                    )
                }
                configFiles += message
            }

            is ForgeMismatchDataMessage ->
                throw ForgeRemoteMismatchException(message)
        }
    }

    private suspend fun acknowledge(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
        token: Int,
    ) {
        connection.outgoing.send(
            ForgeServerboundHandshakePacket(
                ForgeAcknowledgeMessage(token),
            ),
        )
    }

    private fun ensureCompatiblePeer() {
        if (!forgePeer && !definition.network.canConnectToVanillaServer()) {
            throw ForgeVanillaPeerRejectedException(
                "Local Forge channels require a Forge server",
            )
        }
    }
}

class ForgeServerProfile(
    val definition: ForgeServerProfileDefinition,
) : ServerNegotiationProfile {
    private val remoteChannels = linkedSetOf<Identifier>()
    private val remoteMods = linkedMapOf<String, ForgeModInfo>()
    private val remoteChannelVersions = linkedMapOf<Identifier, Int>()
    private var handshakeIntent: ForgeHandshakeIntent? = null
    private var expectedResponse: ForgeExpectedResponse? = null
    private var expectedAck: Int? = null
    private var registrySynchronized = false
    private var stage = ForgeServerStage.BEGIN
    private var begun = false

    override suspend fun begin(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        check(!begun) { "A ForgeServerProfile can negotiate only one connection" }
        begun = true
        requireForgeCodecs(connection)
        updateForgeRoutes(
            connection,
            definition.network.payloadChannels,
            remoteChannels,
            ConnectionState.CONFIGURATION,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
    }

    override fun acceptHandshake(packet: HandshakePacket) {
        check(handshakeIntent == null) {
            "A Forge server profile received more than one Handshake"
        }
        val intent = ForgeHandshake.inspect(packet.serverAddress)
        if (
            intent.forgePeer &&
            intent.networkVersion != definition.networkVersion
        ) {
            throw ForgeNetworkVersionException(
                intent.networkVersion,
                definition.networkVersion,
            )
        }
        handshakeIntent = intent
    }

    override suspend fun negotiateConfigurationStart(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        requireStage(ForgeServerStage.BEGIN)
        val intent = handshakeIntent
            ?: throw ForgeNegotiationException(
                "Forge profile did not observe the Handshake packet",
            )
        if (!intent.forgePeer) {
            val hasDataPackRegistries =
                definition.registrySync?.dataPackRegistries?.isNotEmpty() == true
            if (!definition.network.acceptsVanillaClient() || hasDataPackRegistries) {
                throw ForgeVanillaPeerRejectedException(
                    "Server Forge channels or data-pack registries require a Forge client",
                )
            }
            stage = ForgeServerStage.COMPLETE
            return
        }
        stage = ForgeServerStage.NEGOTIATING
        connection.outgoing.send(
            ForgeRegisterChannelsPacket(definition.network.payloadChannels),
        )

        expectedResponse = ForgeExpectedResponse.MOD_VERSIONS
        connection.outgoing.send(
            ForgeClientboundHandshakePacket(
                ForgeModVersionsMessage(definition.mods),
            ),
        )
        awaitExpected(connection)

        expectedResponse = ForgeExpectedResponse.CHANNEL_VERSIONS
        connection.outgoing.send(
            ForgeClientboundHandshakePacket(
                definition.network.versionsPacket,
            ),
        )
        awaitExpected(connection)

        synchronizeRegistries(connection)
        definition.configFiles.forEach { config ->
            connection.outgoing.send(
                ForgeClientboundHandshakePacket(config),
            )
        }
        stage = ForgeServerStage.COMPLETE
    }

    override suspend fun handleConfigurationPacket(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
        packet: ServerboundPacket,
    ): Boolean = when (packet) {
        is ForgeRegisterChannelsPacket -> {
            remoteChannels += packet.channels
            updateForgeRoutes(
                connection,
                definition.network.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
                PacketDirection.CLIENTBOUND,
            )
            true
        }

        is ForgeUnregisterChannelsPacket -> {
            remoteChannels -= packet.channels
            updateForgeRoutes(
                connection,
                definition.network.payloadChannels,
                remoteChannels,
                ConnectionState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
                PacketDirection.CLIENTBOUND,
            )
            true
        }

        is ForgeServerboundHandshakePacket -> {
            handleHandshakeMessage(packet.message)
            true
        }

        else -> false
    }

    override suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        val shared = definition.resolvedRegistryContext ?: return context
        val sectionCount = context.chunkSectionCount ?: return shared
        return if (shared.chunkSectionCount == sectionCount) {
            shared
        } else {
            shared.withChunkSectionCount(sectionCount)
        }
    }

    override suspend fun preparePlay(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        requireStage(ForgeServerStage.COMPLETE)
        updateForgeRoutes(
            connection,
            definition.network.payloadChannels,
            remoteChannels,
            ConnectionState.PLAY,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
    }

    override suspend fun complete(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ): NegotiationProfileResult {
        val intent = checkNotNull(handshakeIntent)
        return ForgeNegotiationResult(
            intent.forgePeer,
            intent.networkVersion.takeIf { intent.forgePeer },
            remoteChannels.toSet(),
            remoteMods.toMap(),
            remoteChannelVersions.toMap(),
            registrySynchronized,
            emptyList(),
        )
    }

    private fun handleHandshakeMessage(message: ForgeServerboundHandshakeMessage) {
        when (message) {
            is ForgeModVersionsMessage -> {
                requireExpected(ForgeExpectedResponse.MOD_VERSIONS)
                remoteMods.clear()
                remoteMods.putAll(message.mods)
                expectedResponse = null
            }

            is ForgeChannelVersionsMessage -> {
                requireExpected(ForgeExpectedResponse.CHANNEL_VERSIONS)
                val validation = definition.network.validateClient(message.channels)
                if (!validation.successful) {
                    throw ForgeChannelNegotiationException(
                        validation.toFailureMessage(),
                    )
                }
                remoteChannelVersions.clear()
                remoteChannelVersions.putAll(message.channels)
                expectedResponse = null
            }

            is ForgeAcknowledgeMessage -> {
                val expected = expectedAck
                    ?: throw ForgeNegotiationException(
                        "Unexpected Forge acknowledgement ${message.token}",
                    )
                if (message.token != expected) {
                    throw ForgeNegotiationException(
                        "Forge acknowledgement ${message.token} does not match $expected",
                    )
                }
                expectedAck = null
            }
        }
    }

    private suspend fun synchronizeRegistries(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        val sync = definition.registrySync
        var token = 0
        expectedAck = token
        connection.outgoing.send(
            ForgeClientboundHandshakePacket(
                ForgeRegistryListMessage(
                    token,
                    sync?.registryNames.orEmpty(),
                    sync?.dataPackRegistries?.toList().orEmpty(),
                ),
            ),
        )
        awaitExpected(connection)
        sync?.snapshots?.forEach { (registry, snapshot) ->
            token++
            expectedAck = token
            connection.outgoing.send(
                ForgeClientboundHandshakePacket(
                    ForgeRegistryDataMessage(token, registry, snapshot),
                ),
            )
            awaitExpected(connection)
        }
        registrySynchronized = true
    }

    private suspend fun awaitExpected(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        while (expectedResponse != null || expectedAck != null) {
            val packet = connection.incoming.receive()
            if (!handleConfigurationPacket(connection, packet)) {
                throw ForgeNegotiationException(
                    "Unexpected packet during Forge negotiation: ${packet::class.simpleName}",
                )
            }
        }
    }

    private fun requireExpected(expected: ForgeExpectedResponse) {
        if (expectedResponse != expected || expectedAck != null) {
            throw ForgeNegotiationException(
                "Forge response $expected arrived while waiting for $expectedResponse and ack $expectedAck",
            )
        }
    }

    private fun requireStage(expected: ForgeServerStage) {
        if (stage != expected) {
            throw ForgeNegotiationException(
                "Forge server profile is in $stage; expected $expected",
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

class ForgeMissingDataPackRegistriesException(
    val missing: Set<Identifier>,
) : ForgeNegotiationException(
    "Forge client is missing synchronized data-pack registries $missing",
)

class ForgeVanillaPeerRejectedException(
    message: String,
) : ForgeNegotiationException(message)

private suspend fun <Incoming : Packet, Outgoing : Packet> updateForgeRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    localChannels: Set<Identifier>,
    remoteChannels: Set<Identifier>,
    state: ConnectionState,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val candidates = connection.declaredExtensionRoutes
        .filterIsInstance<PacketRouteKey.CustomPayload>()
        .filter { route -> route.state == state }
        .toSet()
    val accepted = candidates.filter { route ->
        route.channel == ForgeChannels.Register ||
                route.channel == ForgeChannels.Unregister ||
                (
                        state == ConnectionState.CONFIGURATION &&
                                route.channel == ForgeChannels.Handshake
                        ) ||
                (
                        route.direction == incomingDirection &&
                                route.channel in localChannels
                        ) ||
                (
                        route.direction == outgoingDirection &&
                                route.channel in remoteChannels
                        )
    }
    val loginRoutes = connection.declaredExtensionRoutes.filter { route ->
        route is PacketRouteKey.LoginQuery
    }
    connection.activateExtensionRoutes(
        connection.activeExtensionRoutes - candidates + accepted + loginRoutes,
    )
}

private fun <Incoming : Packet, Outgoing : Packet> requireForgeCodecs(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
) {
    val required = ForgeProtocol.packetCodecs.mapTo(linkedSetOf()) { registration ->
        registration.route
    }
    val missing = required - connection.declaredExtensionRoutes
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
