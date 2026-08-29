package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.toGameProfile
import com.hiczp.minecraft.protocol.client.MinecraftClientConnection
import com.hiczp.minecraft.protocol.client.negotiate
import com.hiczp.minecraft.protocol.datapack.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.datapack.withPlayLoginDimensionLayout
import com.hiczp.minecraft.protocol.fabric.FabricProtocol
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.*
import io.ktor.network.selector.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

class ClientToServerEndToEndTest {
    @Test
    fun vanillaDefaultsReachPlayWithOnlyConnectionFactsAndPlayerIdentity() = runTest {
        SelectorManager(Dispatchers.Default).use { selectorManager ->
            MinecraftServer.bind(
                selectorManager = selectorManager,
                host = "127.0.0.1",
                port = 0,
            ).use { minecraftServer ->
                val releaseServer = CompletableDeferred<Unit>()
                val serverNegotiation = async {
                    minecraftServer.accept().use { minecraftServerConnection ->
                        val minecraftServerNegotiationResult = assertNotNull(minecraftServerConnection.negotiate())
                        releaseServer.await()
                        minecraftServerNegotiationResult
                    }
                }
                val minecraftOfflineIdentity = MinecraftOfflineIdentity("VanillaDefaults")
                val minecraftClientNegotiationResult = try {
                    MinecraftClientConnection.connect(
                        selectorManager = selectorManager,
                        host = "127.0.0.1",
                        port = minecraftServer.port,
                    ).use { minecraftClientConnection ->
                        minecraftClientConnection.negotiate(minecraftOfflineIdentity).also {
                            assertEquals(ConnectionState.PLAY, minecraftClientConnection.connectionState)
                        }
                    }
                } finally {
                    releaseServer.complete(Unit)
                }
                val minecraftServerNegotiationResult = serverNegotiation.await()

                assertEquals(
                    minecraftOfflineIdentity.id,
                    minecraftClientNegotiationResult.loginSuccessPacket.profile.id
                )
                assertEquals(minecraftOfflineIdentity.id, minecraftServerNegotiationResult.gameProfile.id)
                assertEquals(
                    minecraftClientNegotiationResult.playLoginPacket,
                    minecraftServerNegotiationResult.playLoginPacket
                )
                assertEquals(
                    VanillaProtocolData.offeredKnownPacks,
                    minecraftClientNegotiationResult.dataPackConfigurationSnapshot.offeredKnownPacks,
                )
            }
        }
    }

    @Test
    fun publicNegotiationPrimitivesReachInitialPlay() = runTest {
        SelectorManager(Dispatchers.Default).use { selectorManager ->
            val minecraftConnectionDefinition = FabricProtocol.connectionDefinition()
            val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
                compressionThreshold = 64,
                gameMode = PlayerGameMode.CREATIVE,
                difficulty = Difficulty.HARD,
                difficultyLocked = true,
            )
            MinecraftServer.bind(
                selectorManager = selectorManager,
                host = "127.0.0.1",
                port = 0,
                minecraftConnectionDefinition = minecraftConnectionDefinition,
            ).use { minecraftServer ->
                val statusServer = async {
                    minecraftServer.accept().use { minecraftServerConnection ->
                        serveStatus(minecraftServerConnection, minecraftServerNegotiationOptions)
                    }
                }
                MinecraftClientConnection.connect(
                    selectorManager = selectorManager,
                    host = "127.0.0.1",
                    port = minecraftServer.port,
                    minecraftConnectionDefinition = minecraftConnectionDefinition,
                    connectionDispatcher = Dispatchers.Default,
                ).use { minecraftClientConnection ->
                    requestStatus(minecraftClientConnection)
                    statusServer.await()
                }

                val testClientProfile = TestClientProfile()
                val testServerProfile = TestServerProfile()
                val playServer = async {
                    minecraftServer.accept().use { minecraftServerConnection ->
                        negotiateServerPlay(
                            minecraftServerConnection = minecraftServerConnection,
                            minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
                            serverNegotiationProfile = testServerProfile,
                        )
                    }
                }
                val minecraftOfflineIdentity = MinecraftOfflineIdentity("ProtocolProbe")
                val (clientOutcome, serverOutcome) = MinecraftClientConnection.connect(
                    selectorManager = selectorManager,
                    host = "127.0.0.1",
                    port = minecraftServer.port,
                    minecraftConnectionDefinition = minecraftConnectionDefinition,
                ).use { minecraftClientConnection ->
                    val clientResult = negotiateClientPlay(
                        minecraftClientConnection = minecraftClientConnection,
                        minecraftOfflineIdentity = minecraftOfflineIdentity,
                        clientNegotiationProfile = testClientProfile,
                    )
                    clientResult to playServer.await()
                }

                assertEquals(minecraftOfflineIdentity.id, clientOutcome.loginSuccessPacket.profile.id)
                assertEquals(minecraftOfflineIdentity.id, serverOutcome.gameProfile.id)
                assertEquals(clientOutcome.playLoginPacket, serverOutcome.playLoginPacket)
                assertEquals(PlayerGameMode.CREATIVE, clientOutcome.playLoginPacket.spawnInfo.gameMode)
                assertEquals(1, serverOutcome.minecraftInitialWorld.chunks.size)
                assertEquals(1, serverOutcome.minecraftInitialWorld.entities.size)
            }
        }
    }

    private suspend fun serveStatus(
        minecraftServerConnection: MinecraftServerConnection,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ) {
        val handshakePacket = assertIs<HandshakePacket>(minecraftServerConnection.incoming.receive())
        assertEquals(HandshakeNextState.STATUS, handshakePacket.nextState)
        assertEquals(ConnectionState.STATUS, minecraftServerConnection.connectionState)
        assertEquals(StatusRequestPacket, minecraftServerConnection.incoming.receive())
        minecraftServerConnection.outgoing.send(StatusResponsePacket(minecraftServerNegotiationOptions.statusJson()))
        minecraftServerConnection.requestFlush()
        val statusPingRequestPacket = assertIs<StatusPingRequestPacket>(minecraftServerConnection.incoming.receive())
        minecraftServerConnection.outgoing.send(StatusPongResponsePacket(statusPingRequestPacket.timestamp))
        minecraftServerConnection.outgoing.close()
        minecraftServerConnection.awaitClosed()
    }

    private suspend fun requestStatus(
        minecraftClientConnection: MinecraftClientConnection,
    ) {
        minecraftClientConnection.outgoing.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = minecraftClientConnection.serverAddress,
                serverPort = minecraftClientConnection.serverPort,
                nextState = HandshakeNextState.STATUS,
            ),
        )
        minecraftClientConnection.outgoing.send(StatusRequestPacket)
        minecraftClientConnection.requestFlush()
        val statusResponsePacket = assertIs<StatusResponsePacket>(minecraftClientConnection.incoming.receive())
        val statusDocument = Json.parseToJsonElement(statusResponsePacket.jsonResponse).jsonObject
        assertEquals(
            MinecraftProtocol.PROTOCOL_VERSION,
            statusDocument.getValue("version")
                .jsonObject
                .getValue("protocol")
                .jsonPrimitive
                .int,
        )
        minecraftClientConnection.outgoing.send(StatusPingRequestPacket(STATUS_PING_ID))
        minecraftClientConnection.requestFlush()
        assertEquals(
            StatusPongResponsePacket(STATUS_PING_ID),
            minecraftClientConnection.incoming.receive(),
        )
    }

    private suspend fun negotiateServerPlay(
        minecraftServerConnection: MinecraftServerConnection,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        serverNegotiationProfile: ServerNegotiationProfile,
    ): ServerPlayOutcome {
        serverNegotiationProfile.begin(minecraftServerConnection)
        val handshakePacket = assertIs<HandshakePacket>(minecraftServerConnection.incoming.receive())
        serverNegotiationProfile.acceptHandshake(handshakePacket)
        assertEquals(HandshakeNextState.LOGIN, handshakePacket.nextState)
        assertEquals(MinecraftProtocol.PROTOCOL_VERSION, handshakePacket.protocolVersion)
        val loginStartPacket = assertIs<LoginStartPacket>(minecraftServerConnection.incoming.receive())
        val gameProfile = MinecraftOfflineIdentity(loginStartPacket.name).toGameProfile()

        serverNegotiationProfile.negotiateLogin(minecraftServerConnection)
        minecraftServerNegotiationOptions.compressionThreshold?.let {
            minecraftServerConnection.outgoing.send(
                SetCompressionPacket(it)
            )
        }
        minecraftServerConnection.outgoing.send(
            LoginSuccessPacket(
                gameProfile,
                minecraftServerNegotiationOptions.sessionId
            )
        )
        minecraftServerConnection.requestFlush()
        assertEquals(LoginAcknowledgedPacket, minecraftServerConnection.incoming.receive())
        minecraftServerConnection.awaitState(ConnectionState.CONFIGURATION)
        minecraftServerConnection.enableConfigurationKeepAlive()

        val clientInformation = assertIs<ConfigurationClientInformationPacket>(
            minecraftServerConnection.incoming.receive(),
        ).information
        serverNegotiationProfile.negotiateConfigurationStart(minecraftServerConnection)
        minecraftServerConnection.outgoing.send(FeatureFlagsPacket(minecraftServerNegotiationOptions.protocolData.enabledFeatureFlags))
        serverNegotiationProfile.negotiateEarlyConfiguration(minecraftServerConnection)
        minecraftServerConnection.outgoing.send(
            ConfigurationClientboundKnownPacksPacket(minecraftServerNegotiationOptions.protocolData.offeredKnownPacks),
        )
        minecraftServerConnection.requestFlush()
        val acceptedKnownPacks = assertIs<ConfigurationServerboundKnownPacksPacket>(
            minecraftServerConnection.incoming.receive(),
        ).knownPacks
        val synchronizedRegistryPackets =
            minecraftServerNegotiationOptions.protocolData.synchronizedRegistryPackets(acceptedKnownPacks)
        synchronizedRegistryPackets.forEach { registryDataPacket ->
            minecraftServerConnection.outgoing.send(
                registryDataPacket
            )
        }
        minecraftServerConnection.outgoing.send(ConfigurationUpdateTagsPacket(minecraftServerNegotiationOptions.protocolData.registryTags))
        serverNegotiationProfile.negotiateConfiguration(minecraftServerConnection)

        val playLoginPacket = minecraftServerNegotiationOptions.createPlayLoginPacket(gameProfile, onlineMode = false)
        val baseProtocolRegistryContext = minecraftServerNegotiationOptions.protocolData
            .resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
            .withPlayLoginDimensionLayout(
                playLoginPacket,
                synchronizedRegistryPackets,
                minecraftServerNegotiationOptions.protocolData
            )
        minecraftServerConnection.installProtocolRegistryContext(
            serverNegotiationProfile.resolveProtocolRegistryContext(baseProtocolRegistryContext),
        )
        minecraftServerConnection.outgoing.send(FinishConfigurationPacket)
        minecraftServerConnection.requestFlush()
        assertEquals(AcknowledgeFinishConfigurationPacket, minecraftServerConnection.incoming.receive())
        minecraftServerConnection.disableKeepAlive()
        minecraftServerConnection.awaitState(ConnectionState.PLAY)
        val recordingKeepAlive = minecraftServerConnection.enableRecordingPlayKeepAlive(100.milliseconds)
        serverNegotiationProfile.preparePlay(minecraftServerConnection)
        minecraftServerConnection.outgoing.send(playLoginPacket)
        assertSame(TestProfileResult, serverNegotiationProfile.complete(minecraftServerConnection))

        val minecraftInitialWorld = MinecraftInitialWorld.flatVanilla(
            minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
            chunkRadius = 0,
            entities = listOf(testPig()),
        )
        minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
        minecraftServerConnection.requestFlush()

        var teleportConfirmed = false
        var chunkBatchConfirmed = false
        while (!(teleportConfirmed && chunkBatchConfirmed)) {
            when (val serverboundPacket = minecraftServerConnection.incoming.receive()) {
                is ConfirmTeleportationPacket ->
                    teleportConfirmed =
                        serverboundPacket.teleportId == minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId

                is ChunkBatchReceivedPacket -> chunkBatchConfirmed = true
                else -> Unit
            }
        }
        recordingKeepAlive.roundTrip.await()
        assertTrue(teleportConfirmed)
        assertTrue(chunkBatchConfirmed)
        assertEquals("en_us", clientInformation.locale)
        assertEquals(
            PROFILE_REGISTRY_SIZE,
            minecraftServerConnection.protocolRegistryContext.registrySize(PROFILE_REGISTRY),
        )
        assertEquals(minecraftServerConnection.declaredExtensionRoutes, minecraftServerConnection.activeExtensionRoutes)
        return ServerPlayOutcome(
            gameProfile = gameProfile,
            playLoginPacket = playLoginPacket,
            minecraftInitialWorld = minecraftInitialWorld,
        )
    }

    private suspend fun negotiateClientPlay(
        minecraftClientConnection: MinecraftClientConnection,
        minecraftOfflineIdentity: MinecraftOfflineIdentity,
        clientNegotiationProfile: ClientNegotiationProfile,
    ): ClientPlayOutcome {
        clientNegotiationProfile.begin(minecraftClientConnection)
        minecraftClientConnection.outgoing.send(
            clientNegotiationProfile.prepareHandshake(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = minecraftClientConnection.serverAddress,
                    serverPort = minecraftClientConnection.serverPort,
                    nextState = HandshakeNextState.LOGIN,
                ),
            ),
        )
        minecraftClientConnection.outgoing.send(
            LoginStartPacket(
                minecraftOfflineIdentity.name,
                minecraftOfflineIdentity.id
            )
        )
        minecraftClientConnection.requestFlush()
        val firstLoginPacket = minecraftClientConnection.incoming.receive()
        val loginSuccessPacket = if (firstLoginPacket is SetCompressionPacket) {
            assertIs<LoginSuccessPacket>(minecraftClientConnection.incoming.receive())
        } else {
            assertIs<LoginSuccessPacket>(firstLoginPacket)
        }
        minecraftClientConnection.outgoing.send(LoginAcknowledgedPacket)
        minecraftClientConnection.awaitState(ConnectionState.CONFIGURATION)

        minecraftClientConnection.outgoing.send(
            ConfigurationClientInformationPacket(
                ClientInformation(
                    locale = "en_us",
                    viewDistance = 8,
                    chatMode = ChatMode.ENABLED,
                    chatColors = true,
                    displayedSkinParts = 0x7F,
                    mainHand = MainHand.RIGHT,
                    enableTextFiltering = false,
                    allowServerListings = true,
                    particleStatus = ParticleStatus.ALL,
                ),
            ),
        )
        minecraftClientConnection.requestFlush()
        val synchronizedRegistryPackets = mutableListOf<RegistryDataPacket>()
        var configurationFinished = false
        while (!configurationFinished) {
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is FeatureFlagsPacket -> assertEquals(
                    FeatureFlagsPacket(VanillaProtocolData.enabledFeatureFlags),
                    clientboundPacket,
                )

                is ConfigurationClientboundKnownPacksPacket -> {
                    minecraftClientConnection.outgoing.send(ConfigurationServerboundKnownPacksPacket(clientboundPacket.knownPacks))
                    minecraftClientConnection.requestFlush()
                }

                is RegistryDataPacket -> synchronizedRegistryPackets += clientboundPacket
                is ConfigurationUpdateTagsPacket -> assertEquals(
                    ConfigurationUpdateTagsPacket(VanillaProtocolData.registryTags),
                    clientboundPacket,
                )

                is FinishConfigurationPacket -> {
                    val resolvedProtocolRegistryContext = VanillaProtocolData.resolveSynchronizedRegistryContext(
                        synchronizedRegistryPackets,
                    )
                    val profileProtocolRegistryContext =
                        clientNegotiationProfile.resolveProtocolRegistryContext(resolvedProtocolRegistryContext)
                    minecraftClientConnection.installProtocolRegistryContext(profileProtocolRegistryContext)
                    clientNegotiationProfile.preparePlay(minecraftClientConnection)
                    minecraftClientConnection.outgoing.send(AcknowledgeFinishConfigurationPacket)
                    minecraftClientConnection.requestFlush()
                    minecraftClientConnection.awaitState(ConnectionState.PLAY)
                    configurationFinished = true
                }

                else -> fail("Unexpected Configuration packet ${clientboundPacket::class.simpleName}")
            }
        }

        val playLoginPacket = assertIs<PlayLoginPacket>(minecraftClientConnection.incoming.receive())
        val activeProtocolRegistryContext =
            minecraftClientConnection.protocolRegistryContext.withPlayLoginDimensionLayout(
                playLoginPacket = playLoginPacket,
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                protocolData = VanillaProtocolData,
            )
        minecraftClientConnection.installProtocolRegistryContext(activeProtocolRegistryContext)
        assertSame(TestProfileResult, clientNegotiationProfile.complete(minecraftClientConnection))

        var chunkReceived = false
        var entityReceived = false
        var difficultyReceived = false
        var playerAbilities: PlayerAbilities? = null
        while (!(chunkReceived && entityReceived && difficultyReceived && playerAbilities != null)) {
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is SynchronizePlayerPositionPacket -> {
                    minecraftClientConnection.outgoing.send(ConfirmTeleportationPacket(clientboundPacket.teleportId))
                    minecraftClientConnection.requestFlush()
                }

                is ChunkDataAndUpdateLightPacket -> chunkReceived = true
                is ChunkBatchFinishedPacket -> {
                    minecraftClientConnection.outgoing.send(ChunkBatchReceivedPacket(desiredChunksPerTick = 10.0f))
                    minecraftClientConnection.requestFlush()
                }

                is ClientboundBundlePacket -> entityReceived = clientboundPacket.subPackets
                    .filterIsInstance<SpawnEntityPacket>()
                    .any { spawnEntityPacket ->
                        spawnEntityPacket.typeId == testPig().typeId(VanillaProtocolData.completeProtocolRegistryContext)
                    }

                is ClientboundChangeDifficultyPacket ->
                    difficultyReceived = clientboundPacket.difficulty == Difficulty.HARD && clientboundPacket.locked

                is ClientboundPlayerAbilitiesPacket -> playerAbilities = clientboundPacket.abilities
                else -> Unit
            }
        }
        assertTrue(chunkReceived)
        assertTrue(entityReceived)
        assertTrue(difficultyReceived)
        assertPlayerAbilitiesEqual(
            expected = MinecraftInitialWorldBootstrap.vanillaPlayerAbilities(PlayerGameMode.CREATIVE),
            actual = assertNotNull(playerAbilities),
        )
        assertEquals(
            PROFILE_REGISTRY_SIZE,
            minecraftClientConnection.protocolRegistryContext.registrySize(PROFILE_REGISTRY),
        )
        assertEquals(minecraftClientConnection.declaredExtensionRoutes, minecraftClientConnection.activeExtensionRoutes)
        return ClientPlayOutcome(
            loginSuccessPacket = loginSuccessPacket,
            playLoginPacket = playLoginPacket,
        )
    }

    private fun testPig(): MinecraftEntitySnapshot = MinecraftEntitySnapshot(
        entityId = 2,
        uuid = Uuid.fromLongs(0, 2),
        type = Identifier("pig"),
        position = Vector3d(3.5, 65.0, 3.5),
    )

    private fun assertPlayerAbilitiesEqual(
        expected: PlayerAbilities,
        actual: PlayerAbilities,
    ) {
        assertEquals(expected.invulnerable, actual.invulnerable)
        assertEquals(expected.flying, actual.flying)
        assertEquals(expected.canFly, actual.canFly)
        assertEquals(expected.instantBuild, actual.instantBuild)
        assertEquals(expected.flyingSpeed.toRawBits(), actual.flyingSpeed.toRawBits())
        assertEquals(expected.walkingSpeed.toRawBits(), actual.walkingSpeed.toRawBits())
    }

    private companion object {
        const val STATUS_PING_ID: Long = 42
    }
}

private data class ServerPlayOutcome(
    val gameProfile: GameProfile,
    val playLoginPacket: PlayLoginPacket,
    val minecraftInitialWorld: MinecraftInitialWorld,
)

private data class ClientPlayOutcome(
    val loginSuccessPacket: LoginSuccessPacket,
    val playLoginPacket: PlayLoginPacket,
)

private data object TestProfileResult : NegotiationProfileResult

private class TestClientProfile : ClientNegotiationProfile {
    override suspend fun begin(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        minecraftClientPacketConnection.activateExtensionRoutes(minecraftClientPacketConnection.declaredExtensionRoutes)
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext = protocolRegistryContext.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

    override suspend fun complete(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ): NegotiationProfileResult = TestProfileResult
}

private class TestServerProfile : ServerNegotiationProfile {
    override suspend fun begin(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        minecraftServerPacketConnection.activateExtensionRoutes(minecraftServerPacketConnection.declaredExtensionRoutes)
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext = protocolRegistryContext.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

    override suspend fun complete(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult = TestProfileResult
}

private val PROFILE_REGISTRY: Identifier = Identifier("test:profile")
private const val PROFILE_REGISTRY_SIZE: Int = 7
