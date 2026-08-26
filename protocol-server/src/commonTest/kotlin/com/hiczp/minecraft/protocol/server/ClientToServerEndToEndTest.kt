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
        SelectorManager(Dispatchers.Default).use { selector ->
            MinecraftServer.bind(
                selectorManager = selector,
                host = "127.0.0.1",
                port = 0,
            ).use { server ->
                val releaseServer = CompletableDeferred<Unit>()
                val serverNegotiation = async {
                    server.accept().use { connection ->
                        val result = assertNotNull(connection.negotiate())
                        releaseServer.await()
                        result
                    }
                }
                val identity = MinecraftOfflineIdentity("VanillaDefaults")
                val clientResult = try {
                    MinecraftClientConnection.connect(
                        selectorManager = selector,
                        host = "127.0.0.1",
                        port = server.port,
                    ).use { connection ->
                        connection.negotiate(identity).also {
                            assertEquals(ConnectionState.PLAY, connection.state)
                        }
                    }
                } finally {
                    releaseServer.complete(Unit)
                }
                val serverResult = serverNegotiation.await()

                assertEquals(identity.id, clientResult.loginSuccessPacket.profile.id)
                assertEquals(identity.id, serverResult.gameProfile.id)
                assertEquals(clientResult.playLoginPacket, serverResult.playLoginPacket)
                assertEquals(
                    VanillaProtocolData.offeredKnownPacks,
                    clientResult.dataPackConfigurationSnapshot.offeredKnownPacks,
                )
            }
        }
    }

    @Test
    fun publicNegotiationPrimitivesReachInitialPlay() = runTest {
        SelectorManager(Dispatchers.Default).use { selector ->
            val definition = FabricProtocol.connectionDefinition()
            val options = MinecraftServerNegotiationOptions(
                compressionThreshold = 64,
                gameMode = PlayerGameMode.CREATIVE,
                difficulty = Difficulty.HARD,
                difficultyLocked = true,
            )
            MinecraftServer.bind(
                selectorManager = selector,
                host = "127.0.0.1",
                port = 0,
                definition = definition,
            ).use { server ->
                val statusServer = async {
                    server.accept().use { connection ->
                        serveStatus(connection, options)
                    }
                }
                MinecraftClientConnection.connect(
                    selectorManager = selector,
                    host = "127.0.0.1",
                    port = server.port,
                    definition = definition,
                    connectionDispatcher = Dispatchers.Default,
                ).use { connection ->
                    requestStatus(connection)
                    statusServer.await()
                }

                val clientProfile = TestClientProfile()
                val serverProfile = TestServerProfile()
                val playServer = async {
                    server.accept().use { connection ->
                        negotiateServerPlay(
                            connection = connection,
                            options = options,
                            profile = serverProfile,
                        )
                    }
                }
                val identity = MinecraftOfflineIdentity("ProtocolProbe")
                val (clientOutcome, serverOutcome) = MinecraftClientConnection.connect(
                    selectorManager = selector,
                    host = "127.0.0.1",
                    port = server.port,
                    definition = definition,
                ).use { connection ->
                    val clientResult = negotiateClientPlay(
                        connection = connection,
                        identity = identity,
                        profile = clientProfile,
                    )
                    clientResult to playServer.await()
                }

                assertEquals(identity.id, clientOutcome.loginSuccessPacket.profile.id)
                assertEquals(identity.id, serverOutcome.gameProfile.id)
                assertEquals(clientOutcome.playLoginPacket, serverOutcome.playLoginPacket)
                assertEquals(PlayerGameMode.CREATIVE, clientOutcome.playLoginPacket.spawnInfo.gameMode)
                assertEquals(1, serverOutcome.initialWorld.chunks.size)
                assertEquals(1, serverOutcome.initialWorld.entities.size)
            }
        }
    }

    private suspend fun serveStatus(
        connection: MinecraftServerConnection,
        options: MinecraftServerNegotiationOptions,
    ) {
        val handshake = assertIs<HandshakePacket>(connection.incoming.receive())
        assertEquals(HandshakeNextState.STATUS, handshake.nextState)
        assertEquals(ConnectionState.STATUS, connection.state)
        assertEquals(StatusRequestPacket, connection.incoming.receive())
        connection.outgoing.send(StatusResponsePacket(options.statusJson()))
        connection.requestFlush()
        val ping = assertIs<StatusPingRequestPacket>(connection.incoming.receive())
        connection.outgoing.send(StatusPongResponsePacket(ping.timestamp))
        connection.outgoing.close()
        connection.awaitClosed()
    }

    private suspend fun requestStatus(
        connection: MinecraftClientConnection,
    ) {
        connection.outgoing.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = connection.serverAddress,
                serverPort = connection.serverPort,
                nextState = HandshakeNextState.STATUS,
            ),
        )
        connection.outgoing.send(StatusRequestPacket)
        connection.requestFlush()
        val response = assertIs<StatusResponsePacket>(connection.incoming.receive())
        val statusDocument = Json.parseToJsonElement(response.jsonResponse).jsonObject
        assertEquals(
            MinecraftProtocol.PROTOCOL_VERSION,
            statusDocument.getValue("version")
                .jsonObject
                .getValue("protocol")
                .jsonPrimitive
                .int,
        )
        connection.outgoing.send(StatusPingRequestPacket(STATUS_PING_ID))
        connection.requestFlush()
        assertEquals(
            StatusPongResponsePacket(STATUS_PING_ID),
            connection.incoming.receive(),
        )
    }

    private suspend fun negotiateServerPlay(
        connection: MinecraftServerConnection,
        options: MinecraftServerNegotiationOptions,
        profile: ServerNegotiationProfile,
    ): ServerPlayOutcome {
        profile.begin(connection)
        val handshake = assertIs<HandshakePacket>(connection.incoming.receive())
        profile.acceptHandshake(handshake)
        assertEquals(HandshakeNextState.LOGIN, handshake.nextState)
        assertEquals(MinecraftProtocol.PROTOCOL_VERSION, handshake.protocolVersion)
        val start = assertIs<LoginStartPacket>(connection.incoming.receive())
        val gameProfile = MinecraftOfflineIdentity(start.name).toGameProfile()

        profile.negotiateLogin(connection)
        options.compressionThreshold?.let { connection.outgoing.send(SetCompressionPacket(it)) }
        connection.outgoing.send(LoginSuccessPacket(gameProfile, options.sessionId))
        connection.requestFlush()
        assertEquals(LoginAcknowledgedPacket, connection.incoming.receive())
        connection.awaitState(ConnectionState.CONFIGURATION)
        connection.enableConfigurationKeepAlive()

        val clientInformation = assertIs<ConfigurationClientInformationPacket>(
            connection.incoming.receive(),
        ).information
        profile.negotiateConfigurationStart(connection)
        connection.outgoing.send(FeatureFlagsPacket(options.protocolData.enabledFeatureFlags))
        profile.negotiateEarlyConfiguration(connection)
        connection.outgoing.send(
            ConfigurationClientboundKnownPacksPacket(options.protocolData.offeredKnownPacks),
        )
        connection.requestFlush()
        val acceptedKnownPacks = assertIs<ConfigurationServerboundKnownPacksPacket>(
            connection.incoming.receive(),
        ).knownPacks
        val synchronizedRegistryPackets = options.protocolData.synchronizedRegistryPackets(acceptedKnownPacks)
        synchronizedRegistryPackets.forEach { registryDataPacket -> connection.outgoing.send(registryDataPacket) }
        connection.outgoing.send(ConfigurationUpdateTagsPacket(options.protocolData.registryTags))
        profile.negotiateConfiguration(connection)

        val playLoginPacket = options.createPlayLoginPacket(gameProfile, onlineMode = false)
        val baseProtocolRegistryContext = options.protocolData
            .resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
            .withPlayLoginDimensionLayout(playLoginPacket, synchronizedRegistryPackets, options.protocolData)
        connection.installProtocolRegistryContext(
            profile.resolveProtocolRegistryContext(baseProtocolRegistryContext),
        )
        connection.outgoing.send(FinishConfigurationPacket)
        connection.requestFlush()
        assertEquals(AcknowledgeFinishConfigurationPacket, connection.incoming.receive())
        connection.disableKeepAlive()
        connection.awaitState(ConnectionState.PLAY)
        val keepAlive = connection.enableRecordingPlayKeepAlive(100.milliseconds)
        profile.preparePlay(connection)
        connection.outgoing.send(playLoginPacket)
        assertSame(TestProfileResult, profile.complete(connection))

        val world = MinecraftInitialWorld.flatVanilla(
            options = options,
            chunkRadius = 0,
            entities = listOf(testPig()),
        )
        connection.synchronizeInitialWorld(world)
        connection.requestFlush()

        var teleportConfirmed = false
        var chunkBatchConfirmed = false
        while (!(teleportConfirmed && chunkBatchConfirmed)) {
            when (val packet = connection.incoming.receive()) {
                is ConfirmTeleportationPacket ->
                    teleportConfirmed = packet.teleportId == world.bootstrap.teleportId

                is ChunkBatchReceivedPacket -> chunkBatchConfirmed = true
                else -> Unit
            }
        }
        keepAlive.roundTrip.await()
        assertTrue(teleportConfirmed)
        assertTrue(chunkBatchConfirmed)
        assertEquals("en_us", clientInformation.locale)
        assertEquals(
            PROFILE_REGISTRY_SIZE,
            connection.protocolRegistryContext.registrySize(PROFILE_REGISTRY),
        )
        assertEquals(connection.declaredExtensionRoutes, connection.activeExtensionRoutes)
        return ServerPlayOutcome(
            gameProfile = gameProfile,
            playLoginPacket = playLoginPacket,
            initialWorld = world,
        )
    }

    private suspend fun negotiateClientPlay(
        connection: MinecraftClientConnection,
        identity: MinecraftOfflineIdentity,
        profile: ClientNegotiationProfile,
    ): ClientPlayOutcome {
        profile.begin(connection)
        connection.outgoing.send(
            profile.prepareHandshake(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = connection.serverAddress,
                    serverPort = connection.serverPort,
                    nextState = HandshakeNextState.LOGIN,
                ),
            ),
        )
        connection.outgoing.send(LoginStartPacket(identity.name, identity.id))
        connection.requestFlush()
        val firstLoginPacket = connection.incoming.receive()
        val loginSuccessPacket = if (firstLoginPacket is SetCompressionPacket) {
            assertIs<LoginSuccessPacket>(connection.incoming.receive())
        } else {
            assertIs<LoginSuccessPacket>(firstLoginPacket)
        }
        connection.outgoing.send(LoginAcknowledgedPacket)
        connection.awaitState(ConnectionState.CONFIGURATION)

        connection.outgoing.send(
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
        connection.requestFlush()
        val synchronizedRegistryPackets = mutableListOf<RegistryDataPacket>()
        var configurationFinished = false
        while (!configurationFinished) {
            when (val packet = connection.incoming.receive()) {
                is FeatureFlagsPacket -> assertEquals(
                    FeatureFlagsPacket(VanillaProtocolData.enabledFeatureFlags),
                    packet,
                )
                is ConfigurationClientboundKnownPacksPacket -> {
                    connection.outgoing.send(ConfigurationServerboundKnownPacksPacket(packet.knownPacks))
                    connection.requestFlush()
                }

                is RegistryDataPacket -> synchronizedRegistryPackets += packet
                is ConfigurationUpdateTagsPacket -> assertEquals(
                    ConfigurationUpdateTagsPacket(VanillaProtocolData.registryTags),
                    packet,
                )
                is FinishConfigurationPacket -> {
                    val resolvedProtocolRegistryContext = VanillaProtocolData.resolveSynchronizedRegistryContext(
                        synchronizedRegistryPackets,
                    )
                    val profileProtocolRegistryContext =
                        profile.resolveProtocolRegistryContext(resolvedProtocolRegistryContext)
                    connection.installProtocolRegistryContext(profileProtocolRegistryContext)
                    profile.preparePlay(connection)
                    connection.outgoing.send(AcknowledgeFinishConfigurationPacket)
                    connection.requestFlush()
                    connection.awaitState(ConnectionState.PLAY)
                    configurationFinished = true
                }

                else -> fail("Unexpected Configuration packet ${packet::class.simpleName}")
            }
        }

        val playLoginPacket = assertIs<PlayLoginPacket>(connection.incoming.receive())
        val activeProtocolRegistryContext = connection.protocolRegistryContext.withPlayLoginDimensionLayout(
            playLoginPacket = playLoginPacket,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            protocolData = VanillaProtocolData,
        )
        connection.installProtocolRegistryContext(activeProtocolRegistryContext)
        assertSame(TestProfileResult, profile.complete(connection))

        var chunkReceived = false
        var entityReceived = false
        var difficultyReceived = false
        var abilities: PlayerAbilities? = null
        while (!(chunkReceived && entityReceived && difficultyReceived && abilities != null)) {
            when (val packet = connection.incoming.receive()) {
                is SynchronizePlayerPositionPacket -> {
                    connection.outgoing.send(ConfirmTeleportationPacket(packet.teleportId))
                    connection.requestFlush()
                }

                is ChunkDataAndUpdateLightPacket -> chunkReceived = true
                is ChunkBatchFinishedPacket -> {
                    connection.outgoing.send(ChunkBatchReceivedPacket(desiredChunksPerTick = 10.0f))
                    connection.requestFlush()
                }

                is ClientboundBundlePacket -> entityReceived = packet.subPackets
                    .filterIsInstance<SpawnEntityPacket>()
                    .any { spawn ->
                        spawn.typeId == testPig().typeId(VanillaProtocolData.completeProtocolRegistryContext)
                    }

                is ClientboundChangeDifficultyPacket ->
                    difficultyReceived = packet.difficulty == Difficulty.HARD && packet.locked

                is ClientboundPlayerAbilitiesPacket -> abilities = packet.abilities
                else -> Unit
            }
        }
        assertTrue(chunkReceived)
        assertTrue(entityReceived)
        assertTrue(difficultyReceived)
        assertPlayerAbilitiesEqual(
            expected = MinecraftInitialWorldBootstrap.vanillaPlayerAbilities(PlayerGameMode.CREATIVE),
            actual = assertNotNull(abilities),
        )
        assertEquals(
            PROFILE_REGISTRY_SIZE,
            connection.protocolRegistryContext.registrySize(PROFILE_REGISTRY),
        )
        assertEquals(connection.declaredExtensionRoutes, connection.activeExtensionRoutes)
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
    val initialWorld: MinecraftInitialWorld,
)

private data class ClientPlayOutcome(
    val loginSuccessPacket: LoginSuccessPacket,
    val playLoginPacket: PlayLoginPacket,
)

private data object TestProfileResult : NegotiationProfileResult

private class TestClientProfile : ClientNegotiationProfile {
    override suspend fun begin(
        connection: MinecraftClientPacketConnection,
    ) {
        connection.activateExtensionRoutes(connection.declaredExtensionRoutes)
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext = protocolRegistryContext.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

    override suspend fun complete(
        connection: MinecraftClientPacketConnection,
    ): NegotiationProfileResult = TestProfileResult
}

private class TestServerProfile : ServerNegotiationProfile {
    override suspend fun begin(
        connection: MinecraftServerPacketConnection,
    ) {
        connection.activateExtensionRoutes(connection.declaredExtensionRoutes)
    }

    override suspend fun resolveProtocolRegistryContext(
        protocolRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext = protocolRegistryContext.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

    override suspend fun complete(
        connection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult = TestProfileResult
}

private val PROFILE_REGISTRY: Identifier = Identifier("test:profile")
private const val PROFILE_REGISTRY_SIZE: Int = 7
