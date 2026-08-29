package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.auth.MinecraftClientKeyExchange
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.respond
import com.hiczp.minecraft.protocol.auth.toEncryptionResponsePacket
import com.hiczp.minecraft.protocol.datapack.resolveMinecraftWorld
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaDataPacks
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.datapack.vanilla.toVanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi
import com.hiczp.minecraft.protocol.session.MinecraftClientPacketSession
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.createMinecraftServerPacketConnection
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.DimensionTypeId
import com.hiczp.minecraft.world.format.data.WorldGenDimension
import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.Uuid
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftServerProtocolTest {
    @Test
    fun negotiationOptionsDefaultToTheCompleteVanillaServerContract() {
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions()

        assertSame(VanillaProtocolData, minecraftServerNegotiationOptions.protocolData)
        assertTrue(minecraftServerNegotiationOptions.statusEnabled)
        assertFalse(minecraftServerNegotiationOptions.acceptsTransfers)
        assertEquals(PlayerGameMode.SURVIVAL, minecraftServerNegotiationOptions.gameMode)
        assertEquals(DimensionId.Overworld, minecraftServerNegotiationOptions.initialDimensionId)
    }

    @Test
    fun defaultPolicyBuildsStructuredStatusAndSecureChatClaims() {
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
            statusDescription = "Structured status",
            maximumPlayers = 12,
            enforcesSecureChat = true,
        )
        val offline = DefaultMinecraftServerNegotiationPolicy.createServerStatus(
            minecraftServerNegotiationOptions,
            onlinePlayers = 3,
        )
        assertEquals(
            MinecraftProtocol.PROTOCOL_VERSION,
            offline.version?.protocol,
        )
        assertEquals(
            "Structured status",
            Json.parseToJsonElement(offline.description.json).jsonObject
                .getValue("text").jsonPrimitive.content,
        )
        assertFalse(offline.enforcesSecureChat)

        val online = DefaultMinecraftServerNegotiationPolicy.createServerStatus(
            minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
            onlinePlayers = 3,
            onlineMode = true,
        )
        assertTrue(online.enforcesSecureChat)
        val unconventionalOptions = MinecraftServerNegotiationOptions(
            compressionThreshold = -1,
            maximumPlayers = -1,
            viewDistance = 1,
            simulationDistance = -1,
        )
        val unconventionalStatus = DefaultMinecraftServerNegotiationPolicy.createServerStatus(
            unconventionalOptions,
            onlinePlayers = -1,
        )
        assertEquals(
            -1,
            unconventionalStatus.players?.online,
        )

        val gameProfile = GameProfile(Uuid.fromLongs(1, 2), "Probe", emptyList())
        val offlinePlayLoginPacket = DefaultMinecraftServerNegotiationPolicy.createPlayLoginPacket(
            minecraftServerNegotiationOptions,
            gameProfile,
            onlineMode = false,
        )
        val onlinePlayLoginPacket = DefaultMinecraftServerNegotiationPolicy.createPlayLoginPacket(
            minecraftServerNegotiationOptions,
            gameProfile,
            onlineMode = true,
        )
        assertFalse(offlinePlayLoginPacket.enforcesSecureChat)
        assertTrue(onlinePlayLoginPacket.enforcesSecureChat)
    }

    @Test
    fun servesStatusThroughOnlyThePublicPacketChannels() = runTest {
        val connectionPair = connectionPair()
        val serverStatus = ServerStatus(
            description = JsonTextComponent(
                buildJsonObject { put("text", "channel-first") }.toString(),
            ),
        )
        val minecraftServerNegotiationPolicy = object : MinecraftServerNegotiationPolicy {
            override suspend fun onlinePlayerCount(
                minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
            ): Int = 7

            override suspend fun serverStatus(
                minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
                onlineMode: Boolean,
            ): ServerStatus {
                assertFalse(onlineMode)
                return super.serverStatus(minecraftServerNegotiationOptions, onlineMode).copy(
                    description = serverStatus.description,
                )
            }
        }
        try {
            val negotiation = async {
                connectionPair.server.negotiate(minecraftServerNegotiationPolicy = minecraftServerNegotiationPolicy)
            }
            connectionPair.client.send(handshake(HandshakeNextState.STATUS))
            connectionPair.client.send(StatusRequestPacket)
            val statusResponsePacket = assertIs<StatusResponsePacket>(connectionPair.client.receive())
            assertEquals(serverStatus.description, statusResponsePacket.status.description)
            assertEquals(7, statusResponsePacket.status.players?.online)
            connectionPair.client.send(StatusPingRequestPacket(42))
            assertEquals(StatusPongResponsePacket(42), connectionPair.client.receive())
            assertNull(negotiation.await())
            assertFalse(connectionPair.server.isOpen)
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun offlineNegotiationInstallsOneSharedRegistryContextReference() = runTest {
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
            compressionThreshold = null,
            gameMode = PlayerGameMode.CREATIVE,
        )
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("ChannelProbe")
        val connectionPair = connectionPair()
        try {
            val negotiation =
                async { connectionPair.server.negotiate(minecraftServerNegotiationOptions = minecraftServerNegotiationOptions) }
            connectionPair.client.send(handshake(HandshakeNextState.LOGIN))
            connectionPair.client.send(LoginStartPacket(minecraftOfflineIdentity.name, minecraftOfflineIdentity.id))
            val clientNegotiationTranscript =
                finishClientNegotiation(connectionPair.client, minecraftServerNegotiationOptions)
            val minecraftServerNegotiationResult = assertNotNull(negotiation.await())

            assertEquals(minecraftOfflineIdentity.id, minecraftServerNegotiationResult.gameProfile.id)
            assertEquals(clientNegotiationTranscript.login, minecraftServerNegotiationResult.gameProfile)
            assertEquals(clientNegotiationTranscript.playLoginPacket, minecraftServerNegotiationResult.playLoginPacket)
            assertEquals(
                PlayerGameMode.CREATIVE,
                minecraftServerNegotiationResult.playLoginPacket.spawnInfo.gameMode,
            )
            assertSame(
                minecraftServerNegotiationOptions.protocolData.completeProtocolRegistryContext.registries,
                connectionPair.server.protocolRegistryContext.registries,
            )
            assertSame(
                minecraftServerNegotiationOptions.protocolData.completeProtocolRegistryContext.blockStates,
                connectionPair.server.protocolRegistryContext.blockStates,
            )
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun resolvedWorldProtocolDataProducesACompatibleNegotiatedDimensionContext() = runTest {
        val resolvedProtocolData = VanillaDataPacks.coreDataPackStack.toVanillaProtocolData()
        val resolvedMinecraftWorld = resolvedProtocolData.resolveMinecraftWorld(
            WorldGenSettingsData(
                seed = 1L,
                generateStructures = true,
                bonusChest = false,
                dimensions = mapOf(
                    DimensionId.Overworld to WorldGenDimension(
                        type = WorldGenDimensionType.Reference(DimensionTypeId("overworld")),
                        generator = NbtCompound(emptyMap()),
                    ),
                ),
            ),
        )
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
            protocolData = resolvedMinecraftWorld.protocolData,
            initialDimensionId = DimensionId.Overworld,
            dimensionIds = resolvedMinecraftWorld.dimensions.keys,
            compressionThreshold = null,
            viewDistance = 3,
        )
        val expectedMinecraftChunkContext = resolvedMinecraftWorld.dimension(DimensionId.Overworld)
        val playLoginPacket = DefaultMinecraftServerNegotiationPolicy.createPlayLoginPacket(
            minecraftServerNegotiationOptions,
            gameProfile = GameProfile(Uuid.fromLongs(1, 2), "ResolvedProbe", emptyList()),
            onlineMode = false,
        )
        assertEquals(setOf(Identifier("overworld")), playLoginPacket.levels)
        assertEquals(
            expectedMinecraftChunkContext.minecraftDimensionLayout.dimensionTypeRawId,
            playLoginPacket.spawnInfo.dimensionTypeId,
        )
        val minecraftChunkPacketEncoder = expectedMinecraftChunkContext.packetEncoder(
            isAir = { protocolBlockState -> protocolBlockState.block == Identifier("air") },
            hasFluid = { false },
        )
        assertSame(
            expectedMinecraftChunkContext.protocolRegistryContext,
            minecraftChunkPacketEncoder.protocolRegistryContext,
        )
        assertSame(expectedMinecraftChunkContext.chunkCodecContext, minecraftChunkPacketEncoder.chunkCodecContext)
        val connectionPair = connectionPair()
        try {
            val negotiation = async {
                connectionPair.server.negotiate(
                    minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
                )
            }
            val minecraftOfflineIdentity = MinecraftOfflineIdentity("ResolvedProbe")
            connectionPair.client.send(handshake(HandshakeNextState.LOGIN))
            connectionPair.client.send(LoginStartPacket(minecraftOfflineIdentity.name, minecraftOfflineIdentity.id))
            finishClientNegotiation(connectionPair.client, minecraftServerNegotiationOptions)

            val minecraftServerNegotiationResult = assertNotNull(negotiation.await())
            val minecraftDimensionContext = minecraftServerNegotiationResult.minecraftDimensionContext
            assertEquals(expectedMinecraftChunkContext.dimensionId, minecraftDimensionContext.dimensionId)
            assertEquals(
                expectedMinecraftChunkContext.minecraftDimensionLayout,
                minecraftDimensionContext.minecraftDimensionLayout,
            )
            assertEquals(
                expectedMinecraftChunkContext.protocolRegistryContext,
                minecraftDimensionContext.protocolRegistryContext,
            )
            assertSame(minecraftDimensionContext.protocolRegistryContext, connectionPair.server.protocolRegistryContext)
            val minecraftInitialWorldBootstrap = MinecraftInitialWorldBootstrap.vanilla(
                minecraftServerNegotiationResult,
            )
            assertEquals(3, minecraftInitialWorldBootstrap.viewDistance)
            assertEquals(
                Identifier("overworld"),
                minecraftInitialWorldBootstrap.defaultSpawn.globalPosition.dimension,
            )
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun loginRejectionDoesNotSendAnythingUntilTheCallerChoosesTo() = runTest {
        val connectionPair = connectionPair()
        try {
            val negotiation = async {
                try {
                    connectionPair.server.negotiate()
                    null
                } catch (failure: MinecraftLoginRejectedException) {
                    failure
                }
            }
            connectionPair.client.send(
                handshake(
                    handshakeNextState = HandshakeNextState.LOGIN,
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION + 1,
                ),
            )
            val failure = assertNotNull(negotiation.await())
            assertTrue(connectionPair.server.isOpen)
            assertEquals(failure.reason, failure.failurePacket.reason)

            val callerReason = JsonTextComponent(
                buildJsonObject { put("text", "caller-owned reply") }
                    .toString(),
            )
            connectionPair.server.outgoing.send(LoginDisconnectPacket(callerReason))
            assertEquals(
                LoginDisconnectPacket(callerReason),
                connectionPair.client.receive(),
            )
            assertNotEquals(callerReason, failure.reason)
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun unknownLoginPacketsRemainRawAndPolicyResponsesAreExplicit() = runTest {
        val connectionPair = connectionPair()
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
            compressionThreshold = null,
        )
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("UnknownProbe")
        val marker = JsonTextComponent(
            buildJsonObject { put("text", "policy response") }.toString(),
        )
        var observed: UnknownPacket.Serverbound? = null
        val minecraftServerNegotiationPolicy = object : MinecraftServerNegotiationPolicy {
            override suspend fun onUnhandledQuery(
                packet: UnknownPacket.Serverbound,
            ): ServerNegotiationQueryResult {
                observed = packet
                return ServerNegotiationQueryResult.Respond(
                    listOf(LoginDisconnectPacket(marker)),
                )
            }
        }
        try {
            val negotiation = async {
                connectionPair.server.negotiate(
                    minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
                    minecraftServerNegotiationPolicy = minecraftServerNegotiationPolicy,
                )
            }
            connectionPair.client.send(handshake(HandshakeNextState.LOGIN))
            val topLevel = PacketRoute.TopLevel(
                connectionState = ConnectionState.LOGIN,
                packetDirection = PacketDirection.SERVERBOUND,
                packetId = 0x7E,
            )
            val data = ByteString(byteArrayOf(1, 2, 3))
            connectionPair.client.send(UnknownPacket.Serverbound(topLevel, data))
            assertEquals(LoginDisconnectPacket(marker), connectionPair.client.receive())
            assertEquals(topLevel, assertNotNull(observed).packetRoute)
            assertEquals(data, assertNotNull(observed).data)

            connectionPair.client.send(LoginStartPacket(minecraftOfflineIdentity.name, minecraftOfflineIdentity.id))
            finishClientNegotiation(connectionPair.client, minecraftServerNegotiationOptions)
            assertNotNull(negotiation.await())
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun configurationPacketsAndTasksUseTheSamePublicChannels() = runTest {
        val connectionPair = connectionPair()
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
            compressionThreshold = null,
        )
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("TaskProbe")
        val brand = ConfigurationClientboundPluginMessagePacket(
            CustomPayload.Brand("task-profile"),
        )
        val minecraftServerNegotiationPolicy = object : MinecraftServerNegotiationPolicy {
            override suspend fun configurationPackets(
                gameProfile: GameProfile,
                clientInformation: ClientInformation,
                acceptedKnownPacks: List<KnownPack>,
                transferred: Boolean,
                minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
            ): List<ClientboundPacket> = listOf(brand)

            override suspend fun configurationTasks(
                gameProfile: GameProfile,
                clientInformation: ClientInformation,
                acceptedKnownPacks: List<KnownPack>,
                transferred: Boolean,
                minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
            ): List<MinecraftServerNegotiationTask> = listOf(
                MinecraftServerNegotiationTask(
                    clientboundPackets = listOf(ConfigurationPingPacket(91)),
                ) { serverboundPacket -> serverboundPacket == ConfigurationPongPacket(91) },
            )
        }
        try {
            val negotiation = async {
                connectionPair.server.negotiate(
                    minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
                    minecraftServerNegotiationPolicy = minecraftServerNegotiationPolicy,
                )
            }
            connectionPair.client.send(handshake(HandshakeNextState.LOGIN))
            connectionPair.client.send(LoginStartPacket(minecraftOfflineIdentity.name, minecraftOfflineIdentity.id))
            finishClientNegotiation(
                connectionPair.client,
                minecraftServerNegotiationOptions
            ) { minecraftClientPacketSession ->
                assertEquals(brand, minecraftClientPacketSession.receive())
                assertEquals(ConfigurationPingPacket(91), minecraftClientPacketSession.receive())
                minecraftClientPacketSession.send(ConfigurationPongPacket(91))
            }
            assertNotNull(negotiation.await())
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun onlineAuthenticationEnablesEncryptionAndUsesTheClientAddress() =
        runTest {
            val profileId = Uuid.fromLongs(0x1020, 0x3040)
            var requestedIp: String? = null
            val sessionHttpClient = HttpClient(
                MockEngine { httpRequestData ->
                    requestedIp = httpRequestData.url.parameters["ip"]
                    respond(
                        content = Json.encodeToString(
                            buildJsonObject {
                                put("id", profileId.toHexString())
                                put("properties", buildJsonArray {})
                            },
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString(),
                        ),
                    )
                },
            ) {
                followRedirects = false
            }
            val authentication = MinecraftServerAuthentication.online(
                sessionHttpClient,
            )
            val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
                compressionThreshold = null,
                preventProxyConnections = true,
                enforcesSecureChat = true,
            )
            val connectionPair = connectionPair(
                minecraftServerAuthentication = authentication,
                clientIpAddress = "203.0.113.42",
            )
            try {
                val negotiation = async {
                    connectionPair.server.negotiate(minecraftServerNegotiationOptions = minecraftServerNegotiationOptions)
                }
                connectionPair.client.send(handshake(HandshakeNextState.LOGIN))
                connectionPair.client.send(
                    LoginStartPacket("OnlineProbe", profileId),
                )
                val encryptionRequestPacket = assertIs<EncryptionRequestPacket>(
                    connectionPair.client.receive(),
                )
                val minecraftClientKeyExchangeResult = MinecraftClientKeyExchange.respond(encryptionRequestPacket)
                val secret = minecraftClientKeyExchangeResult.sharedSecret
                try {
                    connectionPair.client.prepareOutboundEncryption(secret)
                    connectionPair.client.send(minecraftClientKeyExchangeResult.toEncryptionResponsePacket())
                } finally {
                    secret.fill(0)
                }
                val clientNegotiationTranscript = finishClientNegotiation(
                    connectionPair.client,
                    minecraftServerNegotiationOptions,
                )
                val minecraftServerNegotiationResult = assertNotNull(negotiation.await())

                assertEquals(profileId, clientNegotiationTranscript.login.id)
                assertEquals(profileId, minecraftServerNegotiationResult.gameProfile.id)
                assertTrue(minecraftServerNegotiationResult.playLoginPacket.onlineMode)
                assertTrue(minecraftServerNegotiationResult.playLoginPacket.enforcesSecureChat)
                assertEquals("203.0.113.42", requestedIp)
            } finally {
                connectionPair.close()
                sessionHttpClient.close()
            }
        }

    @Test
    fun formatsNumericClientAddressesWithoutDnsNames() {
        assertEquals(
            "192.0.2.9",
            byteArrayOf(192.toByte(), 0, 2, 9).toNumericIpAddress(),
        )
        assertEquals(
            "2001:db8:0:0:0:0:0:1",
            byteArrayOf(
                0x20,
                0x01,
                0x0d,
                0xb8.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                1,
            ).toNumericIpAddress(),
        )
        assertFailsWith<IllegalArgumentException> {
            byteArrayOf(1, 2).toNumericIpAddress()
        }
    }

    private suspend fun finishClientNegotiation(
        minecraftClientPacketSession: MinecraftClientPacketSession,
        minecraftServerNegotiationOptions: MinecraftServerNegotiationOptions,
        afterVanillaConfiguration: suspend (MinecraftClientPacketSession) -> Unit = {},
    ): ClientNegotiationTranscript {
        minecraftServerNegotiationOptions.compressionThreshold?.let { threshold ->
            assertEquals(SetCompressionPacket(threshold), minecraftClientPacketSession.receive())
        }
        val login = assertIs<LoginSuccessPacket>(minecraftClientPacketSession.receive()).profile
        minecraftClientPacketSession.send(LoginAcknowledgedPacket)
        minecraftClientPacketSession.send(ConfigurationClientInformationPacket(clientInformation()))
        assertEquals(
            FeatureFlagsPacket(minecraftServerNegotiationOptions.protocolData.enabledFeatureFlags),
            minecraftClientPacketSession.receive()
        )
        val configurationClientboundKnownPacksPacket = assertIs<ConfigurationClientboundKnownPacksPacket>(
            minecraftClientPacketSession.receive(),
        )
        minecraftClientPacketSession.send(
            ConfigurationServerboundKnownPacksPacket(configurationClientboundKnownPacksPacket.knownPacks),
        )
        minecraftServerNegotiationOptions.protocolData.synchronizedRegistryPackets(
            configurationClientboundKnownPacksPacket.knownPacks
        )
            .forEach { expected -> assertEquals(expected, minecraftClientPacketSession.receive()) }
        assertEquals(
            ConfigurationUpdateTagsPacket(minecraftServerNegotiationOptions.protocolData.registryTags),
            minecraftClientPacketSession.receive()
        )
        afterVanillaConfiguration(minecraftClientPacketSession)
        assertEquals(FinishConfigurationPacket, minecraftClientPacketSession.receive())
        minecraftClientPacketSession.send(AcknowledgeFinishConfigurationPacket)
        val playLoginPacket = assertIs<PlayLoginPacket>(minecraftClientPacketSession.receive())
        return ClientNegotiationTranscript(login, playLoginPacket)
    }

    private fun connectionPair(
        minecraftServerAuthentication: MinecraftServerAuthentication =
            MinecraftServerAuthentication.Offline,
        clientIpAddress: String? = "127.0.0.1",
    ): ConnectionPair {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val serverFrames = MinecraftFrameStream(clientToServer, serverToClient)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val minecraftServerConnection = MinecraftServerConnection(
            minecraftServerPacketConnection = createMinecraftServerPacketConnection(
                minecraftFrameStream = serverFrames,
                closeTransport = { serverFrames.cancel() },
                minecraftConnectionDefinition = MinecraftConnectionDefinition(),
            ),
            minecraftServerAuthentication = minecraftServerAuthentication,
            clientIpAddress = clientIpAddress,
        )
        return ConnectionPair(
            server = minecraftServerConnection,
            client = MinecraftClientPacketSession(
                minecraftFrameStream = clientFrames,
            ),
            clientFrames = clientFrames,
        )
    }

    private fun handshake(
        handshakeNextState: HandshakeNextState,
        protocolVersion: Int = MinecraftProtocol.PROTOCOL_VERSION,
    ): HandshakePacket = HandshakePacket(
        protocolVersion = protocolVersion,
        serverAddress = "localhost",
        serverPort = MinecraftServerConnection.DEFAULT_PORT,
        nextState = handshakeNextState,
    )

    private fun clientInformation(): ClientInformation = ClientInformation(
        locale = "en_us",
        viewDistance = 8,
        chatMode = ChatMode.ENABLED,
        chatColors = true,
        displayedSkinParts = 0x7F,
        mainHand = MainHand.RIGHT,
        enableTextFiltering = false,
        allowServerListings = true,
        particleStatus = ParticleStatus.ALL,
    )
}

private data class ClientNegotiationTranscript(
    val login: GameProfile,
    val playLoginPacket: PlayLoginPacket,
)

private data class ConnectionPair(
    val server: MinecraftServerConnection,
    val client: MinecraftClientPacketSession,
    val clientFrames: MinecraftFrameStream,
) {
    fun close() {
        server.close()
        clientFrames.cancel()
    }
}
