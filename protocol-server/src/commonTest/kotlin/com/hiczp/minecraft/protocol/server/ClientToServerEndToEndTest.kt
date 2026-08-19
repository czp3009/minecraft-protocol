package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.toGameProfile
import com.hiczp.minecraft.protocol.client.MinecraftClientConnection
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.data.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.data.withPlayLoginDimension
import com.hiczp.minecraft.protocol.fabric.FabricProtocol
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.ClientNegotiationProfile
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.NegotiationProfileResult
import com.hiczp.minecraft.protocol.session.ServerNegotiationProfile
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import kotlin.uuid.Uuid
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

class ClientToServerEndToEndTest {
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

                assertEquals(identity.id, clientOutcome.login.profile.id)
                assertEquals(identity.id, serverOutcome.gameProfile.id)
                assertEquals(clientOutcome.playLogin, serverOutcome.playLogin)
                assertEquals(PlayerGameMode.CREATIVE, clientOutcome.playLogin.spawnInfo.gameMode)
                assertEquals(1, serverOutcome.synchronization.chunkCount)
                assertEquals(1, serverOutcome.synchronization.entityCount)
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
        assertEquals(LoginAcknowledgedPacket, connection.incoming.receive())
        connection.awaitState(ConnectionState.CONFIGURATION)

        val information = assertIs<ConfigurationClientInformationPacket>(
            connection.incoming.receive(),
        ).information
        profile.negotiateConfigurationStart(connection)
        connection.outgoing.send(options.protocolData.featureFlags)
        profile.negotiateEarlyConfiguration(connection)
        connection.outgoing.send(
            ConfigurationClientboundKnownPacksPacket(options.protocolData.knownPacks),
        )
        val acceptedKnownPacks = assertIs<ConfigurationServerboundKnownPacksPacket>(
            connection.incoming.receive(),
        ).knownPacks
        val registries = options.protocolData.registryPackets(acceptedKnownPacks)
        registries.forEach { connection.outgoing.send(it) }
        connection.outgoing.send(options.protocolData.tags)
        profile.negotiateConfiguration(connection)

        val playLogin = options.playLogin(gameProfile, onlineMode = false)
        options.validatePlayLogin(playLogin)
        val baseContext = options.protocolData
            .resolveSynchronizedRegistryContext(registries)
            .withPlayLoginDimension(playLogin, registries, options.protocolData)
        connection.installRegistryContext(profile.resolveRegistryContext(baseContext))
        connection.outgoing.send(FinishConfigurationPacket)
        assertEquals(AcknowledgeFinishConfigurationPacket, connection.incoming.receive())
        connection.awaitState(ConnectionState.PLAY)
        profile.preparePlay(connection)
        connection.outgoing.send(playLogin)
        assertSame(TestProfileResult, profile.complete(connection))

        val world = MinecraftInitialWorld.flatVanilla(
            options = options,
            chunkRadius = 0,
            entities = listOf(testPig()),
        )
        val synchronization = connection.synchronizeInitialWorld(
            world = world,
            login = playLogin,
        )
        connection.outgoing.send(PlayClientboundKeepAlivePacket(KEEP_ALIVE_ID))

        var teleportConfirmed = false
        var chunkBatchConfirmed = false
        var keepAliveConfirmed = false
        var remainingPackets = options.maximumPacketsPerPhase
        while (
            remainingPackets-- > 0 &&
            !(teleportConfirmed && chunkBatchConfirmed && keepAliveConfirmed)
        ) {
            when (val packet = connection.incoming.receive()) {
                is ConfirmTeleportationPacket ->
                    teleportConfirmed = packet.teleportId == synchronization.teleportId

                is ChunkBatchReceivedPacket -> chunkBatchConfirmed = true
                is PlayServerboundKeepAlivePacket ->
                    keepAliveConfirmed = packet.id == KEEP_ALIVE_ID

                else -> Unit
            }
        }
        assertTrue(teleportConfirmed)
        assertTrue(chunkBatchConfirmed)
        assertTrue(keepAliveConfirmed)
        assertEquals("en_us", information.locale)
        assertEquals(PROFILE_REGISTRY_SIZE, connection.registries.registrySize(PROFILE_REGISTRY))
        assertEquals(connection.declaredExtensionRoutes, connection.activeExtensionRoutes)
        return ServerPlayOutcome(
            gameProfile = gameProfile,
            playLogin = playLogin,
            synchronization = synchronization,
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
        val firstLoginPacket = connection.incoming.receive()
        val login = if (firstLoginPacket is SetCompressionPacket) {
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
        val registries = mutableListOf<RegistryDataPacket>()
        var configurationFinished = false
        while (!configurationFinished) {
            when (val packet = connection.incoming.receive()) {
                is FeatureFlagsPacket -> assertEquals(VanillaProtocolData.featureFlags, packet)
                is ConfigurationClientboundKnownPacksPacket -> connection.outgoing.send(
                    ConfigurationServerboundKnownPacksPacket(packet.knownPacks),
                )

                is RegistryDataPacket -> registries += packet
                is ConfigurationUpdateTagsPacket -> assertEquals(VanillaProtocolData.tags, packet)
                is FinishConfigurationPacket -> {
                    val resolved = VanillaProtocolData.resolveSynchronizedRegistryContext(registries)
                    val profiled = profile.resolveRegistryContext(resolved)
                    connection.installRegistryContext(profiled)
                    profile.preparePlay(connection)
                    connection.outgoing.send(AcknowledgeFinishConfigurationPacket)
                    connection.awaitState(ConnectionState.PLAY)
                    configurationFinished = true
                }

                else -> fail("Unexpected Configuration packet ${packet::class.simpleName}")
            }
        }

        val playLogin = assertIs<PlayLoginPacket>(connection.incoming.receive())
        val activeContext = connection.registries.withPlayLoginDimension(
            login = playLogin,
            registries = registries,
            protocolData = VanillaProtocolData,
        )
        connection.installRegistryContext(activeContext)
        assertSame(TestProfileResult, profile.complete(connection))

        var chunkReceived = false
        var entityReceived = false
        var keepAliveReceived = false
        var difficultyReceived = false
        var abilities: PlayerAbilities? = null
        while (!keepAliveReceived) {
            when (val packet = connection.incoming.receive()) {
                is SynchronizePlayerPositionPacket -> connection.outgoing.send(
                    ConfirmTeleportationPacket(packet.teleportId),
                )

                is ChunkDataAndUpdateLightPacket -> chunkReceived = true
                is ChunkBatchFinishedPacket -> connection.outgoing.send(
                    ChunkBatchReceivedPacket(desiredChunksPerTick = 10.0f),
                )

                is SpawnEntityPacket -> entityReceived = packet.typeId == testPig().typeId(
                    VanillaProtocolData.registryContext,
                )

                is ClientboundChangeDifficultyPacket ->
                    difficultyReceived = packet.difficulty == Difficulty.HARD && packet.locked

                is ClientboundPlayerAbilitiesPacket -> abilities = packet.abilities
                is PlayClientboundKeepAlivePacket -> {
                    assertEquals(KEEP_ALIVE_ID, packet.id)
                    connection.outgoing.send(PlayServerboundKeepAlivePacket(packet.id))
                    keepAliveReceived = true
                }

                else -> Unit
            }
        }
        assertTrue(chunkReceived)
        assertTrue(entityReceived)
        assertTrue(difficultyReceived)
        assertPlayerAbilitiesEqual(
            expected = MinecraftInitialWorld.vanillaPlayerAbilities(PlayerGameMode.CREATIVE),
            actual = assertNotNull(abilities),
        )
        assertEquals(PROFILE_REGISTRY_SIZE, connection.registries.registrySize(PROFILE_REGISTRY))
        assertEquals(connection.declaredExtensionRoutes, connection.activeExtensionRoutes)
        return ClientPlayOutcome(
            login = login,
            playLogin = playLogin,
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
        const val KEEP_ALIVE_ID: Long = 0x1020_3040_5060_7080L
    }
}

private data class ServerPlayOutcome(
    val gameProfile: GameProfile,
    val playLogin: PlayLoginPacket,
    val synchronization: MinecraftInitialWorldSynchronization,
)

private data class ClientPlayOutcome(
    val login: LoginSuccessPacket,
    val playLogin: PlayLoginPacket,
)

private data object TestProfileResult : NegotiationProfileResult

private class TestClientProfile : ClientNegotiationProfile {
    override suspend fun begin(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ) {
        connection.activateExtensionRoutes(connection.declaredExtensionRoutes)
    }

    override suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext = context.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

    override suspend fun complete(
        connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    ): NegotiationProfileResult = TestProfileResult
}

private class TestServerProfile : ServerNegotiationProfile {
    override suspend fun begin(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ) {
        connection.activateExtensionRoutes(connection.declaredExtensionRoutes)
    }

    override suspend fun resolveRegistryContext(
        context: ProtocolRegistryContext,
    ): ProtocolRegistryContext = context.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

    override suspend fun complete(
        connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    ): NegotiationProfileResult = TestProfileResult
}

private val PROFILE_REGISTRY: Identifier = Identifier("test:profile")
private const val PROFILE_REGISTRY_SIZE: Int = 7
