package com.hiczp.minecraft.protocol.fabric

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.*

data class FabricNegotiationResult(
    val commonVersion: Int?,
    val remoteConfigurationChannels: Set<Identifier>,
    val remotePlayChannels: Set<Identifier>,
    val registriesSynchronized: Boolean,
) : NegotiationProfileResult

/** One-connection Fabric API client profile. */
class FabricClientProfile(
    val staticRegistrySchema: StaticRegistrySchema,
    supportedCommonVersions: Set<Int> =
        setOf(FabricProtocol.COMMON_PACKET_VERSION),
) : ClientNegotiationProfile {
    private val supportedCommonVersions = supportedVersions(
        supportedCommonVersions,
    )
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private val fabricSplitAssembler = FabricSplitAssembler(setOf(FabricChannels.RegistrySync))
    private var commonVersion: Int? = null
    private var fabricRegistrySyncPacket: FabricRegistrySyncPacket? = null
    private var sentInitialRegistration = false
    private var begun = false

    override suspend fun begin(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        check(!begun) { "A FabricClientProfile can negotiate only one connection" }
        begun = true
        requireFabricCodecs(minecraftClientPacketConnection)
        minecraftClientPacketConnection.activateExtensionRoutes(buildSet {
            addAll(minecraftClientPacketConnection.activeExtensionRoutes)
            addAll(initialRoutes(minecraftClientPacketConnection, PacketDirection.CLIENTBOUND))
        })
    }

    override suspend fun handleConfigurationPacket(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        clientboundPacket: ClientboundPacket,
    ): Boolean {
        if (fabricSplitAssembler.isCollecting && clientboundPacket !is FabricSplitPacket) {
            throw FabricNegotiationException(
                "Received ${clientboundPacket::class.simpleName} inside a Fabric split stream",
            )
        }
        return when (clientboundPacket) {
            is FabricRegisterChannelsPacket -> {
                remoteConfigurationChannels += clientboundPacket.channels
                activateAcceptedOutboundRoutes(
                    minecraftClientPacketConnection,
                    ConnectionState.CONFIGURATION,
                    PacketDirection.SERVERBOUND,
                    remoteConfigurationChannels,
                )
                if (!sentInitialRegistration) {
                    minecraftClientPacketConnection.outgoing.send(
                        FabricRegisterChannelsPacket(
                            receivableChannels(
                                minecraftClientPacketConnection,
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
                clientboundPacket.channels.forEach(remoteConfigurationChannels::remove)
                activateAcceptedOutboundRoutes(
                    minecraftClientPacketConnection,
                    ConnectionState.CONFIGURATION,
                    PacketDirection.SERVERBOUND,
                    remoteConfigurationChannels,
                )
                true
            }

            is FabricCommonVersionPacket -> {
                val negotiated = highestCommonVersion(
                    clientboundPacket.versions,
                    supportedCommonVersions,
                )
                commonVersion = negotiated
                minecraftClientPacketConnection.outgoing.send(
                    FabricCommonVersionPacket(listOf(negotiated)),
                )
                true
            }

            is FabricCommonRegisterPacket -> {
                val negotiated = requireCommonVersion(clientboundPacket.version)
                when (clientboundPacket.protocol) {
                    CONFIGURATION_PROTOCOL -> {
                        remoteConfigurationChannels += clientboundPacket.channels
                        activateAcceptedOutboundRoutes(
                            minecraftClientPacketConnection,
                            ConnectionState.CONFIGURATION,
                            PacketDirection.SERVERBOUND,
                            remoteConfigurationChannels,
                        )
                        minecraftClientPacketConnection.outgoing.send(
                            FabricCommonRegisterPacket(
                                negotiated,
                                CONFIGURATION_PROTOCOL,
                                receivableChannels(
                                    minecraftClientPacketConnection,
                                    ConnectionState.CONFIGURATION,
                                    PacketDirection.CLIENTBOUND,
                                ),
                            ),
                        )
                    }

                    PLAY_PROTOCOL -> {
                        remotePlayChannels += clientboundPacket.channels
                        minecraftClientPacketConnection.outgoing.send(
                            FabricCommonRegisterPacket(
                                negotiated,
                                PLAY_PROTOCOL,
                                receivableChannels(
                                    minecraftClientPacketConnection,
                                    ConnectionState.PLAY,
                                    PacketDirection.CLIENTBOUND,
                                ),
                            ),
                        )
                    }

                    else -> throw FabricNegotiationException(
                        "Unknown Fabric common protocol ${clientboundPacket.protocol}",
                    )
                }
                true
            }

            is FabricRegistrySyncPacket -> {
                applyRegistrySync(clientboundPacket)
                minecraftClientPacketConnection.outgoing.send(FabricRegistrySyncCompletePacket)
                true
            }

            is FabricSplitPacket -> {
                val routedCustomPayload = fabricSplitAssembler.accept(
                    ConnectionState.CONFIGURATION,
                    PacketDirection.CLIENTBOUND,
                    clientboundPacket,
                ) ?: return true
                when (val decoded = minecraftClientPacketConnection.decodeCustomPayload(routedCustomPayload)) {
                    is FabricRegistrySyncPacket -> {
                        applyRegistrySync(decoded)
                        minecraftClientPacketConnection.outgoing.send(
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

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        val fabricRegistrySyncPacket = this.fabricRegistrySyncPacket ?: return protocolRegistryContext
        val remoteRegistrySnapshot = compatibleSnapshot(fabricRegistrySyncPacket)
        return protocolRegistryContext.withStaticRegistryResolution(
            staticRegistrySchema.resolve(remoteRegistrySnapshot),
        )
    }

    override suspend fun preparePlay(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        activatePlayRoutes(
            minecraftClientPacketConnection,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
            remotePlayChannels,
            commonVersion != null,
        )
    }

    override suspend fun complete(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ): NegotiationProfileResult = result()

    private fun applyRegistrySync(fabricRegistrySyncPacket: FabricRegistrySyncPacket) {
        if (this.fabricRegistrySyncPacket != null) {
            throw FabricNegotiationException(
                "Received more than one Fabric registry snapshot",
            )
        }
        compatibleSnapshot(fabricRegistrySyncPacket)
        this.fabricRegistrySyncPacket = fabricRegistrySyncPacket
    }

    private fun compatibleSnapshot(
        fabricRegistrySyncPacket: FabricRegistrySyncPacket,
    ): RemoteRegistrySnapshot {
        val compatibleRegistries =
            fabricRegistrySyncPacket.remoteRegistrySnapshot.registries.values.mapNotNull { remoteRegistry ->
                val localEntries = staticRegistrySchema.registries[remoteRegistry.id]
                if (localEntries == null) {
                    if (remoteRegistry.id in fabricRegistrySyncPacket.optionalRegistryIds) return@mapNotNull null
                    throw FabricNegotiationException(
                        "Fabric server synchronized unknown mandatory registry ${remoteRegistry.id}",
                    )
                }
                val local = localEntries.toSet()
                val missing = remoteRegistry.entries.map(RemoteRegistryEntry::id)
                    .filterNot(local::contains)
                if (missing.isNotEmpty()) {
                    throw FabricNegotiationException(
                        "Fabric registry ${remoteRegistry.id} contains missing local entries: $missing",
                    )
                }
                remoteRegistry
            }
        return RemoteRegistrySnapshot(compatibleRegistries)
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
        fabricRegistrySyncPacket != null,
    )
}

/** One-connection Fabric API server profile. */
class FabricServerProfile(
    val fabricRegistrySyncPacket: FabricRegistrySyncPacket? = null,
    /** Caller-built context retained by reference across connections. */
    val protocolRegistryContext: ProtocolRegistryContext? = null,
    supportedCommonVersions: Set<Int> =
        setOf(FabricProtocol.COMMON_PACKET_VERSION),
) : ServerNegotiationProfile {
    private val supportedCommonVersions = supportedVersions(
        supportedCommonVersions,
    )
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private var commonVersion: Int? = null
    private var registriesSynchronized = false
    private var receivedInitialRegistration = false
    private var receivedProbePong = false
    private var begun = false

    override suspend fun begin(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        check(!begun) { "A FabricServerProfile can negotiate only one connection" }
        begun = true
        requireFabricCodecs(minecraftServerPacketConnection)
        minecraftServerPacketConnection.activateExtensionRoutes(buildSet {
            addAll(minecraftServerPacketConnection.activeExtensionRoutes)
            addAll(initialRoutes(minecraftServerPacketConnection, PacketDirection.SERVERBOUND))
        })
    }

    override suspend fun negotiateConfiguration(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        minecraftServerPacketConnection.outgoing.send(
            FabricRegisterChannelsPacket(
                receivableChannels(
                    minecraftServerPacketConnection,
                    ConnectionState.CONFIGURATION,
                    PacketDirection.SERVERBOUND,
                ).toList(),
            ),
        )
        minecraftServerPacketConnection.outgoing.send(ConfigurationPingPacket(FABRIC_PROBE_ID))
        while (!receivedInitialRegistration && !receivedProbePong) {
            minecraftServerPacketConnection.requestFlush()
            val serverboundPacket = minecraftServerPacketConnection.incoming.receive()
            if (!handleConfigurationPacket(minecraftServerPacketConnection, serverboundPacket)) {
                throw FabricNegotiationException(
                    "Expected Fabric channel registration or probe pong, received ${serverboundPacket::class.simpleName}",
                )
            }
        }
        if (!receivedInitialRegistration) return

        if (FabricChannels.CommonVersion in remoteConfigurationChannels) {
            minecraftServerPacketConnection.outgoing.send(
                FabricCommonVersionPacket(
                    supportedCommonVersions.sorted(),
                ),
            )
            awaitConfigurationPacket<FabricCommonVersionPacket>(minecraftServerPacketConnection)
        }
        if (
            commonVersion != null &&
            FabricChannels.CommonRegister in remoteConfigurationChannels
        ) {
            minecraftServerPacketConnection.outgoing.send(
                FabricCommonRegisterPacket(
                    checkNotNull(commonVersion),
                    PLAY_PROTOCOL,
                    receivableChannels(
                        minecraftServerPacketConnection,
                        ConnectionState.PLAY,
                        PacketDirection.SERVERBOUND,
                    ),
                ),
            )
            awaitConfigurationPacket<FabricCommonRegisterPacket>(minecraftServerPacketConnection)
        }

        val fabricRegistrySyncPacket = this.fabricRegistrySyncPacket ?: return
        if (FabricChannels.RegistrySync !in remoteConfigurationChannels) {
            if (
                fabricRegistrySyncPacket.remoteRegistrySnapshot.registries.keys.any {
                    it !in fabricRegistrySyncPacket.optionalRegistryIds
                }
            ) {
                throw FabricNegotiationException(
                    "Client did not advertise mandatory Fabric registry synchronization",
                )
            }
            return
        }
        val routedCustomPayload = minecraftServerPacketConnection.encodeCustomPayload(fabricRegistrySyncPacket)
        val encodedSize = FabricSplitPayloads.encodedPacketSize(routedCustomPayload)
        if (encodedSize >= FabricSplitPayloads.CLIENTBOUND_CHUNK_SIZE) {
            FabricSplitPayloads.split(
                routedCustomPayload,
                FabricSplitPayloads.CLIENTBOUND_CHUNK_SIZE,
            ).forEach { minecraftServerPacketConnection.outgoing.send(it) }
        } else {
            minecraftServerPacketConnection.outgoing.send(fabricRegistrySyncPacket)
        }
        awaitConfigurationPacket<FabricRegistrySyncCompletePacket>(minecraftServerPacketConnection)
    }

    override suspend fun handleConfigurationPacket(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
        serverboundPacket: ServerboundPacket,
    ): Boolean = when (serverboundPacket) {
        is FabricRegisterChannelsPacket -> {
            remoteConfigurationChannels += serverboundPacket.channels
            receivedInitialRegistration = true
            activateAcceptedOutboundRoutes(
                minecraftServerPacketConnection,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                remoteConfigurationChannels,
            )
            true
        }

        is FabricUnregisterChannelsPacket -> {
            serverboundPacket.channels.forEach(remoteConfigurationChannels::remove)
            activateAcceptedOutboundRoutes(
                minecraftServerPacketConnection,
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                remoteConfigurationChannels,
            )
            true
        }

        is ConfigurationPongPacket -> {
            if (serverboundPacket.id != FABRIC_PROBE_ID) return false
            receivedProbePong = true
            true
        }

        is FabricCommonVersionPacket -> {
            commonVersion = highestCommonVersion(
                serverboundPacket.versions,
                supportedCommonVersions,
            )
            true
        }

        is FabricCommonRegisterPacket -> {
            requireCommonVersion(serverboundPacket.version)
            when (serverboundPacket.protocol) {
                PLAY_PROTOCOL -> remotePlayChannels += serverboundPacket.channels
                CONFIGURATION_PROTOCOL ->
                    remoteConfigurationChannels += serverboundPacket.channels

                else -> throw FabricNegotiationException(
                    "Unknown Fabric common protocol ${serverboundPacket.protocol}",
                )
            }
            true
        }

        FabricRegistrySyncCompletePacket -> {
            registriesSynchronized = true
            true
        }

        else -> false
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        val sharedProtocolRegistryContext = this.protocolRegistryContext ?: return protocolRegistryContext
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
        activatePlayRoutes(
            minecraftServerPacketConnection,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
            remotePlayChannels,
            receivedInitialRegistration && commonVersion != null,
        )
    }

    override suspend fun complete(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult = FabricNegotiationResult(
        commonVersion,
        remoteConfigurationChannels.toSet(),
        remotePlayChannels.toSet(),
        registriesSynchronized,
    )

    private suspend inline fun <reified T : ServerboundPacket>
            awaitConfigurationPacket(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): T {
        while (true) {
            minecraftServerPacketConnection.requestFlush()
            val packet = minecraftServerPacketConnection.incoming.receive()
            if (packet is T) {
                handleConfigurationPacket(minecraftServerPacketConnection, packet)
                return packet
            }
            if (!handleConfigurationPacket(minecraftServerPacketConnection, packet)) {
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
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
    remoteChannels: Set<Identifier>,
    fabricPeer: Boolean,
) {
    if (!fabricPeer) return
    val inbound = customRoutes(
        minecraftPacketConnection,
        ConnectionState.PLAY,
        incomingDirection,
    )
    val outbound = customRoutes(
        minecraftPacketConnection,
        ConnectionState.PLAY,
        outgoingDirection,
    ).filter { customPayload ->
        customPayload.channel in remoteChannels || customPayload.channel in INFRASTRUCTURE_CHANNELS
    }
    minecraftPacketConnection.activateExtensionRoutes(buildSet {
        addAll(minecraftPacketConnection.activeExtensionRoutes)
        addAll(inbound)
        addAll(outbound)
    })
}

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activateAcceptedOutboundRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    connectionState: ConnectionState,
    packetDirection: PacketDirection,
    remoteChannels: Set<Identifier>,
) {
    val candidates = customRoutes(minecraftPacketConnection, connectionState, packetDirection)
    val accepted = candidates.filter { customPayload ->
        customPayload.channel in remoteChannels || customPayload.channel in INFRASTRUCTURE_CHANNELS
    }
    minecraftPacketConnection.activateExtensionRoutes(buildSet {
        addAll(minecraftPacketConnection.activeExtensionRoutes)
        removeAll(candidates)
        addAll(accepted)
    })
}

private fun <Incoming : Packet, Outgoing : Packet> initialRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    incomingDirection: PacketDirection,
): Set<PacketRouteKey> = buildSet {
    addAll(
        minecraftPacketConnection.declaredExtensionRoutes.filter { packetRouteKey ->
            packetRouteKey is PacketRouteKey.LoginQuery
        },
    )
    addAll(
        customRoutes(
            minecraftPacketConnection,
            ConnectionState.CONFIGURATION,
            incomingDirection,
        ),
    )
    addAll(
        minecraftPacketConnection.declaredExtensionRoutes.filter { packetRouteKey ->
            packetRouteKey is PacketRouteKey.CustomPayload &&
                    packetRouteKey.connectionState == ConnectionState.CONFIGURATION &&
                    packetRouteKey.channel in INFRASTRUCTURE_CHANNELS
        },
    )
}

private fun <Incoming : Packet, Outgoing : Packet> receivableChannels(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    connectionState: ConnectionState,
    packetDirection: PacketDirection,
): Set<Identifier> = customRoutes(minecraftPacketConnection, connectionState, packetDirection)
    .map(PacketRouteKey.CustomPayload::channel)
    .filterNot(INFRASTRUCTURE_CHANNELS::contains)
    .toSet()

private fun <Incoming : Packet, Outgoing : Packet> customRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    connectionState: ConnectionState,
    packetDirection: PacketDirection,
): Set<PacketRouteKey.CustomPayload> = minecraftPacketConnection.declaredExtensionRoutes
    .filterIsInstance<PacketRouteKey.CustomPayload>()
    .filter { route ->
        route.connectionState == connectionState && route.packetDirection == packetDirection
    }
    .toSet()

private fun <Incoming : Packet, Outgoing : Packet> requireFabricCodecs(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
) {
    val missing = REQUIRED_CONFIGURATION_ROUTES -
            minecraftPacketConnection.declaredExtensionRoutes
    require(missing.isEmpty()) {
        "Fabric profile requires FabricProtocol packet codecs; missing $missing"
    }
}

private fun supportedVersions(versions: Set<Int>): Set<Int> =
    versions.also {
        require(versions.isNotEmpty()) {
            "Fabric common version set must not be empty"
        }
        require(versions.all { it > 0 }) {
            "Fabric common versions must be positive"
        }
        require(versions.size <= FabricProtocol.MAX_COMMON_VERSIONS) {
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
    FabricProtocol.packetCodecs.map { registration -> registration.packetRouteKey }
        .filter { route -> route.connectionState == ConnectionState.CONFIGURATION }
        .toSet()

class FabricNegotiationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
