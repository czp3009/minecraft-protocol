package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.auth.MinecraftClientKeyExchange
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.respond
import com.hiczp.minecraft.protocol.auth.toServerboundKeyPacket
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.configuration.resolveMinecraftDimensions
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.configuration.vanilla.toVanillaConfigurationData
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
import com.hiczp.minecraft.world.format.datapack.vanilla.VanillaDataPacks
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
class MinecraftServerNegotiationTest {
    @Test
    fun negotiationOptionsDefaultToTheCompleteVanillaServerContract() {
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions()

        assertSame(VanillaConfigurationData, minecraftServerNegotiationOptions.configurationData)
        assertTrue(minecraftServerNegotiationOptions.statusEnabled)
        assertFalse(minecraftServerNegotiationOptions.acceptsTransfers)
        assertEquals(PlayerGameMode.SURVIVAL, minecraftServerNegotiationOptions.gameMode)
        assertEquals(DimensionId.Overworld, minecraftServerNegotiationOptions.initialDimensionId)
    }

    @Test
    fun negotiationOptionsRequireTheInitialDimensionToBeAdvertised() {
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerNegotiationOptions(
                dimensionIds = setOf(DimensionId.Overworld),
                initialDimensionId = DimensionId.parse("example:missing"),
            )
        }
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
        val offlineClientboundLoginPacket = DefaultMinecraftServerNegotiationPolicy.createClientboundLoginPacket(
            minecraftServerNegotiationOptions,
            gameProfile,
            onlineMode = false,
        )
        val onlineClientboundLoginPacket = DefaultMinecraftServerNegotiationPolicy.createClientboundLoginPacket(
            minecraftServerNegotiationOptions,
            gameProfile,
            onlineMode = true,
        )
        assertFalse(offlineClientboundLoginPacket.enforcesSecureChat)
        assertTrue(onlineClientboundLoginPacket.enforcesSecureChat)
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
            connectionPair.client.send(handshake(ClientIntent.STATUS))
            connectionPair.client.send(ServerboundStatusRequestPacket)
            val clientboundStatusResponsePacket =
                assertIs<ClientboundStatusResponsePacket>(connectionPair.client.receive())
            assertEquals(serverStatus.description, clientboundStatusResponsePacket.status.description)
            assertEquals(7, clientboundStatusResponsePacket.status.players?.online)
            connectionPair.client.send(ServerboundPingRequestPacket(42))
            assertEquals(ClientboundPongResponsePacket(42), connectionPair.client.receive())
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
            connectionPair.client.send(handshake(ClientIntent.LOGIN))
            connectionPair.client.send(
                ServerboundHelloPacket(
                    minecraftOfflineIdentity.name,
                    minecraftOfflineIdentity.id
                )
            )
            val clientNegotiationTranscript =
                finishClientNegotiation(connectionPair.client, minecraftServerNegotiationOptions)
            val minecraftServerNegotiationResult = assertNotNull(negotiation.await())

            assertEquals(minecraftOfflineIdentity.id, minecraftServerNegotiationResult.gameProfile.id)
            assertEquals(clientNegotiationTranscript.login, minecraftServerNegotiationResult.gameProfile)
            assertEquals(
                clientNegotiationTranscript.clientboundLoginPacket,
                minecraftServerNegotiationResult.clientboundLoginPacket
            )
            assertEquals(
                PlayerGameMode.CREATIVE,
                minecraftServerNegotiationResult.clientboundLoginPacket.commonPlayerSpawnInfo.gameMode,
            )
            assertSame(
                minecraftServerNegotiationOptions.configurationData.completePacketCodecContext.registries,
                connectionPair.server.packetCodecContext.registries,
            )
            assertSame(
                minecraftServerNegotiationOptions.configurationData.completePacketCodecContext.blockStates,
                connectionPair.server.packetCodecContext.blockStates,
            )
        } finally {
            connectionPair.close()
        }
    }

    @Test
    fun negotiatesAStoredDimensionWhoseIdDiffersFromItsType() = runTest {
        val resolvedConfigurationData = VanillaDataPacks.coreDataPackStack.toVanillaConfigurationData()
        val dimensionId = DimensionId.parse("example:moon")
        val dimensions = resolvedConfigurationData.resolveMinecraftDimensions(
            WorldGenSettingsData(
                seed = 1L,
                generateStructures = true,
                bonusChest = false,
                dimensions = mapOf(
                    dimensionId to WorldGenDimension(
                        type = WorldGenDimensionType.Reference(DimensionTypeId("the_nether")),
                        generator = NbtCompound(emptyMap()),
                    ),
                ),
            ),
        )
        val expectedMinecraftDimensionContext = dimensions.getValue(dimensionId)
        val minecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(
            configurationData = resolvedConfigurationData,
            initialDimensionId = expectedMinecraftDimensionContext.dimensionId,
            initialDimensionTypeId = expectedMinecraftDimensionContext.minecraftDimensionLayout.dimensionTypeId,
            dimensionIds = dimensions.keys,
            compressionThreshold = null,
            viewDistance = 3,
        )
        val expectedMinecraftDimensionLayout = MinecraftDimensionLayout.from(
            resolvedConfigurationData,
            Identifier("the_nether"),
        )
        val clientboundLoginPacket = DefaultMinecraftServerNegotiationPolicy.createClientboundLoginPacket(
            minecraftServerNegotiationOptions,
            gameProfile = GameProfile(Uuid.fromLongs(1, 2), "ResolvedProbe", emptyList()),
            onlineMode = false,
        )
        assertEquals(setOf(Identifier("example:moon")), clientboundLoginPacket.levels)
        assertEquals(Identifier("example:moon"), clientboundLoginPacket.commonPlayerSpawnInfo.dimension)
        assertEquals(
            expectedMinecraftDimensionLayout.dimensionTypeRawId,
            clientboundLoginPacket.commonPlayerSpawnInfo.dimensionTypeId,
        )
        val connectionPair = connectionPair()
        try {
            val negotiation = async {
                connectionPair.server.negotiate(
                    minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
                )
            }
            val minecraftOfflineIdentity = MinecraftOfflineIdentity("ResolvedProbe")
            connectionPair.client.send(handshake(ClientIntent.LOGIN))
            connectionPair.client.send(
                ServerboundHelloPacket(
                    minecraftOfflineIdentity.name,
                    minecraftOfflineIdentity.id
                )
            )
            finishClientNegotiation(connectionPair.client, minecraftServerNegotiationOptions)

            val minecraftServerNegotiationResult = assertNotNull(negotiation.await())
            val minecraftDimensionContext = minecraftServerNegotiationResult.minecraftDimensionContext
            assertEquals(expectedMinecraftDimensionContext.dimensionId, minecraftDimensionContext.dimensionId)
            assertEquals(
                expectedMinecraftDimensionLayout,
                minecraftDimensionContext.minecraftDimensionLayout,
            )
            assertEquals(
                expectedMinecraftDimensionContext.packetCodecContext,
                minecraftDimensionContext.packetCodecContext,
            )
            assertSame(minecraftDimensionContext.packetCodecContext, connectionPair.server.packetCodecContext)
            val minecraftInitialWorldBootstrap = testWorldBootstrap(
                minecraftServerNegotiationResult,
            )
            assertEquals(3, minecraftInitialWorldBootstrap.viewDistance)
            assertEquals(
                Identifier("example:moon"),
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
                    handshakeNextState = ClientIntent.LOGIN,
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
            connectionPair.server.outgoing.send(ClientboundLoginDisconnectPacket(callerReason))
            assertEquals(
                ClientboundLoginDisconnectPacket(callerReason),
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
                    listOf(ClientboundLoginDisconnectPacket(marker)),
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
            connectionPair.client.send(handshake(ClientIntent.LOGIN))
            val topLevel = PacketRoute.TopLevel(
                connectionState = ConnectionState.LOGIN,
                packetDirection = PacketDirection.SERVERBOUND,
                packetId = 0x7E,
            )
            val data = ByteString(byteArrayOf(1, 2, 3))
            connectionPair.client.send(UnknownPacket.Serverbound(topLevel, data))
            assertEquals(ClientboundLoginDisconnectPacket(marker), connectionPair.client.receive())
            assertEquals(topLevel, assertNotNull(observed).packetRoute)
            assertEquals(data, assertNotNull(observed).data)

            connectionPair.client.send(
                ServerboundHelloPacket(
                    minecraftOfflineIdentity.name,
                    minecraftOfflineIdentity.id
                )
            )
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
        val brand = ClientboundCustomPayloadPacket(
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
                    clientboundPackets = listOf(ClientboundPingPacket(91)),
                ) { serverboundPacket -> serverboundPacket == ServerboundPongPacket(91) },
            )
        }
        try {
            val negotiation = async {
                connectionPair.server.negotiate(
                    minecraftServerNegotiationOptions = minecraftServerNegotiationOptions,
                    minecraftServerNegotiationPolicy = minecraftServerNegotiationPolicy,
                )
            }
            connectionPair.client.send(handshake(ClientIntent.LOGIN))
            connectionPair.client.send(
                ServerboundHelloPacket(
                    minecraftOfflineIdentity.name,
                    minecraftOfflineIdentity.id
                )
            )
            finishClientNegotiation(
                connectionPair.client,
                minecraftServerNegotiationOptions
            ) { minecraftClientPacketSession ->
                assertEquals(brand, minecraftClientPacketSession.receive())
                assertEquals(ClientboundPingPacket(91), minecraftClientPacketSession.receive())
                minecraftClientPacketSession.send(ServerboundPongPacket(91))
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
                connectionPair.client.send(handshake(ClientIntent.LOGIN))
                connectionPair.client.send(
                    ServerboundHelloPacket("OnlineProbe", profileId),
                )
                val clientboundHelloPacket = assertIs<ClientboundHelloPacket>(
                    connectionPair.client.receive(),
                )
                val minecraftClientKeyExchangeResult = MinecraftClientKeyExchange.respond(clientboundHelloPacket)
                val secret = minecraftClientKeyExchangeResult.sharedSecret
                try {
                    connectionPair.client.prepareOutboundEncryption(secret)
                    connectionPair.client.send(minecraftClientKeyExchangeResult.toServerboundKeyPacket())
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
                assertTrue(minecraftServerNegotiationResult.clientboundLoginPacket.onlineMode)
                assertTrue(minecraftServerNegotiationResult.clientboundLoginPacket.enforcesSecureChat)
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
            assertEquals(ClientboundLoginCompressionPacket(threshold), minecraftClientPacketSession.receive())
        }
        val login = assertIs<ClientboundLoginFinishedPacket>(minecraftClientPacketSession.receive()).gameProfile
        minecraftClientPacketSession.send(ServerboundLoginAcknowledgedPacket)
        minecraftClientPacketSession.send(ServerboundClientInformationPacket(clientInformation()))
        assertEquals(
            ClientboundUpdateEnabledFeaturesPacket(minecraftServerNegotiationOptions.configurationData.enabledFeatureFlags),
            minecraftClientPacketSession.receive()
        )
        val clientboundSelectKnownPacks = assertIs<ClientboundSelectKnownPacks>(
            minecraftClientPacketSession.receive(),
        )
        minecraftClientPacketSession.send(
            ServerboundSelectKnownPacks(clientboundSelectKnownPacks.knownPacks),
        )
        minecraftServerNegotiationOptions.configurationData.synchronizedRegistryPackets(
            clientboundSelectKnownPacks.knownPacks
        )
            .forEach { expected -> assertEquals(expected, minecraftClientPacketSession.receive()) }
        assertEquals(
            ClientboundUpdateTagsPacket(minecraftServerNegotiationOptions.configurationData.registryTags),
            minecraftClientPacketSession.receive()
        )
        afterVanillaConfiguration(minecraftClientPacketSession)
        assertEquals(ClientboundFinishConfigurationPacket, minecraftClientPacketSession.receive())
        minecraftClientPacketSession.send(ServerboundFinishConfigurationPacket)
        val clientboundLoginPacket = assertIs<ClientboundLoginPacket>(minecraftClientPacketSession.receive())
        return ClientNegotiationTranscript(login, clientboundLoginPacket)
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
        handshakeNextState: ClientIntent,
        protocolVersion: Int = MinecraftProtocol.PROTOCOL_VERSION,
    ): ClientIntentionPacket = ClientIntentionPacket(
        protocolVersion = protocolVersion,
        hostName = "localhost",
        port = MinecraftServerConnection.DEFAULT_PORT,
        intention = handshakeNextState,
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
    val clientboundLoginPacket: ClientboundLoginPacket,
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
