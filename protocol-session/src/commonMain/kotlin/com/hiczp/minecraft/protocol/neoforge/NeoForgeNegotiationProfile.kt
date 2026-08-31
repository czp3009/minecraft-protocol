package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.RemoteRegistrySnapshot
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema
import com.hiczp.minecraft.protocol.session.*

data class NeoForgeClientProfileDefinition(
    val staticRegistrySchema: StaticRegistrySchema,
    val neoForgeNetworkConfiguration: NeoForgeNetworkConfiguration = NeoForgeNetworkConfiguration(),
    val knownDataMaps: Map<Identifier, List<NeoForgeKnownDataMap>> = emptyMap(),
    val extensibleEnums: List<NeoForgeEnumEntry> = emptyList(),
    val featureFlags: Set<Identifier> = emptySet(),
    val supportedCommonVersions: Set<Int> =
        setOf(NeoForgeProtocol.COMMON_PACKET_VERSION),
) {
    init {
        validateCommonVersions(supportedCommonVersions)
        require(
            this.extensibleEnums.distinctBy(NeoForgeEnumEntry::className).size ==
                    this.extensibleEnums.size
        ) {
            "NeoForge client extensible enums contain duplicate class names"
        }
    }
}

data class NeoForgeServerProfileDefinition(
    val neoForgeNetworkConfiguration: NeoForgeNetworkConfiguration = NeoForgeNetworkConfiguration(),
    val neoForgeFrozenRegistrySync: NeoForgeFrozenRegistrySync? = null,
    /** Caller-built context retained by reference across connections. */
    val protocolRegistryContext: ProtocolRegistryContext? = null,
    val configFiles: List<NeoForgeConfigFilePacket> = emptyList(),
    val knownDataMaps: Map<Identifier, List<NeoForgeKnownDataMap>> = emptyMap(),
    val extensibleEnums: List<NeoForgeEnumEntry> = emptyList(),
    val featureFlags: Set<Identifier> = emptySet(),
    val supportedCommonVersions: Set<Int> =
        setOf(NeoForgeProtocol.COMMON_PACKET_VERSION),
) {
    init {
        validateCommonVersions(supportedCommonVersions)
        require(
            this.extensibleEnums.distinctBy(NeoForgeEnumEntry::className).size ==
                    this.extensibleEnums.size
        ) {
            "NeoForge server extensible enums contain duplicate class names"
        }
    }
}

data class NeoForgeNegotiationResult(
    val neoForgePeer: Boolean,
    val neoForgeNetworkSetup: NeoForgeNetworkSetup,
    val commonVersion: Int?,
    val remoteConfigurationChannels: Set<Identifier>,
    val remotePlayChannels: Set<Identifier>,
    val registriesSynchronized: Boolean,
    val configFiles: List<NeoForgeConfigFilePacket>,
    val remoteKnownDataMaps: Map<Identifier, List<Identifier>>,
) : NegotiationProfileResult

class NeoForgeClientProfile(
    val neoForgeClientProfileDefinition: NeoForgeClientProfileDefinition,
) : ClientNegotiationProfile {
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private val configFiles = mutableListOf<NeoForgeConfigFilePacket>()
    private val frozenRegistryPackets = linkedMapOf<Identifier, NeoForgeFrozenRegistryPacket>()
    private val neoForgeSplitAssembler = NeoForgeSplitAssembler()
    private var expectedFrozenRegistryIds: Set<Identifier>? = null
    private var frozenRemoteRegistrySnapshot: RemoteRegistrySnapshot? = null
    private var neoForgeNetworkSetup: NeoForgeNetworkSetup? = null
    private var commonVersion: Int? = null
    private var remoteKnownDataMaps: Map<Identifier, List<Identifier>> = emptyMap()
    private var sentInitialRegistration = false
    private var sentNetworkQuery = false
    private var lateTaskRank = 0
    private var begun = false

    override suspend fun begin(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        check(!begun) { "A NeoForgeClientProfile can negotiate only one connection" }
        begun = true
        requireNeoForgeCodecs(
            minecraftClientPacketConnection,
            neoForgeClientProfileDefinition.neoForgeNetworkConfiguration
        )
        activateInitialConfigurationRoutes(
            minecraftClientPacketConnection,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
        )
    }

    override suspend fun handleConfigurationPacket(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        clientboundPacket: ClientboundPacket,
    ): Boolean = when (clientboundPacket) {
        is NeoForgeRegisterChannelsPacket -> {
            remoteConfigurationChannels += clientboundPacket.channels
            if (!sentInitialRegistration) {
                sentInitialRegistration = true
                minecraftClientPacketConnection.outgoing.send(
                    NeoForgeRegisterChannelsPacket(INITIAL_CHANNELS),
                )
            }
            true
        }

        is NeoForgeUnregisterChannelsPacket -> {
            remoteConfigurationChannels -= clientboundPacket.channels
            true
        }

        is NeoForgeModdedNetworkQueryPacket -> {
            if (sentNetworkQuery) {
                throw NeoForgeNegotiationException(
                    "Server sent more than one NeoForge network query",
                )
            }
            sentNetworkQuery = true
            minecraftClientPacketConnection.outgoing.send(neoForgeClientProfileDefinition.neoForgeNetworkConfiguration.neoForgeModdedNetworkQueryPacket)
            true
        }

        is NeoForgeModdedNetworkPacket -> {
            if (!sentNetworkQuery || neoForgeNetworkSetup != null) {
                throw NeoForgeNegotiationException(
                    "NeoForge network setup arrived out of order",
                )
            }
            neoForgeClientProfileDefinition.neoForgeNetworkConfiguration.validateSetup(clientboundPacket.neoForgeNetworkSetup)
            neoForgeNetworkSetup = clientboundPacket.neoForgeNetworkSetup
            activateConfigurationSetupRoutes(
                minecraftClientPacketConnection,
                clientboundPacket.neoForgeNetworkSetup,
                PacketDirection.CLIENTBOUND,
                PacketDirection.SERVERBOUND,
            )
            minecraftClientPacketConnection.outgoing.send(
                NeoForgeRegisterChannelsPacket(
                    INITIAL_CHANNELS +
                            clientboundPacket.neoForgeNetworkSetup.channels(
                                NeoForgeConnectionProtocol.CONFIGURATION,
                            ).keys,
                ),
            )
            true
        }

        is NeoForgeModdedNetworkSetupFailedPacket ->
            throw NeoForgeRemoteSetupFailedException(clientboundPacket)

        is NeoForgeFrozenRegistrySyncStartPacket -> {
            if (expectedFrozenRegistryIds != null || lateTaskRank != 0) {
                throw NeoForgeNegotiationException(
                    "NeoForge frozen registry sync started out of order",
                )
            }
            if (clientboundPacket.registryIds.distinct().size != clientboundPacket.registryIds.size) {
                throw NeoForgeNegotiationException(
                    "NeoForge frozen registry start contains duplicates",
                )
            }
            expectedFrozenRegistryIds = clientboundPacket.registryIds.toSet()
            frozenRegistryPackets.clear()
            true
        }

        is NeoForgeFrozenRegistryPacket -> {
            val expected = expectedFrozenRegistryIds
                ?: throw NeoForgeNegotiationException(
                    "NeoForge frozen registry arrived before sync start",
                )
            if (clientboundPacket.registryId !in expected) {
                throw NeoForgeNegotiationException(
                    "Unexpected NeoForge frozen registry ${clientboundPacket.registryId}",
                )
            }
            if (frozenRegistryPackets.put(clientboundPacket.registryId, clientboundPacket) != null) {
                throw NeoForgeNegotiationException(
                    "Duplicate NeoForge frozen registry ${clientboundPacket.registryId}",
                )
            }
            true
        }

        NeoForgeFrozenRegistrySyncCompletedPacket -> {
            val expected = expectedFrozenRegistryIds
                ?: throw NeoForgeNegotiationException(
                    "NeoForge frozen registry completion arrived before sync start",
                )
            val missing = expected - frozenRegistryPackets.keys
            if (missing.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "NeoForge frozen registry sync omitted $missing",
                )
            }
            val remoteRegistrySnapshot = neoForgeRemoteRegistrySnapshot(frozenRegistryPackets.values)
            neoForgeClientProfileDefinition.staticRegistrySchema.requireNeoForgeCompatible(remoteRegistrySnapshot)
            frozenRemoteRegistrySnapshot = remoteRegistrySnapshot
            expectedFrozenRegistryIds = null
            minecraftClientPacketConnection.outgoing.send(
                NeoForgeFrozenRegistrySyncCompletedPacket,
            )
            true
        }

        is NeoForgeCommonVersionPacket -> {
            advanceLateTask(COMMON_VERSION_TASK)
            val selected = highestCommonVersion(
                clientboundPacket.versions,
                neoForgeClientProfileDefinition.supportedCommonVersions,
            )
            commonVersion = selected
            minecraftClientPacketConnection.outgoing.send(
                NeoForgeCommonVersionPacket(listOf(selected)),
            )
            true
        }

        is NeoForgeCommonRegisterPacket -> {
            advanceLateTask(COMMON_REGISTER_TASK)
            requireCommonRegister(clientboundPacket, commonVersion)
            remotePlayChannels.clear()
            remotePlayChannels += clientboundPacket.channels
            minecraftClientPacketConnection.outgoing.send(
                NeoForgeCommonRegisterPacket(
                    checkNotNull(commonVersion),
                    NeoForgeConnectionProtocol.PLAY.id,
                    neoForgeClientProfileDefinition.neoForgeNetworkConfiguration.optionalChannels(
                        NeoForgeConnectionProtocol.PLAY,
                        NeoForgePacketFlow.CLIENTBOUND,
                    ),
                ),
            )
            true
        }

        is NeoForgeConfigFilePacket -> {
            advanceLateTask(CONFIG_TASK, repeated = true)
            configFiles += clientboundPacket
            true
        }

        is NeoForgeKnownRegistryDataMapsPacket -> {
            advanceLateTask(DATA_MAP_TASK)
            validateDataMaps(clientboundPacket.dataMaps, neoForgeClientProfileDefinition.knownDataMaps)
            remoteKnownDataMaps = clientboundPacket.dataMaps.mapValues { (_, maps) ->
                maps.map(NeoForgeKnownDataMap::id)
            }
            minecraftClientPacketConnection.outgoing.send(
                NeoForgeKnownRegistryDataMapsReplyPacket(
                    neoForgeClientProfileDefinition.knownDataMaps.mapValues { (_, maps) ->
                        maps.map(NeoForgeKnownDataMap::id)
                    },
                ),
            )
            true
        }

        is NeoForgeExtensibleEnumDataPacket -> {
            advanceLateTask(ENUM_TASK)
            validateExtensibleEnums(clientboundPacket.entries, neoForgeClientProfileDefinition.extensibleEnums)
            minecraftClientPacketConnection.outgoing.send(
                NeoForgeExtensibleEnumAcknowledgePacket,
            )
            true
        }

        is NeoForgeFeatureFlagDataPacket -> {
            advanceLateTask(FEATURE_FLAG_TASK)
            if (clientboundPacket.flags != neoForgeClientProfileDefinition.featureFlags) {
                throw NeoForgeNegotiationException(
                    "NeoForge feature flags differ: server=${clientboundPacket.flags}, client=${neoForgeClientProfileDefinition.featureFlags}",
                )
            }
            minecraftClientPacketConnection.outgoing.send(NeoForgeFeatureFlagAcknowledgePacket)
            true
        }

        is NeoForgeSplitPacket -> {
            val routedCustomPayload = neoForgeSplitAssembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.CLIENTBOUND,
                clientboundPacket,
            ) ?: return true
            val decoded = minecraftClientPacketConnection.decodeCustomPayload(routedCustomPayload)
            if (!handleConfigurationPacket(minecraftClientPacketConnection, decoded)) {
                throw NeoForgeNegotiationException(
                    "NeoForge split stream produced unexpected ${decoded::class.simpleName}",
                )
            }
            true
        }

        else -> false
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        if (expectedFrozenRegistryIds != null) {
            throw NeoForgeNegotiationException(
                "Configuration finished during NeoForge frozen registry sync",
            )
        }
        ensureNetworkSetupForOtherPeer()
        val remoteRegistrySnapshot = frozenRemoteRegistrySnapshot ?: return protocolRegistryContext
        return protocolRegistryContext.withStaticRegistryResolution(
            neoForgeClientProfileDefinition.staticRegistrySchema.resolve(remoteRegistrySnapshot),
        )
    }

    override suspend fun preparePlay(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        val neoForgeNetworkSetup = ensureNetworkSetupForOtherPeer()
        activatePlayRoutes(
            minecraftClientPacketConnection,
            neoForgeNetworkSetup,
            PacketDirection.CLIENTBOUND,
            PacketDirection.SERVERBOUND,
            remotePlayChannels,
            neoForgeClientProfileDefinition.neoForgeNetworkConfiguration.optionalChannels(
                NeoForgeConnectionProtocol.PLAY,
                NeoForgePacketFlow.CLIENTBOUND,
            ),
            sentNetworkQuery,
        )
        minecraftClientPacketConnection.outgoing.send(
            NeoForgeUnregisterChannelsPacket(
                INITIAL_CHANNELS +
                        neoForgeNetworkSetup.channels(
                            NeoForgeConnectionProtocol.CONFIGURATION,
                        ).keys,
            ),
        )
        minecraftClientPacketConnection.outgoing.send(
            NeoForgeRegisterChannelsPacket(
                playListeningChannels(
                    neoForgeClientProfileDefinition.neoForgeNetworkConfiguration,
                    NeoForgePacketFlow.CLIENTBOUND,
                    sentNetworkQuery,
                ),
            ),
        )
    }

    override suspend fun complete(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ): NegotiationProfileResult = result()

    private fun ensureNetworkSetupForOtherPeer(): NeoForgeNetworkSetup {
        neoForgeNetworkSetup?.let { return it }
        val mandatory = neoForgeClientProfileDefinition.neoForgeNetworkConfiguration.components.values.flatten()
            .filterNot(NeoForgeNetworkComponent::optional)
        if (mandatory.isNotEmpty()) {
            throw NeoForgeNegotiationException(
                "Server did not negotiate mandatory NeoForge channels ${mandatory.map(NeoForgeNetworkComponent::id)}",
            )
        }
        return NeoForgeNetworkSetup.Empty.also { neoForgeNetworkSetup = it }
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
        neoForgeNetworkSetup = neoForgeNetworkSetup ?: NeoForgeNetworkSetup.Empty,
        commonVersion = commonVersion,
        remoteConfigurationChannels = remoteConfigurationChannels.toSet(),
        remotePlayChannels = remotePlayChannels.toSet(),
        registriesSynchronized = frozenRemoteRegistrySnapshot != null,
        configFiles = configFiles.toList(),
        remoteKnownDataMaps = remoteKnownDataMaps,
    )
}

class NeoForgeServerProfile(
    val neoForgeServerProfileDefinition: NeoForgeServerProfileDefinition,
) : ServerNegotiationProfile {
    private val remoteConfigurationChannels = linkedSetOf<Identifier>()
    private val remotePlayChannels = linkedSetOf<Identifier>()
    private val neoForgeSplitAssembler = NeoForgeSplitAssembler()
    private var neoForgeNetworkSetup: NeoForgeNetworkSetup? = null
    private var neoForgePeer = false
    private var receivedProbePong = false
    private var commonVersion: Int? = null
    private var registriesSynchronized = false
    private var remoteKnownDataMaps: Map<Identifier, List<Identifier>> = emptyMap()
    private var expectedResponse: ExpectedResponse? = null
    private var serverStage = ServerStage.BEGIN
    private var begun = false

    override suspend fun begin(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        check(!begun) { "A NeoForgeServerProfile can negotiate only one connection" }
        begun = true
        requireNeoForgeCodecs(
            minecraftServerPacketConnection,
            neoForgeServerProfileDefinition.neoForgeNetworkConfiguration
        )
        activateInitialConfigurationRoutes(
            minecraftServerPacketConnection,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
    }

    override suspend fun negotiateConfigurationStart(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        requireStage(ServerStage.BEGIN)
        serverStage = ServerStage.INITIAL
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeUnregisterChannelsPacket(
                setOf(NeoForgeChannels.Register, NeoForgeChannels.Unregister) +
                        neoForgeServerProfileDefinition.neoForgeNetworkConfiguration.optionalChannels(
                            NeoForgeConnectionProtocol.PLAY,
                            NeoForgePacketFlow.SERVERBOUND,
                        ),
            ),
        )
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeRegisterChannelsPacket(INITIAL_CHANNELS),
        )
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeModdedNetworkQueryPacket(emptyMap()),
        )
        minecraftServerPacketConnection.outgoing.send(ConfigurationPingPacket(NEGOTIATION_PING_ID))
        while (!receivedProbePong) {
            minecraftServerPacketConnection.requestFlush()
            val serverboundPacket = minecraftServerPacketConnection.incoming.receive()
            if (!handleConfigurationPacket(minecraftServerPacketConnection, serverboundPacket)) {
                throw NeoForgeNegotiationException(
                    "Unexpected packet during initial NeoForge negotiation: ${serverboundPacket::class.simpleName}",
                )
            }
        }
        if (neoForgeNetworkSetup == null) {
            initializeOtherPeer(minecraftServerPacketConnection)
        }
        serverStage = ServerStage.NETWORK_READY
    }

    override suspend fun negotiateEarlyConfiguration(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        requireStage(ServerStage.NETWORK_READY)
        serverStage = ServerStage.EARLY
        val neoForgeFrozenRegistrySync = neoForgeServerProfileDefinition.neoForgeFrozenRegistrySync
        if (neoForgeFrozenRegistrySync != null && FROZEN_CHANNELS.all(::configurationChannelNegotiated)) {
            expectedResponse = ExpectedResponse.FROZEN_REGISTRY
            minecraftServerPacketConnection.outgoing.send(neoForgeFrozenRegistrySync.neoForgeFrozenRegistrySyncStartPacket)
            neoForgeFrozenRegistrySync.frozenRegistryPackets.forEach { neoForgeFrozenRegistryPacket ->
                sendPossiblySplit(minecraftServerPacketConnection, neoForgeFrozenRegistryPacket)
            }
            minecraftServerPacketConnection.outgoing.send(
                NeoForgeFrozenRegistrySyncCompletedPacket,
            )
            awaitExpected<NeoForgeFrozenRegistrySyncCompletedPacket>(minecraftServerPacketConnection)
            registriesSynchronized = true
        }
        serverStage = ServerStage.EARLY_COMPLETE
    }

    override suspend fun negotiateConfiguration(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        requireStage(ServerStage.EARLY_COMPLETE)
        serverStage = ServerStage.LATE
        negotiateCommonChannels(minecraftServerPacketConnection)
        if (configurationChannelNegotiated(NeoForgeChannels.ConfigFile)) {
            neoForgeServerProfileDefinition.configFiles.forEach { neoForgeConfigFilePacket ->
                sendPossiblySplit(minecraftServerPacketConnection, neoForgeConfigFilePacket)
            }
        }
        negotiateDataMaps(minecraftServerPacketConnection)
        negotiateEnums(minecraftServerPacketConnection)
        negotiateFeatureFlags(minecraftServerPacketConnection)
        serverStage = ServerStage.LATE_COMPLETE
    }

    override suspend fun handleConfigurationPacket(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
        serverboundPacket: ServerboundPacket,
    ): Boolean = when (serverboundPacket) {
        is NeoForgeRegisterChannelsPacket -> {
            remoteConfigurationChannels += serverboundPacket.channels
            true
        }

        is NeoForgeUnregisterChannelsPacket -> {
            remoteConfigurationChannels -= serverboundPacket.channels
            true
        }

        is NeoForgeModdedNetworkQueryPacket -> {
            if (serverStage != ServerStage.INITIAL || neoForgeNetworkSetup != null) {
                throw NeoForgeNegotiationException(
                    "NeoForge client network query arrived out of order",
                )
            }
            initializeNeoForgePeer(minecraftServerPacketConnection, serverboundPacket)
            true
        }

        is ConfigurationPongPacket -> {
            if (serverStage != ServerStage.INITIAL || serverboundPacket.id != NEGOTIATION_PING_ID) {
                return false
            }
            receivedProbePong = true
            true
        }

        is NeoForgeCommonVersionPacket -> {
            requireExpected(ExpectedResponse.COMMON_VERSION)
            commonVersion = highestCommonVersion(
                serverboundPacket.versions,
                neoForgeServerProfileDefinition.supportedCommonVersions,
            )
            true
        }

        is NeoForgeCommonRegisterPacket -> {
            requireExpected(ExpectedResponse.COMMON_REGISTER)
            requireCommonRegister(serverboundPacket, commonVersion)
            remotePlayChannels.clear()
            remotePlayChannels += serverboundPacket.channels
            true
        }

        NeoForgeFrozenRegistrySyncCompletedPacket -> {
            requireExpected(ExpectedResponse.FROZEN_REGISTRY)
            true
        }

        is NeoForgeKnownRegistryDataMapsReplyPacket -> {
            requireExpected(ExpectedResponse.DATA_MAPS)
            remoteKnownDataMaps = serverboundPacket.dataMaps
            val missing = mandatoryDataMaps(neoForgeServerProfileDefinition.knownDataMaps) -
                    serverboundPacket.dataMaps.flatMapTo(linkedSetOf()) { (registry, maps) ->
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
            val routedCustomPayload = neoForgeSplitAssembler.accept(
                ConnectionState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
                serverboundPacket,
            ) ?: return true
            val decoded = minecraftServerPacketConnection.decodeCustomPayload(routedCustomPayload)
            if (!handleConfigurationPacket(minecraftServerPacketConnection, decoded)) {
                throw NeoForgeNegotiationException(
                    "NeoForge split stream produced unexpected ${decoded::class.simpleName}",
                )
            }
            true
        }

        else -> false
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext {
        val sharedProtocolRegistryContext =
            neoForgeServerProfileDefinition.protocolRegistryContext ?: return protocolRegistryContext
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
        requireStage(ServerStage.LATE_COMPLETE)
        serverStage = ServerStage.PLAY
        val actualSetup = checkNotNull(neoForgeNetworkSetup)
        activatePlayRoutes(
            minecraftServerPacketConnection,
            actualSetup,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
            remotePlayChannels,
            neoForgeServerProfileDefinition.neoForgeNetworkConfiguration.optionalChannels(
                NeoForgeConnectionProtocol.PLAY,
                NeoForgePacketFlow.SERVERBOUND,
            ),
            neoForgePeer,
        )
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeUnregisterChannelsPacket(
                INITIAL_CHANNELS +
                        actualSetup.channels(
                            NeoForgeConnectionProtocol.CONFIGURATION,
                        ).keys,
            ),
        )
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeRegisterChannelsPacket(
                playListeningChannels(
                    neoForgeServerProfileDefinition.neoForgeNetworkConfiguration,
                    NeoForgePacketFlow.SERVERBOUND,
                    neoForgePeer,
                ),
            ),
        )
    }

    override suspend fun complete(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult = NeoForgeNegotiationResult(
        neoForgePeer = neoForgePeer,
        neoForgeNetworkSetup = neoForgeNetworkSetup ?: NeoForgeNetworkSetup.Empty,
        commonVersion = commonVersion,
        remoteConfigurationChannels = remoteConfigurationChannels.toSet(),
        remotePlayChannels = remotePlayChannels.toSet(),
        registriesSynchronized = registriesSynchronized,
        configFiles = emptyList(),
        remoteKnownDataMaps = remoteKnownDataMaps,
    )

    private suspend fun initializeNeoForgePeer(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
        neoForgeModdedNetworkQueryPacket: NeoForgeModdedNetworkQueryPacket,
    ) {
        val neoForgeNetworkNegotiation =
            negotiateNeoForgeNetwork(
                neoForgeServerProfileDefinition.neoForgeNetworkConfiguration,
                neoForgeModdedNetworkQueryPacket.queries
            )
        if (!neoForgeNetworkNegotiation.successful) {
            throw NeoForgeNetworkNegotiationException(
                NeoForgeModdedNetworkSetupFailedPacket(neoForgeNetworkNegotiation.failureReasons),
            )
        }
        neoForgePeer = true
        neoForgeNetworkSetup = neoForgeNetworkNegotiation.neoForgeNetworkSetup
        activateConfigurationSetupRoutes(
            minecraftServerPacketConnection,
            neoForgeNetworkNegotiation.neoForgeNetworkSetup,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
        minecraftServerPacketConnection.outgoing.send(NeoForgeModdedNetworkPacket(neoForgeNetworkNegotiation.neoForgeNetworkSetup))
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeRegisterChannelsPacket(
                INITIAL_CHANNELS +
                        neoForgeNetworkNegotiation.neoForgeNetworkSetup.channels(
                            NeoForgeConnectionProtocol.CONFIGURATION,
                        ).keys,
            ),
        )
    }

    private suspend fun initializeOtherPeer(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        val neoForgeNetworkNegotiation =
            negotiateNeoForgeNetwork(neoForgeServerProfileDefinition.neoForgeNetworkConfiguration, emptyMap())
        if (!neoForgeNetworkNegotiation.successful) {
            throw NeoForgeNetworkNegotiationException(
                NeoForgeModdedNetworkSetupFailedPacket(neoForgeNetworkNegotiation.failureReasons),
            )
        }
        neoForgeNetworkSetup = neoForgeNetworkNegotiation.neoForgeNetworkSetup
        val localListening = neoForgeServerProfileDefinition.neoForgeNetworkConfiguration.optionalChannels(
            NeoForgeConnectionProtocol.CONFIGURATION,
            NeoForgePacketFlow.SERVERBOUND,
        )
        activateOtherConfigurationRoutes(
            minecraftServerPacketConnection,
            remoteConfigurationChannels,
            localListening,
            PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND,
        )
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeRegisterChannelsPacket(INITIAL_CHANNELS + localListening),
        )
    }

    private suspend fun negotiateCommonChannels(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        if (
            NeoForgeChannels.CommonVersion !in remoteConfigurationChannels ||
            NeoForgeChannels.CommonRegister !in remoteConfigurationChannels
        ) {
            return
        }
        expectedResponse = ExpectedResponse.COMMON_VERSION
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeCommonVersionPacket(
                neoForgeServerProfileDefinition.supportedCommonVersions.sorted(),
            ),
        )
        awaitExpected<NeoForgeCommonVersionPacket>(minecraftServerPacketConnection)
        expectedResponse = ExpectedResponse.COMMON_REGISTER
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeCommonRegisterPacket(
                checkNotNull(commonVersion),
                NeoForgeConnectionProtocol.PLAY.id,
                neoForgeServerProfileDefinition.neoForgeNetworkConfiguration.optionalChannels(
                    NeoForgeConnectionProtocol.PLAY,
                    NeoForgePacketFlow.SERVERBOUND,
                ),
            ),
        )
        awaitExpected<NeoForgeCommonRegisterPacket>(minecraftServerPacketConnection)
    }

    private suspend fun negotiateDataMaps(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        if (!configurationChannelNegotiated(NeoForgeChannels.KnownRegistryDataMaps)) {
            if (mandatoryDataMaps(neoForgeServerProfileDefinition.knownDataMaps).isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "Client cannot negotiate mandatory NeoForge registry data maps",
                )
            }
            return
        }
        expectedResponse = ExpectedResponse.DATA_MAPS
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeKnownRegistryDataMapsPacket(neoForgeServerProfileDefinition.knownDataMaps),
        )
        awaitExpected<NeoForgeKnownRegistryDataMapsReplyPacket>(minecraftServerPacketConnection)
    }

    private suspend fun negotiateEnums(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        if (!configurationChannelNegotiated(NeoForgeChannels.ExtensibleEnumData)) {
            val required = neoForgeServerProfileDefinition.extensibleEnums.filter { neoForgeEnumEntry ->
                neoForgeEnumEntry.neoForgeEnumExtensionData != null &&
                        neoForgeEnumEntry.neoForgeNetworkCheck != NeoForgeNetworkCheck.SERVERBOUND
            }
            if (required.isNotEmpty()) {
                val classNames = required.map(NeoForgeEnumEntry::className)
                throw NeoForgeNegotiationException(
                    "Client cannot validate clientbound NeoForge extensible enums $classNames",
                )
            }
            return
        }
        expectedResponse = ExpectedResponse.ENUMS
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeExtensibleEnumDataPacket(neoForgeServerProfileDefinition.extensibleEnums),
        )
        awaitExpected<NeoForgeExtensibleEnumAcknowledgePacket>(minecraftServerPacketConnection)
    }

    private suspend fun negotiateFeatureFlags(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        if (!configurationChannelNegotiated(NeoForgeChannels.FeatureFlagData)) {
            if (neoForgeServerProfileDefinition.featureFlags.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "Client cannot validate custom NeoForge feature flags",
                )
            }
            return
        }
        expectedResponse = ExpectedResponse.FEATURE_FLAGS
        minecraftServerPacketConnection.outgoing.send(
            NeoForgeFeatureFlagDataPacket(neoForgeServerProfileDefinition.featureFlags),
        )
        awaitExpected<NeoForgeFeatureFlagAcknowledgePacket>(minecraftServerPacketConnection)
    }

    private suspend inline fun <reified T : ServerboundPacket> awaitExpected(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): T {
        while (true) {
            minecraftServerPacketConnection.requestFlush()
            val packet = minecraftServerPacketConnection.incoming.receive()
            if (packet is T) {
                handleConfigurationPacket(minecraftServerPacketConnection, packet)
                expectedResponse = null
                return packet
            }
            if (!handleConfigurationPacket(minecraftServerPacketConnection, packet)) {
                throw NeoForgeNegotiationException(
                    "Expected ${T::class.simpleName}, received ${packet::class.simpleName}",
                )
            }
        }
    }

    private suspend fun sendPossiblySplit(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
        clientboundPacket: ClientboundPacket,
    ) {
        val routedCustomPayload = minecraftServerPacketConnection.encodeCustomPayload(clientboundPacket)
        if (
            NeoForgeSplitPayloads.encodedPacketSize(routedCustomPayload) <=
            NeoForgeProtocol.SPLIT_PART_SIZE
        ) {
            minecraftServerPacketConnection.outgoing.send(clientboundPacket)
            return
        }
        if (!configurationChannelNegotiated(NeoForgeChannels.Split)) {
            throw NeoForgeNegotiationException(
                "NeoForge payload ${routedCustomPayload.route.channel} requires splitting, but the split channel was not negotiated",
            )
        }
        NeoForgeSplitPayloads.split(
            routedCustomPayload,
        ).forEach { fragment -> minecraftServerPacketConnection.outgoing.send(fragment) }
    }

    private fun configurationChannelNegotiated(channel: Identifier): Boolean =
        neoForgeNetworkSetup?.channels(NeoForgeConnectionProtocol.CONFIGURATION)
            ?.containsKey(channel) == true

    private fun requireExpected(expectedResponse: ExpectedResponse) {
        if (expectedResponse != expectedResponse) {
            throw NeoForgeNegotiationException(
                "NeoForge response $expectedResponse arrived while waiting for $expectedResponse",
            )
        }
    }

    private fun requireStage(expected: ServerStage) {
        if (serverStage != expected) {
            throw NeoForgeNegotiationException(
                "NeoForge server profile is in $serverStage; expected $expected",
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
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val configurationRoutes = customRoutes(minecraftPacketConnection, ConnectionState.CONFIGURATION)
    val accepted = configurationRoutes.filter { packetRouteKey ->
        packetRouteKey.channel in INITIAL_CHANNELS &&
                (packetRouteKey.packetDirection == incomingDirection ||
                        packetRouteKey.packetDirection == outgoingDirection)
    }
    val loginRoutes = minecraftPacketConnection.declaredExtensionRoutes.filter { packetRouteKey ->
        packetRouteKey is PacketRouteKey.LoginQuery
    }
    minecraftPacketConnection.activateExtensionRoutes(buildSet {
        addAll(accepted)
        addAll(loginRoutes)
    })
}

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activateConfigurationSetupRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    neoForgeNetworkSetup: NeoForgeNetworkSetup,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val setupChannels = neoForgeNetworkSetup.channels(
        NeoForgeConnectionProtocol.CONFIGURATION,
    ).keys
    val candidates = customRoutes(minecraftPacketConnection, ConnectionState.CONFIGURATION)
    val accepted = candidates.filter { customPayload ->
        (customPayload.channel in INITIAL_CHANNELS || customPayload.channel in setupChannels) &&
                (customPayload.packetDirection == incomingDirection ||
                        customPayload.packetDirection == outgoingDirection)
    }
    minecraftPacketConnection.activateExtensionRoutes(buildSet {
        addAll(minecraftPacketConnection.activeExtensionRoutes)
        removeAll(candidates)
        addAll(accepted)
    })
}

private suspend fun <Incoming : Packet, Outgoing : Packet>
        activateOtherConfigurationRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    remoteChannels: Set<Identifier>,
    localListening: Set<Identifier>,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
) {
    val candidates = customRoutes(minecraftPacketConnection, ConnectionState.CONFIGURATION)
    val accepted = candidates.filter { customPayload ->
        customPayload.channel in INITIAL_CHANNELS ||
                (
                        customPayload.packetDirection == outgoingDirection &&
                                customPayload.channel in remoteChannels
                        ) ||
                (
                        customPayload.packetDirection == incomingDirection &&
                                customPayload.channel in localListening
                        )
    }
    minecraftPacketConnection.activateExtensionRoutes(buildSet {
        addAll(minecraftPacketConnection.activeExtensionRoutes)
        removeAll(candidates)
        addAll(accepted)
    })
}

private suspend fun <Incoming : Packet, Outgoing : Packet> activatePlayRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    neoForgeNetworkSetup: NeoForgeNetworkSetup,
    incomingDirection: PacketDirection,
    outgoingDirection: PacketDirection,
    remoteCommonChannels: Set<Identifier>,
    localCommonChannels: Set<Identifier>,
    neoForgePeer: Boolean,
) {
    val setupChannels = neoForgeNetworkSetup.channels(NeoForgeConnectionProtocol.PLAY).keys
    val infrastructure = buildSet {
        add(NeoForgeChannels.Register)
        add(NeoForgeChannels.Unregister)
        if (neoForgePeer) add(NeoForgeChannels.NetworkQuery)
    }
    val candidates = customRoutes(minecraftPacketConnection, ConnectionState.PLAY)
    val accepted = candidates.filter { customPayload ->
        customPayload.channel in infrastructure ||
                customPayload.channel in setupChannels ||
                (
                        customPayload.packetDirection == outgoingDirection &&
                                customPayload.channel in remoteCommonChannels
                        ) ||
                (
                        customPayload.packetDirection == incomingDirection &&
                                customPayload.channel in localCommonChannels
                        )
    }
    minecraftPacketConnection.activateExtensionRoutes(buildSet {
        addAll(minecraftPacketConnection.activeExtensionRoutes)
        removeAll(candidates)
        addAll(accepted)
    })
}

private fun <Incoming : Packet, Outgoing : Packet> customRoutes(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    connectionState: ConnectionState,
): Set<PacketRouteKey.CustomPayload> = minecraftPacketConnection.declaredExtensionRoutes
    .filterIsInstance<PacketRouteKey.CustomPayload>()
    .filter { route -> route.connectionState == connectionState }
    .toSet()

private fun <Incoming : Packet, Outgoing : Packet> requireNeoForgeCodecs(
    minecraftPacketConnection: MinecraftPacketConnection<Incoming, Outgoing>,
    neoForgeNetworkConfiguration: NeoForgeNetworkConfiguration,
) {
    val required = buildSet {
        addAll(
            NeoForgeProtocol.packetCodecs.map { packetCodecRegistration ->
                packetCodecRegistration.packetRouteKey
            }.filter { packetRouteKey -> packetRouteKey.connectionState == ConnectionState.CONFIGURATION },
        )
        neoForgeNetworkConfiguration.components.forEach { (neoForgeConnectionProtocol, components) ->
            val connectionState = neoForgeConnectionProtocol.toConnectionState()
            components.forEach { neoForgeNetworkComponent ->
                val directions = neoForgeNetworkComponent.neoForgePacketFlow
                    ?.let { neoForgePacketFlow -> listOf(neoForgePacketFlow.toPacketDirection()) }
                    ?: listOf(
                        PacketDirection.CLIENTBOUND,
                        PacketDirection.SERVERBOUND,
                    )
                directions.forEach { packetDirection ->
                    add(
                        PacketRouteKey.CustomPayload(
                            connectionState,
                            packetDirection,
                            neoForgeNetworkComponent.id,
                        ),
                    )
                }
            }
        }
    }
    val missing = required - minecraftPacketConnection.declaredExtensionRoutes
    require(missing.isEmpty()) {
        "NeoForge profile is missing extension packet codecs $missing"
    }
}

private fun playListeningChannels(
    neoForgeNetworkConfiguration: NeoForgeNetworkConfiguration,
    incomingFlow: NeoForgePacketFlow,
    neoForgePeer: Boolean,
): Set<Identifier> = buildSet {
    add(NeoForgeChannels.Register)
    add(NeoForgeChannels.Unregister)
    if (neoForgePeer) {
        add(NeoForgeChannels.NetworkQuery)
    } else {
        addAll(
            neoForgeNetworkConfiguration.optionalChannels(
                NeoForgeConnectionProtocol.PLAY,
                incomingFlow,
            ),
        )
    }
}

private fun validateCommonVersions(versions: Set<Int>) {
    require(versions.isNotEmpty()) {
        "NeoForge common version set must not be empty"
    }
    require(versions.all { it > 0 }) {
        "NeoForge common versions must be positive"
    }
    require(versions.size <= NeoForgeProtocol.MAX_COMMON_VERSIONS) {
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
    neoForgeCommonRegisterPacket: NeoForgeCommonRegisterPacket,
    commonVersion: Int?,
) {
    val selected = commonVersion ?: throw NeoForgeNegotiationException(
        "NeoForge common channels arrived before version negotiation",
    )
    if (neoForgeCommonRegisterPacket.version != selected) {
        throw NeoForgeNegotiationException(
            "NeoForge common channel version ${neoForgeCommonRegisterPacket.version} does not match $selected",
        )
    }
    if (neoForgeCommonRegisterPacket.protocol != NeoForgeConnectionProtocol.PLAY.id) {
        throw NeoForgeNegotiationException(
            "NeoForge common registration used unsupported protocol ${neoForgeCommonRegisterPacket.protocol}",
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
        val remoteData = remoteEntry?.neoForgeEnumExtensionData
        val localData = localEntry?.neoForgeEnumExtensionData
        when {
            remoteData == null && localData == null -> false
            remoteEntry == null || localEntry == null -> true
            remoteEntry.neoForgeNetworkCheck != localEntry.neoForgeNetworkCheck -> true
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
