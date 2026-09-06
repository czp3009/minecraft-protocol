package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.toGameProfile
import com.hiczp.minecraft.protocol.client.MinecraftClientConnection
import com.hiczp.minecraft.protocol.client.chunkPacketDecoder
import com.hiczp.minecraft.protocol.client.negotiate
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionContext
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.configuration.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.fabric.FabricProtocol
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.*
import com.hiczp.minecraft.protocol.world.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.BlockPosition
import io.ktor.network.selector.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
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
                    minecraftClientNegotiationResult.clientboundLoginFinishedPacket.gameProfile.id
                )
                assertEquals(minecraftOfflineIdentity.id, minecraftServerNegotiationResult.gameProfile.id)
                assertEquals(
                    minecraftClientNegotiationResult.clientboundLoginPacket,
                    minecraftServerNegotiationResult.clientboundLoginPacket
                )
                assertEquals(
                    VanillaConfigurationData.offeredKnownPacks,
                    minecraftClientNegotiationResult.dataPackConfigurationSnapshot.offeredKnownPacks,
                )

                // Both connections are closed. Codec construction needs only the retained results and application facts.
                val defaultBlockState = BlockState(BlockId("minecraft:air"))
                val defaultBiome = BiomeId("minecraft:plains")
                val surfaceBlockState = BlockState(BlockId("minecraft:stone"))
                val serverDimension = minecraftServerNegotiationResult.minecraftDimensionContext
                val clientDimension = minecraftClientNegotiationResult.minecraftDimensionContext
                val chunk = createFlatChunk(
                    ChunkPosition(-2, 3), serverDimension.chunkContext(defaultBlockState, defaultBiome),
                    64, surfaceBlockState,
                )
                val writeMappings = ChunkPacketWriteMappings({ null })
                val requiredData = ChunkPacketRequiredDataProvider.RequirePresent
                val plainEncoder = ChunkPacketEncoder(
                    ChunkPacketEncoderContext(
                        serverDimension.chunkLayout,
                        serverDimension.minecraftDimensionLayout.hasSkyLight,
                        defaultBlockState,
                        defaultBiome,
                        serverDimension.packetCodecContext,
                        writeMappings,
                        requiredData,
                    )
                )
                val shortcutEncoder = minecraftServerNegotiationResult.chunkPacketEncoder(
                    defaultBlockState, defaultBiome, writeMappings, requiredData,
                )
                val readMappings = ChunkPacketReadMappings.dynamic(NbtPropertyReadMappings())
                val missingData = ChunkPacketMissingDataProvider { ChunkPacketMissingData("test:received", 7, false) }
                val plainDecoder = ChunkPacketDecoder(
                    ChunkPacketDecoderContext(
                        clientDimension.chunkContext(defaultBlockState, defaultBiome),
                        clientDimension.packetCodecContext,
                        readMappings,
                        missingData,
                    )
                )
                val shortcutDecoder = minecraftClientNegotiationResult.chunkPacketDecoder(
                    defaultBlockState, defaultBiome, readMappings, missingData,
                )
                val plainPacket = plainEncoder.encode(chunk)
                val shortcutPacket = shortcutEncoder.encode(chunk)
                assertEquals(plainPacket.chunkData.buffer, shortcutPacket.chunkData.buffer)
                assertSame(
                    serverDimension.packetCodecContext,
                    shortcutEncoder.chunkPacketEncoderContext.packetCodecContext
                )
                assertSame(
                    clientDimension.packetCodecContext,
                    shortcutDecoder.chunkPacketDecoderContext.packetCodecContext
                )
                for (decoded in listOf(plainDecoder.decode(shortcutPacket), shortcutDecoder.decode(plainPacket))) {
                    assertEquals(chunk.chunkPosition, decoded.chunkPosition)
                    assertEquals(clientDimension.dimensionId, decoded.chunkContext.dimensionId)
                    assertEquals("test:received", decoded.status)
                    assertEquals(7L, decoded.inhabitedTime)
                    assertEquals(surfaceBlockState, decoded.getBlockState(BlockPosition(-32, 64, 48)))
                    assertEquals(defaultBlockState, decoded.getBlockState(BlockPosition(-32, 65, 48)))
                }
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
            )
            val difficulty = Difficulty.HARD
            val difficultyLocked = true
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
                            difficulty = difficulty,
                            difficultyLocked = difficultyLocked,
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

                assertEquals(minecraftOfflineIdentity.id, clientOutcome.clientboundLoginFinishedPacket.gameProfile.id)
                assertEquals(minecraftOfflineIdentity.id, serverOutcome.gameProfile.id)
                assertEquals(clientOutcome.clientboundLoginPacket, serverOutcome.clientboundLoginPacket)
                assertEquals(
                    PlayerGameMode.CREATIVE,
                    clientOutcome.clientboundLoginPacket.commonPlayerSpawnInfo.gameMode
                )
                assertEquals(1, serverOutcome.minecraftInitialWorld.chunks.size)
                assertEquals(1, serverOutcome.minecraftInitialWorld.entityBatches.single().entities.size)
            }
        }
    }

    private suspend fun serveStatus(
        minecraftServerConnection: MinecraftServerConnection,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
    ) {
        val clientIntentionPacket = assertIs<ClientIntentionPacket>(minecraftServerConnection.incoming.receive())
        assertEquals(ClientIntent.STATUS, clientIntentionPacket.intention)
        assertEquals(ConnectionState.STATUS, minecraftServerConnection.connectionState)
        assertEquals(ServerboundStatusRequestPacket, minecraftServerConnection.incoming.receive())
        minecraftServerConnection.outgoing.send(
            ClientboundStatusResponsePacket(
                DefaultMinecraftServerNegotiationPolicy.createServerStatus(minecraftServerNegotiationOptions),
            ),
        )
        minecraftServerConnection.requestFlush()
        val serverboundPingRequestPacket =
            assertIs<ServerboundPingRequestPacket>(minecraftServerConnection.incoming.receive())
        minecraftServerConnection.outgoing.send(ClientboundPongResponsePacket(serverboundPingRequestPacket.time))
        minecraftServerConnection.outgoing.close()
        minecraftServerConnection.awaitClosed()
    }

    private suspend fun requestStatus(
        minecraftClientConnection: MinecraftClientConnection,
    ) {
        minecraftClientConnection.outgoing.send(
            ClientIntentionPacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                hostName = minecraftClientConnection.serverAddress,
                port = minecraftClientConnection.serverPort,
                intention = ClientIntent.STATUS,
            ),
        )
        minecraftClientConnection.outgoing.send(ServerboundStatusRequestPacket)
        minecraftClientConnection.requestFlush()
        val clientboundStatusResponsePacket =
            assertIs<ClientboundStatusResponsePacket>(minecraftClientConnection.incoming.receive())
        assertEquals(
            MinecraftProtocol.PROTOCOL_VERSION,
            clientboundStatusResponsePacket.status.version?.protocol,
        )
        minecraftClientConnection.outgoing.send(ServerboundPingRequestPacket(STATUS_PING_ID))
        minecraftClientConnection.requestFlush()
        assertEquals(
            ClientboundPongResponsePacket(STATUS_PING_ID),
            minecraftClientConnection.incoming.receive(),
        )
    }

    private suspend fun negotiateServerPlay(
        minecraftServerConnection: MinecraftServerConnection,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        serverNegotiationProfile: ServerNegotiationProfile,
        difficulty: Difficulty,
        difficultyLocked: Boolean,
    ): ServerPlayOutcome {
        serverNegotiationProfile.begin(minecraftServerConnection)
        val clientIntentionPacket = assertIs<ClientIntentionPacket>(minecraftServerConnection.incoming.receive())
        serverNegotiationProfile.acceptHandshake(clientIntentionPacket)
        assertEquals(ClientIntent.LOGIN, clientIntentionPacket.intention)
        assertEquals(MinecraftProtocol.PROTOCOL_VERSION, clientIntentionPacket.protocolVersion)
        val serverboundHelloPacket = assertIs<ServerboundHelloPacket>(minecraftServerConnection.incoming.receive())
        val gameProfile = MinecraftOfflineIdentity(serverboundHelloPacket.name).toGameProfile()

        serverNegotiationProfile.negotiateLogin(minecraftServerConnection)
        minecraftServerNegotiationOptions.compressionThreshold?.let {
            minecraftServerConnection.outgoing.send(
                ClientboundLoginCompressionPacket(it)
            )
        }
        minecraftServerConnection.outgoing.send(
            ClientboundLoginFinishedPacket(
                gameProfile,
                minecraftServerNegotiationOptions.sessionId
            )
        )
        minecraftServerConnection.requestFlush()
        assertEquals(ServerboundLoginAcknowledgedPacket, minecraftServerConnection.incoming.receive())
        minecraftServerConnection.awaitState(ConnectionState.CONFIGURATION)
        minecraftServerConnection.enableKeepAlive()

        val clientInformation = assertIs<ServerboundClientInformationPacket>(
            minecraftServerConnection.incoming.receive(),
        ).information
        serverNegotiationProfile.negotiateConfigurationStart(minecraftServerConnection)
        minecraftServerConnection.outgoing.send(ClientboundUpdateEnabledFeaturesPacket(minecraftServerNegotiationOptions.configurationData.enabledFeatureFlags))
        serverNegotiationProfile.negotiateEarlyConfiguration(minecraftServerConnection)
        minecraftServerConnection.outgoing.send(
            ClientboundSelectKnownPacks(minecraftServerNegotiationOptions.configurationData.offeredKnownPacks),
        )
        minecraftServerConnection.requestFlush()
        val acceptedKnownPacks = assertIs<ServerboundSelectKnownPacks>(
            minecraftServerConnection.incoming.receive(),
        ).knownPacks
        val synchronizedRegistryPackets =
            minecraftServerNegotiationOptions.configurationData.synchronizedRegistryPackets(acceptedKnownPacks)
        synchronizedRegistryPackets.forEach { clientboundRegistryDataPacket ->
            minecraftServerConnection.outgoing.send(
                clientboundRegistryDataPacket
            )
        }
        minecraftServerConnection.outgoing.send(ClientboundUpdateTagsPacket(minecraftServerNegotiationOptions.configurationData.registryTags))
        serverNegotiationProfile.negotiateConfiguration(minecraftServerConnection)

        val clientboundLoginPacket = DefaultMinecraftServerNegotiationPolicy.createClientboundLoginPacket(
            minecraftServerNegotiationOptions,
            gameProfile,
            onlineMode = false,
        )
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = clientboundLoginPacket.commonPlayerSpawnInfo.dimensionTypeId,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            configurationData = minecraftServerNegotiationOptions.configurationData,
        )
        val basePacketCodecContext = minecraftServerNegotiationOptions.configurationData
            .resolveSynchronizedRegistryContext(synchronizedRegistryPackets)
        val packetCodecContext = serverNegotiationProfile.resolvePacketCodecContext(
            basePacketCodecContext,
        )
        val minecraftDimensionContext = MinecraftDimensionContext(
            DimensionId.parse(clientboundLoginPacket.commonPlayerSpawnInfo.dimension.toString()),
            minecraftDimensionLayout,
            packetCodecContext,
        )
        minecraftServerConnection.installPacketCodecContext(
            minecraftDimensionContext.packetCodecContext,
        )
        minecraftServerConnection.outgoing.send(ClientboundFinishConfigurationPacket)
        minecraftServerConnection.requestFlush()
        assertEquals(ServerboundFinishConfigurationPacket, minecraftServerConnection.incoming.receive())
        minecraftServerConnection.disableKeepAlive()
        minecraftServerConnection.awaitState(ConnectionState.PLAY)
        val recordingKeepAlive = minecraftServerConnection.enableRecordingPlayKeepAlive(5.seconds)
        serverNegotiationProfile.preparePlay(minecraftServerConnection)
        minecraftServerConnection.outgoing.send(clientboundLoginPacket)
        assertSame(TestProfileResult, serverNegotiationProfile.complete(minecraftServerConnection))

        val minecraftInitialWorldBootstrap = testWorldBootstrap(
            dimensionId = clientboundLoginPacket.commonPlayerSpawnInfo.dimension,
            difficulty = difficulty,
            difficultyLocked = difficultyLocked,
            gameMode = clientboundLoginPacket.commonPlayerSpawnInfo.gameMode,
            viewDistance = clientboundLoginPacket.chunkRadius,
            simulationDistance = clientboundLoginPacket.simulationDistance,
        )
        val minecraftInitialWorld = testInitialWorld(
            minecraftDimensionContext = minecraftDimensionContext,
            minecraftInitialWorldBootstrap = minecraftInitialWorldBootstrap,
            chunkRadius = 0,
            entityBatches = listOf(
                testEntityBatch(
                    minecraftServerConnection.packetCodecContext, listOf(testPig()), mapOf(Uuid.fromLongs(0, 2) to 2),
                )
            ),
        )
        minecraftServerConnection.synchronizeInitialWorld(minecraftInitialWorld)
        minecraftServerConnection.requestFlush()

        var teleportConfirmed = false
        var chunkBatchConfirmed = false
        while (!(teleportConfirmed && chunkBatchConfirmed)) {
            when (val serverboundPacket = minecraftServerConnection.incoming.receive()) {
                is ServerboundAcceptTeleportationPacket ->
                    teleportConfirmed =
                        serverboundPacket.id == minecraftInitialWorld.minecraftInitialWorldBootstrap.teleportId

                is ServerboundChunkBatchReceivedPacket -> chunkBatchConfirmed = true
                else -> Unit
            }
        }
        recordingKeepAlive.roundTrip.await()
        assertTrue(teleportConfirmed)
        assertTrue(chunkBatchConfirmed)
        assertEquals("en_us", clientInformation.locale)
        assertEquals(
            PROFILE_REGISTRY_SIZE,
            minecraftServerConnection.packetCodecContext.registrySize(PROFILE_REGISTRY),
        )
        assertEquals(minecraftServerConnection.declaredExtensionRoutes, minecraftServerConnection.activeExtensionRoutes)
        return ServerPlayOutcome(
            gameProfile = gameProfile,
            clientboundLoginPacket = clientboundLoginPacket,
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
                ClientIntentionPacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    hostName = minecraftClientConnection.serverAddress,
                    port = minecraftClientConnection.serverPort,
                    intention = ClientIntent.LOGIN,
                ),
            ),
        )
        minecraftClientConnection.outgoing.send(
            ServerboundHelloPacket(
                minecraftOfflineIdentity.name,
                minecraftOfflineIdentity.id
            )
        )
        minecraftClientConnection.requestFlush()
        val firstLoginPacket = minecraftClientConnection.incoming.receive()
        val clientboundLoginFinishedPacket = if (firstLoginPacket is ClientboundLoginCompressionPacket) {
            assertIs<ClientboundLoginFinishedPacket>(minecraftClientConnection.incoming.receive())
        } else {
            assertIs<ClientboundLoginFinishedPacket>(firstLoginPacket)
        }
        minecraftClientConnection.outgoing.send(ServerboundLoginAcknowledgedPacket)
        minecraftClientConnection.awaitState(ConnectionState.CONFIGURATION)

        minecraftClientConnection.outgoing.send(
            ServerboundClientInformationPacket(
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
        val synchronizedRegistryPackets = mutableListOf<ClientboundRegistryDataPacket>()
        var configurationFinished = false
        while (!configurationFinished) {
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is ClientboundUpdateEnabledFeaturesPacket -> assertEquals(
                    ClientboundUpdateEnabledFeaturesPacket(VanillaConfigurationData.enabledFeatureFlags),
                    clientboundPacket,
                )

                is ClientboundSelectKnownPacks -> {
                    minecraftClientConnection.outgoing.send(ServerboundSelectKnownPacks(clientboundPacket.knownPacks))
                    minecraftClientConnection.requestFlush()
                }

                is ClientboundRegistryDataPacket -> synchronizedRegistryPackets += clientboundPacket
                is ClientboundUpdateTagsPacket -> assertEquals(
                    ClientboundUpdateTagsPacket(VanillaConfigurationData.registryTags),
                    clientboundPacket,
                )

                is ClientboundFinishConfigurationPacket -> {
                    val resolvedPacketCodecContext = VanillaConfigurationData.resolveSynchronizedRegistryContext(
                        synchronizedRegistryPackets,
                    )
                    val profilePacketCodecContext =
                        clientNegotiationProfile.resolvePacketCodecContext(resolvedPacketCodecContext)
                    minecraftClientConnection.installPacketCodecContext(profilePacketCodecContext)
                    clientNegotiationProfile.preparePlay(minecraftClientConnection)
                    minecraftClientConnection.outgoing.send(ServerboundFinishConfigurationPacket)
                    minecraftClientConnection.requestFlush()
                    minecraftClientConnection.awaitState(ConnectionState.PLAY)
                    configurationFinished = true
                }

                else -> fail("Unexpected Configuration packet ${clientboundPacket::class.simpleName}")
            }
        }

        val clientboundLoginPacket = assertIs<ClientboundLoginPacket>(minecraftClientConnection.incoming.receive())
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = clientboundLoginPacket.commonPlayerSpawnInfo.dimensionTypeId,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            configurationData = VanillaConfigurationData,
        )
        val minecraftDimensionContext = MinecraftDimensionContext(
            dimensionId = DimensionId.parse(clientboundLoginPacket.commonPlayerSpawnInfo.dimension.toString()),
            minecraftDimensionLayout = minecraftDimensionLayout,
            packetCodecContext = minecraftClientConnection.packetCodecContext,
        )
        minecraftClientConnection.installPacketCodecContext(minecraftDimensionContext.packetCodecContext)
        assertSame(TestProfileResult, clientNegotiationProfile.complete(minecraftClientConnection))

        var chunkReceived = false
        var entityReceived = false
        var difficultyReceived = false
        var playerAbilities: PlayerAbilities? = null
        while (!(chunkReceived && entityReceived && difficultyReceived && playerAbilities != null)) {
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is ClientboundPlayerPositionPacket -> {
                    minecraftClientConnection.outgoing.send(ServerboundAcceptTeleportationPacket(clientboundPacket.id))
                    minecraftClientConnection.requestFlush()
                }

                is ClientboundLevelChunkWithLightPacket -> chunkReceived = true
                is ClientboundChunkBatchFinishedPacket -> {
                    minecraftClientConnection.outgoing.send(ServerboundChunkBatchReceivedPacket(desiredChunksPerTick = 10.0f))
                    minecraftClientConnection.requestFlush()
                }

                is ClientboundBundlePacket -> entityReceived = clientboundPacket.subPackets
                    .filterIsInstance<ClientboundAddEntityPacket>()
                    .any { clientboundAddEntityPacket ->
                        clientboundAddEntityPacket.type == VanillaConfigurationData.completePacketCodecContext.requireRegistryEntry(
                            PacketCodecContext.ENTITY_TYPE_REGISTRY,
                            Identifier("pig")
                        ).rawId
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
            expected = testPlayerAbilities(PlayerGameMode.CREATIVE),
            actual = assertNotNull(playerAbilities),
        )
        assertEquals(
            PROFILE_REGISTRY_SIZE,
            minecraftClientConnection.packetCodecContext.registrySize(PROFILE_REGISTRY),
        )
        assertEquals(minecraftClientConnection.declaredExtensionRoutes, minecraftClientConnection.activeExtensionRoutes)
        return ClientPlayOutcome(
            clientboundLoginFinishedPacket = clientboundLoginFinishedPacket,
            clientboundLoginPacket = clientboundLoginPacket,
        )
    }

    private fun testPig(): Entity = testEntity(2, "pig", EntityVector3d(3.5, 65.0, 3.5))

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
    val clientboundLoginPacket: ClientboundLoginPacket,
    val minecraftInitialWorld: MinecraftInitialWorld,
)

private data class ClientPlayOutcome(
    val clientboundLoginFinishedPacket: ClientboundLoginFinishedPacket,
    val clientboundLoginPacket: ClientboundLoginPacket,
)

private data object TestProfileResult : NegotiationProfileResult

private class TestClientProfile : ClientNegotiationProfile {
    override suspend fun begin(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
    ) {
        minecraftClientPacketConnection.activateExtensionRoutes(minecraftClientPacketConnection.declaredExtensionRoutes)
    }

    override suspend fun resolvePacketCodecContext(
        packetCodecContext: PacketCodecContext,
    ): PacketCodecContext = packetCodecContext.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

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

    override suspend fun resolvePacketCodecContext(
        packetCodecContext: PacketCodecContext,
    ): PacketCodecContext = packetCodecContext.withRegistrySize(PROFILE_REGISTRY, PROFILE_REGISTRY_SIZE)

    override suspend fun complete(
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ): NegotiationProfileResult = TestProfileResult
}

private val PROFILE_REGISTRY: Identifier = Identifier("test:profile")
private const val PROFILE_REGISTRY_SIZE: Int = 7
