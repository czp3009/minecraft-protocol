package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.auth.MinecraftClientKeyExchange
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.respond
import com.hiczp.minecraft.protocol.auth.toEncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi
import com.hiczp.minecraft.protocol.session.MinecraftClientPacketSession
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.createMinecraftServerPacketConnection
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.Uuid
import com.hiczp.minecraft.protocol.model.type.GameMode as PlayerGameMode

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftServerProtocolTest {
    @Test
    fun buildsStructuredStatusJsonWithoutImposingServerPolicy() {
        val options = MinecraftServerNegotiationOptions(
            statusDescription = "Structured status",
            maximumPlayers = 12,
            enforcesSecureChat = true,
        )
        val offline = Json.parseToJsonElement(
            options.statusJson(onlinePlayers = 3),
        ).jsonObject
        assertEquals(
            MinecraftProtocol.PROTOCOL_VERSION,
            offline.getValue("version").jsonObject
                .getValue("protocol").jsonPrimitive.int,
        )
        assertEquals(
            "Structured status",
            offline.getValue("description").jsonObject
                .getValue("text").jsonPrimitive.content,
        )
        assertFalse(
            offline.getValue("enforcesSecureChat").jsonPrimitive.boolean,
        )

        val sink = Buffer()
        options.statusJsonToSink(
            sink = sink,
            onlinePlayers = 3,
            onlineMode = true,
        )
        val online = Json.parseToJsonElement(sink.readString()).jsonObject
        assertTrue(
            online.getValue("enforcesSecureChat").jsonPrimitive.boolean,
        )
        val unconventionalOptions = MinecraftServerNegotiationOptions(
            compressionThreshold = -1,
            maximumPlayers = -1,
            viewDistance = 1,
            simulationDistance = -1,
        )
        val unconventionalStatus = Json.parseToJsonElement(
            unconventionalOptions.statusJson(onlinePlayers = -1),
        ).jsonObject
        assertEquals(
            -1,
            unconventionalStatus.getValue("players").jsonObject
                .getValue("online").jsonPrimitive.int,
        )

        val profile = GameProfile(Uuid.fromLongs(1, 2), "Probe", emptyList())
        val offlineLogin = options.playLogin(profile, onlineMode = false)
        val onlineLogin = options.playLogin(profile, onlineMode = true)
        assertFalse(offlineLogin.enforcesSecureChat)
        assertTrue(onlineLogin.enforcesSecureChat)
    }

    @Test
    fun servesStatusThroughOnlyThePublicPacketChannels() = runTest {
        val pair = connectionPair()
        val responseJson = buildJsonObject { put("test", "channel-first") }
            .toString()
        val policy = object : MinecraftServerNegotiationPolicy {
            override suspend fun statusJson(
                options: MinecraftServerNegotiationOptions,
                onlineMode: Boolean,
            ): String {
                assertFalse(onlineMode)
                return responseJson
            }
        }
        try {
            val negotiation = async {
                pair.server.negotiate(policy = policy)
            }
            pair.client.send(handshake(HandshakeNextState.STATUS))
            pair.client.send(StatusRequestPacket)
            assertEquals(
                StatusResponsePacket(responseJson),
                pair.client.receive(),
            )
            pair.client.send(StatusPingRequestPacket(42))
            assertEquals(StatusPongResponsePacket(42), pair.client.receive())
            assertNull(negotiation.await())
            assertFalse(pair.server.isOpen)
        } finally {
            pair.close()
        }
    }

    @Test
    fun offlineNegotiationInstallsOneSharedRegistryContextReference() = runTest {
        val options = MinecraftServerNegotiationOptions(
            compressionThreshold = null,
            gameMode = PlayerGameMode.CREATIVE,
        )
        val identity = MinecraftOfflineIdentity("ChannelProbe")
        val pair = connectionPair()
        try {
            val negotiation = async { pair.server.negotiate(options = options) }
            pair.client.send(handshake(HandshakeNextState.LOGIN))
            pair.client.send(LoginStartPacket(identity.name, identity.id))
            val transcript = finishClientNegotiation(pair.client, options)
            val result = assertNotNull(negotiation.await())

            assertEquals(identity.id, result.gameProfile.id)
            assertEquals(transcript.login, result.gameProfile)
            assertEquals(transcript.playLogin, result.playLogin)
            assertEquals(
                PlayerGameMode.CREATIVE,
                result.playLogin.spawnInfo.gameMode,
            )
            assertSame(
                options.protocolData.registryContext.registries,
                pair.server.registries.registries,
            )
            assertSame(
                options.protocolData.registryContext.blockStates,
                pair.server.registries.blockStates,
            )
        } finally {
            pair.close()
        }
    }

    @Test
    fun loginRejectionDoesNotSendAnythingUntilTheCallerChoosesTo() = runTest {
        val pair = connectionPair()
        try {
            val negotiation = async {
                try {
                    pair.server.negotiate()
                    null
                } catch (failure: MinecraftLoginRejectedException) {
                    failure
                }
            }
            pair.client.send(
                handshake(
                    nextState = HandshakeNextState.LOGIN,
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION + 1,
                ),
            )
            val failure = assertNotNull(negotiation.await())
            assertTrue(pair.server.isOpen)
            assertEquals(failure.reason, failure.failurePacket.reason)

            val callerReason = JsonTextComponent(
                buildJsonObject { put("text", "caller-owned reply") }
                    .toString(),
            )
            pair.server.outgoing.send(LoginDisconnectPacket(callerReason))
            assertEquals(
                LoginDisconnectPacket(callerReason),
                pair.client.receive(),
            )
            assertNotEquals(callerReason, failure.reason)
        } finally {
            pair.close()
        }
    }

    @Test
    fun unknownLoginPacketsRemainRawAndPolicyResponsesAreExplicit() = runTest {
        val pair = connectionPair()
        val options = MinecraftServerNegotiationOptions(
            compressionThreshold = null,
        )
        val identity = MinecraftOfflineIdentity("UnknownProbe")
        val marker = JsonTextComponent(
            buildJsonObject { put("text", "policy response") }.toString(),
        )
        var observed: UnknownPacket.Serverbound? = null
        val policy = object : MinecraftServerNegotiationPolicy {
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
                pair.server.negotiate(options = options, policy = policy)
            }
            pair.client.send(handshake(HandshakeNextState.LOGIN))
            val route = PacketRoute.TopLevel(
                state = ConnectionState.LOGIN,
                direction = PacketDirection.SERVERBOUND,
                packetId = 0x7E,
            )
            val data = ByteString(byteArrayOf(1, 2, 3))
            pair.client.send(UnknownPacket.Serverbound(route, data))
            assertEquals(LoginDisconnectPacket(marker), pair.client.receive())
            assertEquals(route, assertNotNull(observed).route)
            assertEquals(data, assertNotNull(observed).data)

            pair.client.send(LoginStartPacket(identity.name, identity.id))
            finishClientNegotiation(pair.client, options)
            assertNotNull(negotiation.await())
        } finally {
            pair.close()
        }
    }

    @Test
    fun configurationPacketsAndTasksUseTheSamePublicChannels() = runTest {
        val pair = connectionPair()
        val options = MinecraftServerNegotiationOptions(
            compressionThreshold = null,
        )
        val identity = MinecraftOfflineIdentity("TaskProbe")
        val brand = ConfigurationClientboundPluginMessagePacket(
            CustomPayload.Brand("task-profile"),
        )
        val policy = object : MinecraftServerNegotiationPolicy {
            override suspend fun configurationPackets(
                gameProfile: GameProfile,
                clientInformation: ClientInformation,
                acceptedKnownPacks: List<KnownPack>,
                transferred: Boolean,
                options: MinecraftServerNegotiationOptions,
            ): List<ClientboundPacket> = listOf(brand)

            override suspend fun configurationTasks(
                gameProfile: GameProfile,
                clientInformation: ClientInformation,
                acceptedKnownPacks: List<KnownPack>,
                transferred: Boolean,
                options: MinecraftServerNegotiationOptions,
            ): List<MinecraftServerNegotiationTask> = listOf(
                MinecraftServerNegotiationTask(
                    packets = listOf(ConfigurationPingPacket(91)),
                ) { packet -> packet == ConfigurationPongPacket(91) },
            )
        }
        try {
            val negotiation = async {
                pair.server.negotiate(options = options, policy = policy)
            }
            pair.client.send(handshake(HandshakeNextState.LOGIN))
            pair.client.send(LoginStartPacket(identity.name, identity.id))
            finishClientNegotiation(pair.client, options) { client ->
                assertEquals(brand, client.receive())
                assertEquals(ConfigurationPingPacket(91), client.receive())
                client.send(ConfigurationPongPacket(91))
            }
            assertNotNull(negotiation.await())
        } finally {
            pair.close()
        }
    }

    @Test
    fun onlineAuthenticationEnablesEncryptionAndUsesTheClientAddress() =
        runTest {
            val profileId = Uuid.fromLongs(0x1020, 0x3040)
            var requestedIp: String? = null
            val sessionHttpClient = HttpClient(
                MockEngine { request ->
                    requestedIp = request.url.parameters["ip"]
                    respond(
                        content = Json.encodeToString(
                            JsonObject.serializer(),
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
            val options = MinecraftServerNegotiationOptions(
                compressionThreshold = null,
                preventProxyConnections = true,
                enforcesSecureChat = true,
            )
            val pair = connectionPair(
                authentication = authentication,
                clientIpAddress = "203.0.113.42",
            )
            try {
                val negotiation = async {
                    pair.server.negotiate(options = options)
                }
                pair.client.send(handshake(HandshakeNextState.LOGIN))
                pair.client.send(
                    LoginStartPacket("OnlineProbe", profileId),
                )
                val request = assertIs<EncryptionRequestPacket>(
                    pair.client.receive(),
                )
                val exchange = MinecraftClientKeyExchange.respond(request)
                val secret = exchange.sharedSecret
                try {
                    pair.client.prepareOutboundEncryption(secret)
                    pair.client.send(exchange.toEncryptionResponsePacket())
                } finally {
                    secret.fill(0)
                }
                val transcript = finishClientNegotiation(
                    pair.client,
                    options,
                )
                val result = assertNotNull(negotiation.await())

                assertEquals(profileId, transcript.login.id)
                assertEquals(profileId, result.gameProfile.id)
                assertTrue(result.playLogin.onlineMode)
                assertTrue(result.playLogin.enforcesSecureChat)
                assertEquals("203.0.113.42", requestedIp)
            } finally {
                pair.close()
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
        client: MinecraftClientPacketSession,
        options: MinecraftServerNegotiationOptions,
        afterVanillaConfiguration: suspend (MinecraftClientPacketSession) -> Unit = {},
    ): ClientNegotiationTranscript {
        options.compressionThreshold?.let { threshold ->
            assertEquals(SetCompressionPacket(threshold), client.receive())
        }
        val login = assertIs<LoginSuccessPacket>(client.receive()).profile
        client.send(LoginAcknowledgedPacket)
        client.send(ConfigurationClientInformationPacket(clientInformation()))
        assertEquals(options.protocolData.featureFlags, client.receive())
        val knownPacks = assertIs<ConfigurationClientboundKnownPacksPacket>(
            client.receive(),
        )
        client.send(
            ConfigurationServerboundKnownPacksPacket(knownPacks.knownPacks),
        )
        options.protocolData.registryPackets(knownPacks.knownPacks)
            .forEach { expected -> assertEquals(expected, client.receive()) }
        assertEquals(options.protocolData.tags, client.receive())
        afterVanillaConfiguration(client)
        assertEquals(FinishConfigurationPacket, client.receive())
        client.send(AcknowledgeFinishConfigurationPacket)
        val playLogin = assertIs<PlayLoginPacket>(client.receive())
        return ClientNegotiationTranscript(login, playLogin)
    }

    private fun connectionPair(
        authentication: MinecraftServerAuthentication =
            MinecraftServerAuthentication.Offline,
        clientIpAddress: String? = "127.0.0.1",
    ): ConnectionPair {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val serverFrames = MinecraftFrameStream(clientToServer, serverToClient)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val connection = MinecraftServerConnection(
            connection = createMinecraftServerPacketConnection(
                frameStream = serverFrames,
                closeTransport = { serverFrames.cancel() },
                definition = MinecraftConnectionDefinition(),
            ),
            authentication = authentication,
            clientIpAddress = clientIpAddress,
        )
        return ConnectionPair(
            server = connection,
            client = MinecraftClientPacketSession(
                frameStream = clientFrames,
            ),
            clientFrames = clientFrames,
        )
    }

    private fun handshake(
        nextState: HandshakeNextState,
        protocolVersion: Int = MinecraftProtocol.PROTOCOL_VERSION,
    ): HandshakePacket = HandshakePacket(
        protocolVersion = protocolVersion,
        serverAddress = "localhost",
        serverPort = MinecraftServerConnection.DEFAULT_PORT,
        nextState = nextState,
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
    val playLogin: PlayLoginPacket,
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
