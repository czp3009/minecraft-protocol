package com.hiczp.minecraft.protocol.fabric

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.*

data class FabricNegotiationResult(
    val commonVersion: Int?,
    val remoteConfigurationChannels: Set<Identifier>,
    val remotePlayChannels: Set<Identifier>,
    val registrySynchronized: Boolean,
) : NegotiationProfileResult

/** One-connection Fabric API client profile. */
class FabricClientProfile(
    val staticRegistries: StaticRegistrySchema,
    supportedCommonVersions: Set<Int> =
        setOf(FabricProtocol.COMMON_PACKET_VERSION),
) : ClientNegotiationProfile {
    private val supportedCommonVersions = supportedVersions(
        supportedCommonVersions,
    )
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private val splitAssembler = FabricSplitAssembler(setOf(FabricChannels.RegistrySync))
    private var commonVersion: Int? = null
    private var registrySync: FabricRegistrySyncPacket? = null
    private var sentInitialRegistration = false
    private var begun = false

    override suspend fun begin(
        connection: MinecraftClientPacketConnection,
    ) {
        check(!begun) { "A FabricClientProfile can negotiate only one connection" }
        begun = true
        requireFabricCodecs(connection)
        connection.activateExtensionRoutes(
            connection.activeExtensionRoutes +
                    initialRoutes(connection, PacketDirection.CLIENTBOUND),
        )
    }

    override suspend fun handleConfigurationPacket(
        connection: MinecraftClientPacketConnection,
        packet: ClientboundPacket,
    ): Boolean {
        if (splitAssembler.isCollecting && packet !is FabricSplitPacket) {
            throw FabricNegotiationException(
                "Received ${packet::class.simpleName} inside a Fabric split stream",
            )
        }
        return when (packet) {
            is FabricRegisterChannelsPacket -> {
                remoteConfigurationChannels += packet.channels
                activateAcceptedOutboundRoutes(
                    connection,
                    ConnectionState.CONFIGURATION,
                    PacketDirection.SERVERBOUND,
                    remoteConfigurationChannels,
                )
                if (!sentInitialRegistration) {
                    connection.outgoing.send(
                        FabricRegisterChannelsPacket(
                            receivableChannels(
                                connection,
                                ConnectionState.CONFIGURATION,
                                PacketDirection.CLIENTBOUND,
                            ).toList(),
                        ),
                    )
                    sentInitialRegistration = true
                }
                true
            }

            is FabricUnregisterChannelsPacket -> {
                remoteConfigurationChannels -= packet.channels.toSet()
                activateAcceptedOutboundRoutes(
                    connection,
                    ConnectionState.CONFIGURATION,
                    PacketDirection.SERVERBOUND,
                    remoteConfigurationChannels,
                )
                true
            }

            is FabricCommonVersionPacket -> {
                val negotiated = highestCommonVersion(
                    packet.versions,
                    supportedCommonVersions,
                )
                commonVersion = negotiated
                connection.outgoing.send(
                    FabricCommonVersionPacket(listOf(negotiated)),
                )
                true
            }

            is FabricCommonRegisterPacket -> {
                val negotiated = requireCommonVersion(packet.version)
                when (packet.protocol) {
                    CONFIGURATION_PROTOCOL -> {
                        remoteConfigurationChannels += packet.channels
                        activateAcceptedOutboundRoutes(
                            connection,
                            ConnectionState.CONFIGURATION,
                            PacketDirection.SERVERBOUND,
                            remoteConfigurationChannels,
                        )
                        connection.outgoing.send(
                            FabricCommonRegisterPacket(
                                negotiated,
                                CONFIGURATION_PROTOCOL,
                                receivableChannels(
                                    connection,
                                    ConnectionState.CONFIGURATION,
                                    PacketDirection.CLIENTBOUND,
                                ),
                            ),
                        )
                    }

                    PLAY_PROTOCOL -> {
                        remotePlayChannels += packet.channels
                        connection.outgoing.send(
                            FabricCommonRegisterPacket(
                                negotiated,
                                PLAY_PROTOCOL,
                                receivableChannels(
                                    connection,
                                    ConnectionState.PLAY,
                                    PacketDirection.CLIENTBOUND,
                                ),
                            ),
                        )
                    }

                    else -> throw FabricNegotiationException(
                        "Unknown Fabric common protocol ${packet.protocol}",
                    )
                }
                true
            }

            is FabricRegistrySyncPacket -> {
                applyRegistrySync(packet)
                connection.outgoing.send(FabricRegistrySyncCompletePacket)
                true
            }

            is FabricSplitPacket -> {
                val payload = splitAssembler.accept(
                    ConnectionState.CONFIGURATION,
                    PacketDirection.CLIENTBOUND,
                    packet,
                ) ?: return true
                when (val decoded = connection.decodeCustomPayload(payload)) {
                    is FabricRegistrySyncPacket -> {
                        applyRegistrySync(decoded)
                        connection.outgoing.send(
                            FabricRegistrySyncCompletePacket,
                        )
                    }

                    else -> throw FabricNegotiationException(
                        "Fabric split stream produced unexpected ${decoded::class.simpleName}",
                    )
                }
                true
            }

            else -> false
        }
    }

    override suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        val packet = registrySync ?: return context
        val compatible = compatibleSnapshot(packet)
        return context.withStaticRegistryResolution(
            staticRegistries.resolve(compatible),
        )
    }

    override suspend fun preparePlay(
        connection: MinecraftClientPacketConnection,
    ) {
        activatePlayRoutes(
            connection,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
            remotePlayChannels,
            commonVersion != null,
        )
    }

    override suspend fun complete(
        connection: MinecraftClientPacketConnection,
    ): NegotiationProfileResult = result()

    private fun applyRegistrySync(packet: FabricRegistrySyncPacket) {
        if (registrySync != null) {
            throw FabricNegotiationException(
                "Received more than one Fabric registry snapshot",
            )
        }
        compatibleSnapshot(packet)
        registrySync = packet
    }

    private fun compatibleSnapshot(
        packet: FabricRegistrySyncPacket,
    ): RemoteRegistrySnapshot {
        val compatible = packet.snapshot.registries.values.mapNotNull { registry ->
            val localEntries = staticRegistries.registries[registry.id]
            if (localEntries == null) {
                if (registry.id in packet.optionalRegistries) return@mapNotNull null
                throw FabricNegotiationException(
                    "Fabric server synchronized unknown mandatory registry ${registry.id}",
                )
            }
            val local = localEntries.toSet()
            val missing = registry.entries.map(RemoteRegistryEntry::id)
                .filterNot(local::contains)
            if (missing.isNotEmpty()) {
                throw FabricNegotiationException(
                    "Fabric registry ${registry.id} contains missing local entries: $missing",
                )
            }
            registry
        }
        return RemoteRegistrySnapshot(compatible)
    }

    private fun requireCommonVersion(version: Int): Int {
        val negotiated = commonVersion
            ?: throw FabricNegotiationException(
                "Fabric common channels were registered before version negotiation",
            )
        if (version != negotiated) {
            throw FabricNegotiationException(
                "Fabric common packet version $version does not match $negotiated",
            )
        }
        return negotiated
    }

    private fun result(): FabricNegotiationResult = FabricNegotiationResult(
        commonVersion,
        remoteConfigurationChannels.toSet(),
        remotePlayChannels.toSet(),
        registrySync != null,
    )
}

/** One-connection Fabric API server profile. */
class FabricServerProfile(
    val registrySync: FabricRegistrySyncPacket? = null,
    /** Caller-built immutable context; large registry structures are retained by reference. */
    val resolvedRegistryContext: ProtocolRegistryContext? = null,
    supportedCommonVersions: Set<Int> =
        setOf(FabricProtocol.COMMON_PACKET_VERSION),
) : ServerNegotiationProfile {
    private val supportedCommonVersions = supportedVersions(
        supportedCommonVersions,
    )
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private var commonVersion: Int? = null
    private var registrySynchronized = false
    private var receivedInitialRegistration = false
    private var receivedProbePong = false
    private var begun = false

    override suspend fun begin(
        connection: MinecraftServerPacketConnection,
    ) {
        check(!begun) { "A FabricServerProfile can negotiate only one connection" }
        begun = true
        requireFabricCodecs(connection)
        connection.activateExtensionRoutes(
            connection.activeExtensionRoutes +
                    initialRoutes(connection, PacketDirection.SERVERBOUND),
        )
    }

    override suspend fun negotiateConfiguration(
        connection: MinecraftServerPacketConnection,
    ) {
        connection.outgoing.send(
            FabricRegisterChannelsPacket(
                receivableChannels(
                    connection,
                    ConnectionState.CONFIGURATION,
                    PacketDirection.SERVERBOUND,
                ).toList(),
            ),
        )
        connection.outgoing.send(ConfigurationPingPacket(FABRIC_PROBE_ID))
        while (!receivedInitialRegistration && !receivedProbePong) {
            connection.requestFlush()
            val packet = connection.incoming.receive()
            if (!handleConfigurationPacket(connection, packet)) {
                throw FabricNegotiationException(
                    "Expected Fabric channel registration or probe pong, received ${packet::class.simpleName}",
                )
            }
        }
        if (!receivedInitialRegistration) return

        if (FabricChannels.CommonVersion in remoteConfigurationChannels) {
            connection.outgoing.send(
                FabricCommonVersionPacket(
                    supportedCommonVersions.sorted(),
                ),
            )
            awaitConfigurationPacket<FabricCommonVersionPacket>(connection)
        }
        if (
            commonVersion != null &&
            FabricChannels.CommonRegister in remoteConfigurationChannels
        ) {
            connection.outgoing.send(
                FabricCommonRegisterPacket(
                    checkNotNull(commonVersion),
                    PLAY_PROTOCOL,
                    receivableChannels(
                        connection,
                        ConnectionState.PLAY,
                        PacketDirection.SERVERBOUND,
                    ),
                ),
            )
            awaitConfigurationPacket<FabricCommonRegisterPacket>(connection)
        }

        val sync = registrySync ?: return
        if (FabricChannels.RegistrySync !in remoteConfigurationChannels) {
            if (
                sync.snapshot.registries.keys.any {
                    it !in sync.optionalRegistries
                }
            ) {
                throw FabricNegotiationException(
                    "Client did not advertise mandatory Fabric registry synchronization",
                )
            }
            return
        }
        val routed = connection.encodeCustomPayload(sync)
        val encodedSize = FabricSplitPayloads.encodedPacketSize(routed)
        if (encodedSize >= FabricSplitPayloads.CLIENTBOUND_CHUNK_SIZE) {
            FabricSplitPayloads.split(
                routed,
                FabricSplitPayloads.CLIENTBOUND_CHUNK_SIZE,
            ).forEach { connection.outgoing.send(it) }
        } else {
            connection.outgoing.send(sync)
        }
        awaitConfigurationPacket<FabricRegistrySyncCompletePacket>(connection)
    }

    override suspend fun handleConfigurationPacket(
        connection: MinecraftServerPacketConnection,
        packet: ServerboundPacket,
    ): Boolean = when (packet) {
        is FabricRegisterChannelsPacket -> {
            remoteConfigurationChannels += packet.channels
            receivedInitialRegistration = true
            activateAcceptedOutboundRoutes(
                connection,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                remoteConfigurationChannels,
            )
            true
        }

        is FabricUnregisterChannelsPacket -> {
            remoteConfigurationChannels -= packet.channels.toSet()
            activateAcceptedOutboundRoutes(
                connection,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                remoteConfigurationChannels,
            )
            true
        }

        is ConfigurationPongPacket -> {
            if (packet.id != FABRIC_PROBE_ID) return false
            receivedProbePong = true
            true
        }

        is FabricCommonVersionPacket -> {
            commonVersion = highestCommonVersion(
                packet.versions,
                supportedCommonVersions,
            )
            true
        }

        is FabricCommonRegisterPacket -> {
            requireCommonVersion(packet.version)
            when (packet.protocol) {
                PLAY_PROTOCOL -> remotePlayChannels += packet.channels
                CONFIGURATION_PROTOCOL ->
                    remoteConfigurationChannels += packet.channels

                else -> throw FabricNegotiationException(
                    "Unknown Fabric common protocol ${packet.protocol}",
                )
            }
            true
        }

        FabricRegistrySyncCompletePacket -> {
            registrySynchronized = true
            true
        }

        else -> false
    }

    override suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        val shared = resolvedRegistryContext ?: return context
        val sectionCount = context.chunkSectionCount ?: return shared
        return if (shared.chunkSectionCount == sectionCount) {
            shared
        } else {
            shared.withChunkSectionCount(sectionCount)
        }
    }

    override suspend fun preparePlay(
        connection: MinecraftServerPacketConnection,
    ) {
        activatePlayRoutes(
            connection,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
            remotePlayChannels,
            receivedInitialRegistration && commonVersion != null,
        )
    }

    override suspend fun complete(
        connection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult = FabricNegotiationResult(
        commonVersion,
        remoteConfigurationChannels.toSet(),
        remotePlayChannels.toSet(),
        registrySynchronized,
    )

    private suspend inline fun <reified T : ServerboundPacket>
            awaitConfigurationPacket(
        connection: MinecraftServerPacketConnection,
    ): T {
        while (true) {
            connection.requestFlush()
            val packet = connection.incoming.receive()
            if (packet is T) {
                handleConfigurationPacket(connection, packet)
                return packet
            }
            if (!handleConfigurationPacket(connection, packet)) {
                throw FabricNegotiationException(
                    "Expected ${T::class.simpleName}, received ${packet::class.simpleName}",
                )
            }
        }
    }

    private fun requireCommonVersion(version: Int) {
        val negotiated = commonVersion
            ?: throw FabricNegotiationException(
                "Fabric common channels were registered before version negotiation",
            )
        if (version != negotiated) {
            throw FabricNegotiationException(
                "Fabric common packet version $version does not match $negotiated",
            )
        }
    }
}

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activatePlayRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
    remoteChannels: Set<Identifier>,
    fabricPeer: Boolean,
) {
    if (!fabricPeer) return
    val inbound = customRoutes(
        connection,
        ConnectionState.PLAY,
        incomingDirection,
    )
    val outbound = customRoutes(
        connection,
        ConnectionState.PLAY,
        outgoingDirection,
    ).filter { route ->
        route.channel in remoteChannels || route.channel in INFRASTRUCTURE_CHANNELS
    }
    connection.activateExtensionRoutes(
        connection.activeExtensionRoutes + inbound + outbound,
    )
}

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activateAcceptedOutboundRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    state: ConnectionState,
    direction: PacketDirection,
    remoteChannels: Set<Identifier>,
) {
    val candidates = customRoutes(connection, state, direction)
    val accepted = candidates.filter { route ->
        route.channel in remoteChannels || route.channel in INFRASTRUCTURE_CHANNELS
    }
    connection.activateExtensionRoutes(
        connection.activeExtensionRoutes - candidates.toSet() + accepted,
    )
}

private fun <Incoming : Packet, Outgoing : Packet> initialRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    incomingDirection: PacketDirection,
): Set<PacketRouteKey> = buildSet {
    addAll(
        connection.declaredExtensionRoutes.filter { route ->
            route is PacketRouteKey.LoginQuery
        },
    )
    addAll(
        customRoutes(
            connection,
            ConnectionState.CONFIGURATION,
            incomingDirection,
        ),
    )
    addAll(
        connection.declaredExtensionRoutes.filter { route ->
            route is PacketRouteKey.CustomPayload &&
                    route.state == ConnectionState.CONFIGURATION &&
                    route.channel in INFRASTRUCTURE_CHANNELS
        },
    )
}

private fun <Incoming : Packet, Outgoing : Packet> receivableChannels(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    state: ConnectionState,
    direction: PacketDirection,
): Set<Identifier> = customRoutes(connection, state, direction)
    .map(PacketRouteKey.CustomPayload::channel)
    .filterNot(INFRASTRUCTURE_CHANNELS::contains)
    .toSet()

private fun <Incoming : Packet, Outgoing : Packet> customRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    state: ConnectionState,
    direction: PacketDirection,
): Set<PacketRouteKey.CustomPayload> = connection.declaredExtensionRoutes
    .filterIsInstance<PacketRouteKey.CustomPayload>()
    .filter { route ->
        route.state == state && route.direction == direction
    }
    .toSet()

private fun <Incoming : Packet, Outgoing : Packet> requireFabricCodecs(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
) {
    val missing = REQUIRED_CONFIGURATION_ROUTES -
            connection.declaredExtensionRoutes
    require(missing.isEmpty()) {
        "Fabric profile requires FabricProtocol packet codecs; missing $missing"
    }
}

private fun supportedVersions(versions: Set<Int>): Set<Int> =
    versions.toSet().also { snapshot ->
        require(snapshot.isNotEmpty()) {
            "Fabric common version set must not be empty"
        }
        require(snapshot.all { it > 0 }) {
            "Fabric common versions must be positive"
        }
        require(snapshot.size <= FabricProtocol.MAX_COMMON_VERSIONS) {
            "Fabric common version set is too large"
        }
    }

private fun highestCommonVersion(
    remote: Collection<Int>,
    local: Set<Int>,
): Int = remote.filter(local::contains).maxOrNull()
    ?.takeIf { it > 0 }
    ?: throw FabricNegotiationException(
        "No mutually supported Fabric common packet version",
    )

private const val CONFIGURATION_PROTOCOL = "configuration"
private const val PLAY_PROTOCOL = "play"
private const val FABRIC_PROBE_ID = 0xFAB71C

private val INFRASTRUCTURE_CHANNELS = setOf(
    FabricChannels.Register,
    FabricChannels.Unregister,
    FabricChannels.Split,
)

private val REQUIRED_CONFIGURATION_ROUTES: Set<PacketRouteKey> =
    FabricProtocol.packetCodecs.map { registration -> registration.route }
        .filter { route -> route.state == ConnectionState.CONFIGURATION }
        .toSet()

class FabricNegotiationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
