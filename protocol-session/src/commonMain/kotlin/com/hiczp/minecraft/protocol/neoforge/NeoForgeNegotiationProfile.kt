package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.RemoteRegistrySnapshot
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema
import com.hiczp.minecraft.protocol.session.ClientNegotiationProfile
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.ServerNegotiationProfile

class NeoForgeClientProfileDefinition(
    val staticRegistries: StaticRegistrySchema,
    val network: NeoForgeNetworkConfiguration = NeoForgeNetworkConfiguration(),
    knownDataMaps: Map<Identifier, List<NeoForgeKnownDataMap>> = emptyMap(),
    extensibleEnums: List<NeoForgeEnumEntry> = emptyList(),
    featureFlags: Set<Identifier> = emptySet(),
    supportedCommonVersions: Set<Int> =
        setOf(NeoForgeProtocol.COMMON_PACKET_VERSION),
    val maximumSplitPacketSize: Int = NeoForgeProtocol.DEFAULT_MAXIMUM_SPLIT_PACKET_SIZE,
) {
    val knownDataMaps: Map<Identifier, List<NeoForgeKnownDataMap>> =
        knownDataMaps.entries.associate { (registry, maps) ->
            registry to maps.toList()
        }
    val extensibleEnums: List<NeoForgeEnumEntry> = extensibleEnums.toList()
    val featureFlags: Set<Identifier> = featureFlags.toSet()
    val supportedCommonVersions: Set<Int> = validateCommonVersions(supportedCommonVersions)

    init {
        require(
            this.extensibleEnums.distinctBy(NeoForgeEnumEntry::className).size ==
                    this.extensibleEnums.size
        ) {
            "NeoForge client extensible enums contain duplicate class names"
        }
        require(maximumSplitPacketSize > 0)
    }
}

class NeoForgeServerProfileDefinition(
    val network: NeoForgeNetworkConfiguration = NeoForgeNetworkConfiguration(),
    val frozenRegistries: NeoForgeFrozenRegistrySync? = null,
    /** Caller-built immutable context retained by reference across connections. */
    val resolvedRegistryContext: ProtocolRegistryContext? = null,
    configFiles: List<NeoForgeConfigFilePacket> = emptyList(),
    knownDataMaps: Map<Identifier, List<NeoForgeKnownDataMap>> = emptyMap(),
    extensibleEnums: List<NeoForgeEnumEntry> = emptyList(),
    featureFlags: Set<Identifier> = emptySet(),
    supportedCommonVersions: Set<Int> =
        setOf(NeoForgeProtocol.COMMON_PACKET_VERSION),
    val maximumSplitPacketSize: Int = NeoForgeProtocol.DEFAULT_MAXIMUM_SPLIT_PACKET_SIZE,
) {
    val configFiles: List<NeoForgeConfigFilePacket> = configFiles.toList()
    val knownDataMaps: Map<Identifier, List<NeoForgeKnownDataMap>> =
        knownDataMaps.entries.associate { (registry, maps) ->
            registry to maps.toList()
        }
    val extensibleEnums: List<NeoForgeEnumEntry> = extensibleEnums.toList()
    val featureFlags: Set<Identifier> = featureFlags.toSet()
    val supportedCommonVersions: Set<Int> = validateCommonVersions(supportedCommonVersions)

    init {
        require(
            this.extensibleEnums.distinctBy(NeoForgeEnumEntry::className).size ==
                    this.extensibleEnums.size
        ) {
            "NeoForge server extensible enums contain duplicate class names"
        }
        require(maximumSplitPacketSize > 0)
    }
}

data class NeoForgeNegotiationResult(
    val neoForgePeer: Boolean,
    val networkSetup: NeoForgeNetworkSetup,
    val commonVersion: Int?,
    val remoteConfigurationChannels: Set<Identifier>,
    val remotePlayChannels: Set<Identifier>,
    val registrySynchronized: Boolean,
    val configFiles: List<NeoForgeConfigFilePacket>,
    val remoteKnownDataMaps: Map<Identifier, List<Identifier>>,
) : NegotiationProfileResult

class NeoForgeClientProfile(
    val definition: NeoForgeClientProfileDefinition,
) : ClientNegotiationProfile {
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private val configFiles = mutableListOf<NeoForgeConfigFilePacket>()
    private val frozenPackets = linkedMapOf<Identifier, NeoForgeFrozenRegistryPacket>()
    private val splitAssembler = NeoForgeSplitAssembler(
        definition.maximumSplitPacketSize,
    )
    private var expectedFrozenRegistries: Set<Identifier>? = null
    private var frozenSnapshot: RemoteRegistrySnapshot? = null
    private var networkSetup: NeoForgeNetworkSetup? = null
    private var commonVersion: Int? = null
    private var remoteKnownDataMaps: Map<Identifier, List<Identifier>> = emptyMap()
    private var sentInitialRegistration = false
    private var sentNetworkQuery = false
    private var lateTaskRank = 0
    private var begun = false

    override suspend fun begin(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ) {
        check(!begun) { "A NeoForgeClientProfile can negotiate only one connection" }
        begun = true
        requireNeoForgeCodecs(connection, definition.network)
        activateInitialConfigurationRoutes(
            connection,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
    }

    override suspend fun handleConfigurationPacket(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
        packet: ClientboundPacket,
    ): Boolean = when (packet) {
        is NeoForgeRegisterChannelsPacket -> {
            remoteConfigurationChannels += packet.channels
            if (!sentInitialRegistration) {
                sentInitialRegistration = true
                connection.outgoing.send(
                    NeoForgeRegisterChannelsPacket(INITIAL_CHANNELS),
                )
            }
            true
        }

        is NeoForgeUnregisterChannelsPacket -> {
            remoteConfigurationChannels -= packet.channels
            true
        }

        is NeoForgeModdedNetworkQueryPacket -> {
            if (sentNetworkQuery) {
                throw NeoForgeNegotiationException(
                    "Server sent more than one NeoForge network query",
                )
            }
            sentNetworkQuery = true
            connection.outgoing.send(definition.network.queryPacket)
            true
        }

        is NeoForgeModdedNetworkPacket -> {
            if (!sentNetworkQuery || networkSetup != null) {
                throw NeoForgeNegotiationException(
                    "NeoForge network setup arrived out of order",
                )
            }
            definition.network.validateSetup(packet.setup)
            networkSetup = packet.setup
            activateConfigurationSetupRoutes(
                connection,
                packet.setup,
                PacketDirection.CLIENTBOUND,
                PacketDirection.SERVERBOUND,
            )
            connection.outgoing.send(
                NeoForgeRegisterChannelsPacket(
                    INITIAL_CHANNELS +
                            packet.setup.channels(
                                NeoForgeConnectionProtocol.CONFIGURATION,
                            ).keys,
                ),
            )
            true
        }

        is NeoForgeModdedNetworkSetupFailedPacket ->
            throw NeoForgeRemoteSetupFailedException(packet)

        is NeoForgeFrozenRegistrySyncStartPacket -> {
            if (expectedFrozenRegistries != null || lateTaskRank != 0) {
                throw NeoForgeNegotiationException(
                    "NeoForge frozen registry sync started out of order",
                )
            }
            if (packet.registries.distinct().size != packet.registries.size) {
                throw NeoForgeNegotiationException(
                    "NeoForge frozen registry start contains duplicates",
                )
            }
            expectedFrozenRegistries = packet.registries.toSet()
            frozenPackets.clear()
            true
        }

        is NeoForgeFrozenRegistryPacket -> {
            val expected = expectedFrozenRegistries
                ?: throw NeoForgeNegotiationException(
                    "NeoForge frozen registry arrived before sync start",
                )
            if (packet.registry !in expected) {
                throw NeoForgeNegotiationException(
                    "Unexpected NeoForge frozen registry ${packet.registry}",
                )
            }
            if (frozenPackets.put(packet.registry, packet) != null) {
                throw NeoForgeNegotiationException(
                    "Duplicate NeoForge frozen registry ${packet.registry}",
                )
            }
            true
        }

        NeoForgeFrozenRegistrySyncCompletedPacket -> {
            val expected = expectedFrozenRegistries
                ?: throw NeoForgeNegotiationException(
                    "NeoForge frozen registry completion arrived before sync start",
                )
            val missing = expected - frozenPackets.keys
            if (missing.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "NeoForge frozen registry sync omitted $missing",
                )
            }
            val snapshot = remoteRegistrySnapshot(frozenPackets.values)
            definition.staticRegistries.requireCompatible(snapshot)
            frozenSnapshot = snapshot
            expectedFrozenRegistries = null
            connection.outgoing.send(
                NeoForgeFrozenRegistrySyncCompletedPacket,
            )
            true
        }

        is NeoForgeCommonVersionPacket -> {
            advanceLateTask(COMMON_VERSION_TASK)
            val selected = highestCommonVersion(
                packet.versions,
                definition.supportedCommonVersions,
            )
            commonVersion = selected
            connection.outgoing.send(
                NeoForgeCommonVersionPacket(listOf(selected)),
            )
            true
        }

        is NeoForgeCommonRegisterPacket -> {
            advanceLateTask(COMMON_REGISTER_TASK)
            requireCommonRegister(packet, commonVersion)
            remotePlayChannels.clear()
            remotePlayChannels += packet.channels
            connection.outgoing.send(
                NeoForgeCommonRegisterPacket(
                    checkNotNull(commonVersion),
                    NeoForgeConnectionProtocol.PLAY.id,
                    definition.network.optionalChannels(
                        NeoForgeConnectionProtocol.PLAY,
                        NeoForgePacketFlow.CLIENTBOUND,
                    ),
                ),
            )
            true
        }

        is NeoForgeConfigFilePacket -> {
            advanceLateTask(CONFIG_TASK, repeated = true)
            configFiles += packet
            true
        }

        is NeoForgeKnownRegistryDataMapsPacket -> {
            advanceLateTask(DATA_MAP_TASK)
            validateDataMaps(packet.dataMaps, definition.knownDataMaps)
            remoteKnownDataMaps = packet.dataMaps.mapValues { (_, maps) ->
                maps.map(NeoForgeKnownDataMap::id)
            }
            connection.outgoing.send(
                NeoForgeKnownRegistryDataMapsReplyPacket(
                    definition.knownDataMaps.mapValues { (_, maps) ->
                        maps.map(NeoForgeKnownDataMap::id)
                    },
                ),
            )
            true
        }

        is NeoForgeExtensibleEnumDataPacket -> {
            advanceLateTask(ENUM_TASK)
            validateExtensibleEnums(packet.entries, definition.extensibleEnums)
            connection.outgoing.send(
                NeoForgeExtensibleEnumAcknowledgePacket,
            )
            true
        }

        is NeoForgeFeatureFlagDataPacket -> {
            advanceLateTask(FEATURE_FLAG_TASK)
            if (packet.flags != definition.featureFlags) {
                throw NeoForgeNegotiationException(
                    "NeoForge feature flags differ: server=${packet.flags}, client=${definition.featureFlags}",
                )
            }
            connection.outgoing.send(NeoForgeFeatureFlagAcknowledgePacket)
            true
        }

        is NeoForgeSplitPacket -> {
            val routed = splitAssembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                packet,
            ) ?: return true
            val decoded = connection.decodeCustomPayload(routed)
            if (!handleConfigurationPacket(connection, decoded)) {
                throw NeoForgeNegotiationException(
                    "NeoForge split stream produced unexpected ${decoded::class.simpleName}",
                )
            }
            true
        }

        else -> false
    }

    override suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        if (expectedFrozenRegistries != null) {
            throw NeoForgeNegotiationException(
                "Configuration finished during NeoForge frozen registry sync",
            )
        }
        ensureNetworkSetupForOtherPeer()
        val snapshot = frozenSnapshot ?: return context
        return context.withStaticRegistryResolution(
            definition.staticRegistries.resolve(snapshot),
        )
    }

    override suspend fun preparePlay(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ) {
        val setup = ensureNetworkSetupForOtherPeer()
        activatePlayRoutes(
            connection,
            setup,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
            remotePlayChannels,
            definition.network.optionalChannels(
                NeoForgeConnectionProtocol.PLAY,
                NeoForgePacketFlow.CLIENTBOUND,
            ),
            sentNetworkQuery,
        )
        connection.outgoing.send(
            NeoForgeUnregisterChannelsPacket(
                INITIAL_CHANNELS +
                        setup.channels(
                            NeoForgeConnectionProtocol.CONFIGURATION,
                        ).keys,
            ),
        )
        connection.outgoing.send(
            NeoForgeRegisterChannelsPacket(
                playListeningChannels(
                    definition.network,
                    NeoForgePacketFlow.CLIENTBOUND,
                    sentNetworkQuery,
                ),
            ),
        )
    }

    override suspend fun complete(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ): NegotiationProfileResult = result()

    private fun ensureNetworkSetupForOtherPeer(): NeoForgeNetworkSetup {
        networkSetup?.let { return it }
        val mandatory = definition.network.components.values.flatten()
            .filterNot(NeoForgeNetworkComponent::optional)
        if (mandatory.isNotEmpty()) {
            throw NeoForgeNegotiationException(
                "Server did not negotiate mandatory NeoForge channels ${mandatory.map(NeoForgeNetworkComponent::id)}",
            )
        }
        return NeoForgeNetworkSetup.Empty.also { networkSetup = it }
    }

    private fun advanceLateTask(rank: Int, repeated: Boolean = false) {
        if (rank < lateTaskRank || (!repeated && rank == lateTaskRank)) {
            throw NeoForgeNegotiationException(
                "NeoForge configuration task rank $rank arrived after $lateTaskRank",
            )
        }
        lateTaskRank = rank
    }

    private fun result(): NeoForgeNegotiationResult = NeoForgeNegotiationResult(
        neoForgePeer = sentNetworkQuery,
        networkSetup = networkSetup ?: NeoForgeNetworkSetup.Empty,
        commonVersion = commonVersion,
        remoteConfigurationChannels = remoteConfigurationChannels.toSet(),
        remotePlayChannels = remotePlayChannels.toSet(),
        registrySynchronized = frozenSnapshot != null,
        configFiles = configFiles.toList(),
        remoteKnownDataMaps = remoteKnownDataMaps,
    )
}

class NeoForgeServerProfile(
    val definition: NeoForgeServerProfileDefinition,
) : ServerNegotiationProfile {
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private val splitAssembler = NeoForgeSplitAssembler(
        definition.maximumSplitPacketSize,
    )
    private var setup: NeoForgeNetworkSetup? = null
    private var neoForgePeer = false
    private var receivedProbePong = false
    private var commonVersion: Int? = null
    private var registrySynchronized = false
    private var remoteKnownDataMaps: Map<Identifier, List<Identifier>> = emptyMap()
    private var expectedResponse: ExpectedResponse? = null
    private var stage = ServerStage.BEGIN
    private var begun = false

    override suspend fun begin(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        check(!begun) { "A NeoForgeServerProfile can negotiate only one connection" }
        begun = true
        requireNeoForgeCodecs(connection, definition.network)
        activateInitialConfigurationRoutes(
            connection,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
    }

    override suspend fun negotiateConfigurationStart(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        requireStage(ServerStage.BEGIN)
        stage = ServerStage.INITIAL
        connection.outgoing.send(
            NeoForgeUnregisterChannelsPacket(
                setOf(NeoForgeChannels.Register, NeoForgeChannels.Unregister) +
                        definition.network.optionalChannels(
                            NeoForgeConnectionProtocol.PLAY,
                            NeoForgePacketFlow.SERVERBOUND,
                        ),
            ),
        )
        connection.outgoing.send(
            NeoForgeRegisterChannelsPacket(INITIAL_CHANNELS),
        )
        connection.outgoing.send(
            NeoForgeModdedNetworkQueryPacket(emptyMap()),
        )
        connection.outgoing.send(ConfigurationPingPacket(NEGOTIATION_PING_ID))
        while (!receivedProbePong) {
            val packet = connection.incoming.receive()
            if (!handleConfigurationPacket(connection, packet)) {
                throw NeoForgeNegotiationException(
                    "Unexpected packet during initial NeoForge negotiation: ${packet::class.simpleName}",
                )
            }
        }
        if (setup == null) {
            initializeOtherPeer(connection)
        }
        stage = ServerStage.NETWORK_READY
    }

    override suspend fun negotiateEarlyConfiguration(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        requireStage(ServerStage.NETWORK_READY)
        stage = ServerStage.EARLY
        val frozen = definition.frozenRegistries
        if (frozen != null && FROZEN_CHANNELS.all(::configurationChannelNegotiated)) {
            expectedResponse = ExpectedResponse.FROZEN_REGISTRY
            connection.outgoing.send(frozen.startPacket)
            frozen.registries.forEach { packet ->
                sendPossiblySplit(connection, packet)
            }
            connection.outgoing.send(
                NeoForgeFrozenRegistrySyncCompletedPacket,
            )
            awaitExpected<NeoForgeFrozenRegistrySyncCompletedPacket>(connection)
            registrySynchronized = true
        }
        stage = ServerStage.EARLY_COMPLETE
    }

    override suspend fun negotiateConfiguration(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        requireStage(ServerStage.EARLY_COMPLETE)
        stage = ServerStage.LATE
        negotiateCommonChannels(connection)
        if (configurationChannelNegotiated(NeoForgeChannels.ConfigFile)) {
            definition.configFiles.forEach { packet ->
                sendPossiblySplit(connection, packet)
            }
        }
        negotiateDataMaps(connection)
        negotiateEnums(connection)
        negotiateFeatureFlags(connection)
        stage = ServerStage.LATE_COMPLETE
    }

    override suspend fun handleConfigurationPacket(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
        packet: ServerboundPacket,
    ): Boolean = when (packet) {
        is NeoForgeRegisterChannelsPacket -> {
            remoteConfigurationChannels += packet.channels
            true
        }

        is NeoForgeUnregisterChannelsPacket -> {
            remoteConfigurationChannels -= packet.channels
            true
        }

        is NeoForgeModdedNetworkQueryPacket -> {
            if (stage != ServerStage.INITIAL || setup != null) {
                throw NeoForgeNegotiationException(
                    "NeoForge client network query arrived out of order",
                )
            }
            initializeNeoForgePeer(connection, packet)
            true
        }

        is ConfigurationPongPacket -> {
            if (stage != ServerStage.INITIAL || packet.id != NEGOTIATION_PING_ID) {
                return false
            }
            receivedProbePong = true
            true
        }

        is NeoForgeCommonVersionPacket -> {
            requireExpected(ExpectedResponse.COMMON_VERSION)
            commonVersion = highestCommonVersion(
                packet.versions,
                definition.supportedCommonVersions,
            )
            true
        }

        is NeoForgeCommonRegisterPacket -> {
            requireExpected(ExpectedResponse.COMMON_REGISTER)
            requireCommonRegister(packet, commonVersion)
            remotePlayChannels.clear()
            remotePlayChannels += packet.channels
            true
        }

        NeoForgeFrozenRegistrySyncCompletedPacket -> {
            requireExpected(ExpectedResponse.FROZEN_REGISTRY)
            true
        }

        is NeoForgeKnownRegistryDataMapsReplyPacket -> {
            requireExpected(ExpectedResponse.DATA_MAPS)
            remoteKnownDataMaps = packet.dataMaps
            val missing = mandatoryDataMaps(definition.knownDataMaps) -
                    packet.dataMaps.flatMapTo(linkedSetOf()) { (registry, maps) ->
                        maps.map { id -> registry to id }
                    }
            if (missing.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "Client omitted mandatory NeoForge data maps $missing",
                )
            }
            true
        }

        NeoForgeExtensibleEnumAcknowledgePacket -> {
            requireExpected(ExpectedResponse.ENUMS)
            true
        }

        NeoForgeFeatureFlagAcknowledgePacket -> {
            requireExpected(ExpectedResponse.FEATURE_FLAGS)
            true
        }

        is NeoForgeSplitPacket -> {
            val routed = splitAssembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
                packet,
            ) ?: return true
            val decoded = connection.decodeCustomPayload(routed)
            if (!handleConfigurationPacket(connection, decoded)) {
                throw NeoForgeNegotiationException(
                    "NeoForge split stream produced unexpected ${decoded::class.simpleName}",
                )
            }
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
        requireStage(ServerStage.LATE_COMPLETE)
        stage = ServerStage.PLAY
        val actualSetup = checkNotNull(setup)
        activatePlayRoutes(
            connection,
            actualSetup,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
            remotePlayChannels,
            definition.network.optionalChannels(
                NeoForgeConnectionProtocol.PLAY,
                NeoForgePacketFlow.SERVERBOUND,
            ),
            neoForgePeer,
        )
        connection.outgoing.send(
            NeoForgeUnregisterChannelsPacket(
                INITIAL_CHANNELS +
                        actualSetup.channels(
                            NeoForgeConnectionProtocol.CONFIGURATION,
                        ).keys,
            ),
        )
        connection.outgoing.send(
            NeoForgeRegisterChannelsPacket(
                playListeningChannels(
                    definition.network,
                    NeoForgePacketFlow.SERVERBOUND,
                    neoForgePeer,
                ),
            ),
        )
    }

    override suspend fun complete(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ): NegotiationProfileResult = NeoForgeNegotiationResult(
        neoForgePeer = neoForgePeer,
        networkSetup = setup ?: NeoForgeNetworkSetup.Empty,
        commonVersion = commonVersion,
        remoteConfigurationChannels = remoteConfigurationChannels.toSet(),
        remotePlayChannels = remotePlayChannels.toSet(),
        registrySynchronized = registrySynchronized,
        configFiles = emptyList(),
        remoteKnownDataMaps = remoteKnownDataMaps,
    )

    private suspend fun initializeNeoForgePeer(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
        packet: NeoForgeModdedNetworkQueryPacket,
    ) {
        val result = negotiateNeoForgeNetwork(definition.network, packet.queries)
        if (!result.successful) {
            throw NeoForgeNetworkNegotiationException(
                NeoForgeModdedNetworkSetupFailedPacket(result.failureReasons),
            )
        }
        neoForgePeer = true
        setup = result.setup
        activateConfigurationSetupRoutes(
            connection,
            result.setup,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
        connection.outgoing.send(NeoForgeModdedNetworkPacket(result.setup))
        connection.outgoing.send(
            NeoForgeRegisterChannelsPacket(
                INITIAL_CHANNELS +
                        result.setup.channels(
                            NeoForgeConnectionProtocol.CONFIGURATION,
                        ).keys,
            ),
        )
    }

    private suspend fun initializeOtherPeer(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        val result = negotiateNeoForgeNetwork(definition.network, emptyMap())
        if (!result.successful) {
            throw NeoForgeNetworkNegotiationException(
                NeoForgeModdedNetworkSetupFailedPacket(result.failureReasons),
            )
        }
        setup = result.setup
        val localListening = definition.network.optionalChannels(
            NeoForgeConnectionProtocol.CONFIGURATION,
            NeoForgePacketFlow.SERVERBOUND,
        )
        activateOtherConfigurationRoutes(
            connection,
            remoteConfigurationChannels,
            localListening,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
        connection.outgoing.send(
            NeoForgeRegisterChannelsPacket(INITIAL_CHANNELS + localListening),
        )
    }

    private suspend fun negotiateCommonChannels(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        if (
            NeoForgeChannels.CommonVersion !in remoteConfigurationChannels ||
            NeoForgeChannels.CommonRegister !in remoteConfigurationChannels
        ) {
            return
        }
        expectedResponse = ExpectedResponse.COMMON_VERSION
        connection.outgoing.send(
            NeoForgeCommonVersionPacket(
                definition.supportedCommonVersions.sorted(),
            ),
        )
        awaitExpected<NeoForgeCommonVersionPacket>(connection)
        expectedResponse = ExpectedResponse.COMMON_REGISTER
        connection.outgoing.send(
            NeoForgeCommonRegisterPacket(
                checkNotNull(commonVersion),
                NeoForgeConnectionProtocol.PLAY.id,
                definition.network.optionalChannels(
                    NeoForgeConnectionProtocol.PLAY,
                    NeoForgePacketFlow.SERVERBOUND,
                ),
            ),
        )
        awaitExpected<NeoForgeCommonRegisterPacket>(connection)
    }

    private suspend fun negotiateDataMaps(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        if (!configurationChannelNegotiated(NeoForgeChannels.KnownRegistryDataMaps)) {
            if (mandatoryDataMaps(definition.knownDataMaps).isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "Client cannot negotiate mandatory NeoForge registry data maps",
                )
            }
            return
        }
        expectedResponse = ExpectedResponse.DATA_MAPS
        connection.outgoing.send(
            NeoForgeKnownRegistryDataMapsPacket(definition.knownDataMaps),
        )
        awaitExpected<NeoForgeKnownRegistryDataMapsReplyPacket>(connection)
    }

    private suspend fun negotiateEnums(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        if (!configurationChannelNegotiated(NeoForgeChannels.ExtensibleEnumData)) {
            val required = definition.extensibleEnums.filter { entry ->
                entry.data != null &&
                        entry.networkCheck != NeoForgeNetworkCheck.SERVERBOUND
            }
            if (required.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "Client cannot validate clientbound NeoForge extensible enums ${required.map(NeoForgeEnumEntry::className)}",
                )
            }
            return
        }
        expectedResponse = ExpectedResponse.ENUMS
        connection.outgoing.send(
            NeoForgeExtensibleEnumDataPacket(definition.extensibleEnums),
        )
        awaitExpected<NeoForgeExtensibleEnumAcknowledgePacket>(connection)
    }

    private suspend fun negotiateFeatureFlags(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        if (!configurationChannelNegotiated(NeoForgeChannels.FeatureFlagData)) {
            if (definition.featureFlags.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "Client cannot validate custom NeoForge feature flags",
                )
            }
            return
        }
        expectedResponse = ExpectedResponse.FEATURE_FLAGS
        connection.outgoing.send(
            NeoForgeFeatureFlagDataPacket(definition.featureFlags),
        )
        awaitExpected<NeoForgeFeatureFlagAcknowledgePacket>(connection)
    }

    private suspend inline fun <reified T : ServerboundPacket> awaitExpected(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ): T {
        while (true) {
            val packet = connection.incoming.receive()
            if (packet is T) {
                handleConfigurationPacket(connection, packet)
                expectedResponse = null
                return packet
            }
            if (!handleConfigurationPacket(connection, packet)) {
                throw NeoForgeNegotiationException(
                    "Expected ${T::class.simpleName}, received ${packet::class.simpleName}",
                )
            }
        }
    }

    private suspend fun sendPossiblySplit(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
        packet: ClientboundPacket,
    ) {
        val routed = connection.encodeCustomPayload(packet)
        if (
            NeoForgeSplitPayloads.encodedPacketSize(routed) <=
            NeoForgeProtocol.SPLIT_PART_SIZE
        ) {
            connection.outgoing.send(packet)
            return
        }
        if (!configurationChannelNegotiated(NeoForgeChannels.Split)) {
            throw NeoForgeNegotiationException(
                "NeoForge payload ${routed.route.channel} requires splitting, but the split channel was not negotiated",
            )
        }
        NeoForgeSplitPayloads.split(
            routed,
            maximumPacketSize = definition.maximumSplitPacketSize,
        ).forEach { fragment -> connection.outgoing.send(fragment) }
    }

    private fun configurationChannelNegotiated(channel: Identifier): Boolean =
        setup?.channels(NeoForgeConnectionProtocol.CONFIGURATION)
            ?.containsKey(channel) == true

    private fun requireExpected(expected: ExpectedResponse) {
        if (expectedResponse != expected) {
            throw NeoForgeNegotiationException(
                "NeoForge response $expected arrived while waiting for $expectedResponse",
            )
        }
    }

    private fun requireStage(expected: ServerStage) {
        if (stage != expected) {
            throw NeoForgeNegotiationException(
                "NeoForge server profile is in $stage; expected $expected",
            )
        }
    }
}

class NeoForgeNetworkNegotiationException(
    val failurePacket: NeoForgeModdedNetworkSetupFailedPacket,
) : NeoForgeNegotiationException(
    "NeoForge network negotiation failed for ${failurePacket.failureReasons.keys}",
)

class NeoForgeRemoteSetupFailedException(
    val failurePacket: NeoForgeModdedNetworkSetupFailedPacket,
) : NeoForgeNegotiationException(
    "NeoForge server reported setup failures for ${failurePacket.failureReasons.keys}",
)

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activateInitialConfigurationRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val configurationRoutes = customRoutes(connection, ConnectionState.CONFIGURATION)
    val accepted = configurationRoutes.filter { route ->
        route.channel in INITIAL_CHANNELS &&
                (route.direction == incomingDirection ||
                        route.direction == outgoingDirection)
    }
    val loginRoutes = connection.declaredExtensionRoutes.filter { route ->
        route is PacketRouteKey.LoginQuery
    }
    connection.activateExtensionRoutes((accepted + loginRoutes).toSet())
}

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activateConfigurationSetupRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    setup: NeoForgeNetworkSetup,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val setupChannels = setup.channels(
        NeoForgeConnectionProtocol.CONFIGURATION,
    ).keys
    val candidates = customRoutes(connection, ConnectionState.CONFIGURATION)
    val accepted = candidates.filter { route ->
        (route.channel in INITIAL_CHANNELS || route.channel in setupChannels) &&
                (route.direction == incomingDirection ||
                        route.direction == outgoingDirection)
    }
    connection.activateExtensionRoutes(
        connection.activeExtensionRoutes - candidates.toSet() + accepted,
    )
}

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activateOtherConfigurationRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    remoteChannels: Set<Identifier>,
    localListening: Set<Identifier>,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val candidates = customRoutes(connection, ConnectionState.CONFIGURATION)
    val accepted = candidates.filter { route ->
        route.channel in INITIAL_CHANNELS ||
                (
                        route.direction == outgoingDirection &&
                                route.channel in remoteChannels
                        ) ||
                (
                        route.direction == incomingDirection &&
                                route.channel in localListening
                        )
    }
    connection.activateExtensionRoutes(
        connection.activeExtensionRoutes - candidates.toSet() + accepted,
    )
}

private suspend fun <Incoming : Packet, Outgoing : Packet> activatePlayRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    setup: NeoForgeNetworkSetup,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
    remoteCommonChannels: Set<Identifier>,
    localCommonChannels: Set<Identifier>,
    neoForgePeer: Boolean,
) {
    val setupChannels = setup.channels(NeoForgeConnectionProtocol.PLAY).keys
    val infrastructure = buildSet {
        add(NeoForgeChannels.Register)
        add(NeoForgeChannels.Unregister)
        if (neoForgePeer) add(NeoForgeChannels.NetworkQuery)
    }
    val candidates = customRoutes(connection, ConnectionState.PLAY)
    val accepted = candidates.filter { route ->
        route.channel in infrastructure ||
                route.channel in setupChannels ||
                (
                        route.direction == outgoingDirection &&
                                route.channel in remoteCommonChannels
                        ) ||
                (
                        route.direction == incomingDirection &&
                                route.channel in localCommonChannels
                        )
    }
    connection.activateExtensionRoutes(
        connection.activeExtensionRoutes - candidates.toSet() + accepted,
    )
}

private fun <Incoming : Packet, Outgoing : Packet> customRoutes(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    state: ConnectionState,
): Set<PacketRouteKey.CustomPayload> = connection.declaredExtensionRoutes
    .filterIsInstance<PacketRouteKey.CustomPayload>()
    .filter { route -> route.state == state }
    .toSet()

private fun <Incoming : Packet, Outgoing : Packet> requireNeoForgeCodecs(
    connection: MinecraftPacketConnection<Incoming, Outgoing>,
    network: NeoForgeNetworkConfiguration,
) {
    val required = buildSet {
        addAll(
            NeoForgeProtocol.packetCodecs.map { registration ->
                registration.route
            }.filter { route -> route.state == ConnectionState.CONFIGURATION },
        )
        network.components.forEach { (protocol, components) ->
            val state = protocol.toConnectionState()
            components.forEach { component ->
                val directions = component.flow
                    ?.let { flow -> listOf(flow.toPacketDirection()) }
                    ?: listOf(
                        PacketDirection.CLIENTBOUND,
                        PacketDirection.SERVERBOUND,
                    )
                directions.forEach { direction ->
                    add(
                        PacketRouteKey.CustomPayload(
                            state,
                            direction,
                            component.id,
                        ),
                    )
                }
            }
        }
    }
    val missing = required - connection.declaredExtensionRoutes
    require(missing.isEmpty()) {
        "NeoForge profile is missing extension packet codecs $missing"
    }
}

private fun playListeningChannels(
    network: NeoForgeNetworkConfiguration,
    incomingFlow: NeoForgePacketFlow,
    neoForgePeer: Boolean,
): Set<Identifier> = buildSet {
    add(NeoForgeChannels.Register)
    add(NeoForgeChannels.Unregister)
    if (neoForgePeer) {
        add(NeoForgeChannels.NetworkQuery)
    } else {
        addAll(
            network.optionalChannels(
                NeoForgeConnectionProtocol.PLAY,
                incomingFlow,
            ),
        )
    }
}

private fun validateCommonVersions(versions: Set<Int>): Set<Int> =
    versions.toSet().also { snapshot ->
        require(snapshot.isNotEmpty()) {
            "NeoForge common version set must not be empty"
        }
        require(snapshot.all { it > 0 }) {
            "NeoForge common versions must be positive"
        }
        require(snapshot.size <= NeoForgeProtocol.MAX_COMMON_VERSIONS) {
            "NeoForge common version set is too large"
        }
    }

private fun highestCommonVersion(
    remote: Collection<Int>,
    local: Set<Int>,
): Int = remote.filter(local::contains).maxOrNull()
    ?.takeIf { it > 0 }
    ?: throw NeoForgeNegotiationException(
        "No mutually supported NeoForge common packet version",
    )

private fun requireCommonRegister(
    packet: NeoForgeCommonRegisterPacket,
    commonVersion: Int?,
) {
    val selected = commonVersion ?: throw NeoForgeNegotiationException(
        "NeoForge common channels arrived before version negotiation",
    )
    if (packet.version != selected) {
        throw NeoForgeNegotiationException(
            "NeoForge common channel version ${packet.version} does not match $selected",
        )
    }
    if (packet.protocol != NeoForgeConnectionProtocol.PLAY.id) {
        throw NeoForgeNegotiationException(
            "NeoForge common registration used unsupported protocol ${packet.protocol}",
        )
    }
}

private fun validateDataMaps(
    remote: Map<Identifier, List<NeoForgeKnownDataMap>>,
    local: Map<Identifier, List<NeoForgeKnownDataMap>>,
) {
    val remoteMandatory = mandatoryDataMaps(remote)
    val localMandatory = mandatoryDataMaps(local)
    if (remoteMandatory != localMandatory) {
        throw NeoForgeNegotiationException(
            "NeoForge mandatory data maps differ: server=$remoteMandatory, client=$localMandatory",
        )
    }
}

private fun mandatoryDataMaps(
    maps: Map<Identifier, List<NeoForgeKnownDataMap>>,
): Set<Pair<Identifier, Identifier>> = maps.flatMapTo(linkedSetOf()) { (registry, entries) ->
    entries.filter(NeoForgeKnownDataMap::mandatory)
        .map { entry -> registry to entry.id }
}

private fun validateExtensibleEnums(
    remote: List<NeoForgeEnumEntry>,
    local: List<NeoForgeEnumEntry>,
) {
    val remoteByName = remote.associateBy(NeoForgeEnumEntry::className)
    val localByName = local.associateBy(NeoForgeEnumEntry::className)
    val names = LinkedHashSet<String>().apply {
        addAll(remoteByName.keys)
        addAll(localByName.keys)
    }
    val mismatched = names.filter { name ->
        val remoteEntry = remoteByName[name]
        val localEntry = localByName[name]
        val remoteData = remoteEntry?.data
        val localData = localEntry?.data
        when {
            remoteData == null && localData == null -> false
            remoteEntry == null || localEntry == null -> true
            remoteEntry.networkCheck != localEntry.networkCheck -> true
            remoteData == null || localData == null -> true
            else -> remoteData != localData
        }
    }
    if (mismatched.isNotEmpty()) {
        throw NeoForgeNegotiationException(
            "NeoForge extensible enums differ: $mismatched",
        )
    }
}

private val INITIAL_CHANNELS = setOf(
    NeoForgeChannels.Register,
    NeoForgeChannels.Unregister,
    NeoForgeChannels.NetworkQuery,
    NeoForgeChannels.Network,
    NeoForgeChannels.NetworkSetupFailed,
    NeoForgeChannels.CommonVersion,
    NeoForgeChannels.CommonRegister,
)

private val FROZEN_CHANNELS = setOf(
    NeoForgeChannels.FrozenRegistrySyncStart,
    NeoForgeChannels.FrozenRegistry,
    NeoForgeChannels.FrozenRegistrySyncCompleted,
)

private const val NEGOTIATION_PING_ID = 0
private const val COMMON_VERSION_TASK = 1
private const val COMMON_REGISTER_TASK = 2
private const val CONFIG_TASK = 3
private const val DATA_MAP_TASK = 4
private const val ENUM_TASK = 5
private const val FEATURE_FLAG_TASK = 6

private enum class ExpectedResponse {
    COMMON_VERSION,
    COMMON_REGISTER,
    FROZEN_REGISTRY,
    DATA_MAPS,
    ENUMS,
    FEATURE_FLAGS,
}

private enum class ServerStage {
    BEGIN,
    INITIAL,
    NETWORK_READY,
    EARLY,
    EARLY_COMPLETE,
    LATE,
    LATE_COMPLETE,
    PLAY,
}
