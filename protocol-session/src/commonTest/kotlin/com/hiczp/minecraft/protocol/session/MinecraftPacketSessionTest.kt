package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.serialization.*
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.EOFException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.uuid.Uuid

class MinecraftPacketSessionTest {
    @Test
    fun performsStatusHandshakeAndTypedDispatch() = runTest {
        val (client, server) = sessionPair()
        val handshakePacket = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )

        client.send(handshakePacket)
        assertEquals(handshakePacket, server.receive())
        assertEquals(ConnectionState.STATUS, client.connectionState)
        assertEquals(ConnectionState.STATUS, server.connectionState)

        client.send(StatusRequestPacket)
        assertEquals(StatusRequestPacket, server.receive())
        val statusResponsePacket = StatusResponsePacket(
            ServerStatus(
                version = ServerStatus.Version(
                    name = MinecraftProtocol.MINECRAFT_VERSION,
                    protocol = MinecraftProtocol.PROTOCOL_VERSION,
                ),
            ),
        )
        server.send(statusResponsePacket)
        assertEquals(statusResponsePacket, client.receive())
    }

    @Test
    fun activatesCompressionAfterSetCompressionCrossesTheWire() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)
        val threshold = 32

        server.send(SetCompressionPacket(threshold))
        assertEquals(threshold, server.minecraftFrameStream.minecraftFrameCodec.compressionThreshold)
        assertEquals(SetCompressionPacket(threshold), client.receive())
        assertEquals(threshold, client.minecraftFrameStream.minecraftFrameCodec.compressionThreshold)

        val loginSuccessPacket = LoginSuccessPacket(
            GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
            sessionId = Uuid.fromLongs(3, 4),
        )
        server.send(loginSuccessPacket)
        assertEquals(loginSuccessPacket, client.receive())
        client.send(LoginAcknowledgedPacket)
        assertEquals(LoginAcknowledgedPacket, server.receive())
        assertEquals(ConnectionState.CONFIGURATION, client.connectionState)
        assertEquals(ConnectionState.CONFIGURATION, server.connectionState)
    }

    @Test
    fun negativeSetCompressionDisablesAnAlreadyActiveCodec() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)

        server.send(SetCompressionPacket(16))
        assertEquals(SetCompressionPacket(16), client.receive())
        assertEquals(16, server.minecraftFrameStream.minecraftFrameCodec.compressionThreshold)
        assertEquals(16, client.minecraftFrameStream.minecraftFrameCodec.compressionThreshold)

        server.send(SetCompressionPacket(-1))
        assertEquals(SetCompressionPacket(-1), client.receive())
        assertNull(server.minecraftFrameStream.minecraftFrameCodec.compressionThreshold)
        assertNull(client.minecraftFrameStream.minecraftFrameCodec.compressionThreshold)
    }

    @Test
    fun entersPlayOnlyAfterTheConfigurationAcknowledgement() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)
        server.send(
            LoginSuccessPacket(
                GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid.fromLongs(3, 4),
            ),
        )
        client.receive()
        client.send(LoginAcknowledgedPacket)
        server.receive()

        server.send(FinishConfigurationPacket)
        assertEquals(FinishConfigurationPacket, client.receive())
        assertEquals(ConnectionState.CONFIGURATION, server.connectionState)
        assertEquals(ConnectionState.CONFIGURATION, client.connectionState)
        client.send(AcknowledgeFinishConfigurationPacket)
        assertEquals(AcknowledgeFinishConfigurationPacket, server.receive())
        assertEquals(ConnectionState.PLAY, server.connectionState)
        assertEquals(ConnectionState.PLAY, client.connectionState)
    }

    @Test
    fun rejectsPacketsInTheWrongState() = runTest {
        val (client, server) = sessionPair()

        assertFailsWith<MinecraftSessionException> {
            client.send(StatusRequestPacket)
        }

        client.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = "localhost",
                serverPort = 25_565,
                nextState = HandshakeNextState.STATUS,
            ),
        )
        server.receive()

        assertFailsWith<MinecraftSessionException> {
            client.send(LoginAcknowledgedPacket)
        }
        assertFailsWith<MinecraftSessionException> {
            server.send(FinishConfigurationPacket)
        }
    }

    @Test
    fun appliesStateTransitionsOnlyAfterACompleteWireWrite() = runTest {
        val (client, _) = sessionPair()
        client.minecraftFrameStream.output.cancel(
            CancellationException("Closed before the test write"),
        )

        assertFails {
            client.send(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = "localhost",
                    serverPort = 25_565,
                    nextState = HandshakeNextState.STATUS,
                ),
            )
        }
        assertEquals(ConnectionState.HANDSHAKE, client.connectionState)
    }

    @Test
    fun preservesTheLegacyUnframedPing() = runTest {
        val (client, server) = sessionPair()

        client.send(LegacyServerListPingPacket())

        assertEquals(LegacyServerListPingPacket(), server.receive())
    }

    @Test
    fun transferHandshakeUsesTheLoginState() = runTest {
        val (client, server) = sessionPair()
        val handshakePacket = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.TRANSFER,
        )

        client.send(handshakePacket)

        assertEquals(handshakePacket, server.receive())
        assertEquals(ConnectionState.LOGIN, client.connectionState)
        assertEquals(ConnectionState.LOGIN, server.connectionState)
    }

    @Test
    fun playConfigurationAcknowledgementReturnsBothPeersToConfiguration() =
        runTest {
            val (client, server) = sessionPair()
            enterPlay(client, server)

            client.send(AcknowledgeConfigurationPacket)

            assertEquals(ConnectionState.CONFIGURATION, client.connectionState)
            assertEquals(AcknowledgeConfigurationPacket, server.receive())
            assertEquals(ConnectionState.CONFIGURATION, server.connectionState)
        }

    @Test
    fun preservesUnknownPacketIdsButRejectsMalformedPacketIds() = runTest {
        val (unknownClient, unknownServer) = sessionPair()
        unknownClient.minecraftFrameStream.sendPacketData(byteArrayOf(0x7F, 1, 2, 3))
        assertEquals(
            UnknownPacket.Serverbound(
                packetRoute = PacketRoute.TopLevel(
                    ConnectionState.HANDSHAKE,
                    PacketDirection.SERVERBOUND,
                    0x7F,
                ),
                data = ByteString(byteArrayOf(1, 2, 3)),
            ),
            unknownServer.receive(),
        )

        suspend fun reject(packetData: ByteArray) {
            val (client, server) = sessionPair()
            client.minecraftFrameStream.sendPacketData(packetData)
            assertFailsWith<MinecraftSessionException> {
                server.receive()
            }
        }

        reject(byteArrayOf(0x80.toByte()))
        reject(ByteArray(5) { 0x80.toByte() })
    }

    @Test
    fun rejectsInvalidDecodedTransitionsWithoutChangingSessionState() =
        runTest {
            val (client, server) = sessionPair()
            val malformedHandshake = encodeVarInt(0) +
                    encodeVarInt(MinecraftProtocol.PROTOCOL_VERSION) +
                    byteArrayOf(1, 'x'.code.toByte(), 0x63, 0xDD.toByte(), 0)
            client.minecraftFrameStream.sendPacketData(malformedHandshake)

            assertFailsWith<MinecraftSessionException> {
                server.receive()
            }

            assertEquals(ConnectionState.HANDSHAKE, server.connectionState)
        }

    @Test
    fun liftsActiveCustomPayloadsAndPreservesInactiveOnes() = runTest {
        val channel = Identifier("test:number")
        val customPayload = PacketRouteKey.CustomPayload(
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            channel,
        )
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            listOf(
                PacketCodecRegistration.clientboundCustomPayload(
                    ConnectionState.CONFIGURATION,
                    channel,
                    SessionNumberPayload::class,
                    SessionNumberPayloadCodec,
                ),
            ),
        )
        val (client, server) = sessionPair(packetRegistry)
        enterConfiguration(client, server)

        server.send(
            ConfigurationClientboundPluginMessagePacket(
                CustomPayload.Unknown(
                    channel,
                    ByteString(byteArrayOf(7)),
                ),
            ),
        )
        val unknown = assertIs<UnknownPacket.Clientbound>(client.receive())
        assertEquals(customPayload, unknown.packetRoute.packetRouteKey)
        assertEquals(ByteString(byteArrayOf(7)), unknown.data)

        client.activateExtensionRoutes(setOf(customPayload))
        server.activateExtensionRoutes(setOf(customPayload))
        val routedCustomPayload = server.encodeCustomPayload(SessionNumberPayload(300))
        assertEquals(
            SessionNumberPayload(300),
            client.decodeCustomPayload(
                routedCustomPayload.copy(
                    route = routedCustomPayload.route.copy(packetId = routedCustomPayload.route.packetId + 1),
                ),
            ),
        )
        server.send(SessionNumberPayload(300))
        assertEquals(SessionNumberPayload(300), client.receive())
    }

    @Test
    fun activeExtensionCodecPropagatesMalformedPayload() = runTest {
        val channel = Identifier("test:number")
        val customPayload = PacketRouteKey.CustomPayload(
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            channel,
        )
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            listOf(
                PacketCodecRegistration.clientboundCustomPayload(
                    ConnectionState.CONFIGURATION,
                    channel,
                    SessionNumberPayload::class,
                    SessionNumberPayloadCodec,
                ),
            ),
        )
        val (client, server) = sessionPair(packetRegistry)
        enterConfiguration(client, server)
        client.activateExtensionRoutes(setOf(customPayload))

        server.send(
            ConfigurationClientboundPluginMessagePacket(
                CustomPayload.Unknown(
                    channel,
                    ByteString(byteArrayOf(0x80.toByte())),
                ),
            ),
        )

        assertFailsWith<MinecraftSerializationException> {
            client.receive()
        }
    }

    @Test
    fun correlatesTypedLoginQueriesWithoutHidingTheirTransactionIds() = runTest {
        val channel = Identifier("test:login_query")
        val requestRoute = PacketRouteKey.LoginQuery(
            PacketDirection.CLIENTBOUND,
            channel,
        )
        val responseRoute = PacketRouteKey.LoginQuery(
            PacketDirection.SERVERBOUND,
            channel,
        )
        val packetRegistry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            listOf(
                PacketCodecRegistration.clientboundLoginQuery(
                    channel,
                    SessionQueryRequest::class,
                    sessionQueryRequestCodec,
                ) { packet ->
                    LoginQueryRouteValues(packet.transactionId)
                },
                PacketCodecRegistration.serverboundLoginQuery(
                    channel,
                    SessionQueryResponse::class,
                    sessionQueryResponseCodec,
                ) { packet ->
                    LoginQueryRouteValues(packet.transactionId)
                },
            ),
        )
        val (client, server) = sessionPair(packetRegistry)
        loginHandshake(client, server)
        client.activateExtensionRoutes(setOf(requestRoute, responseRoute))
        server.activateExtensionRoutes(setOf(requestRoute, responseRoute))

        server.send(SessionQueryRequest(transactionId = 17, value = 3))
        assertEquals(
            SessionQueryRequest(transactionId = 17, value = 3),
            client.receive(),
        )

        client.send(SessionQueryResponse(transactionId = 17, value = 9))
        assertEquals(
            SessionQueryResponse(transactionId = 17, value = 9),
            server.receive(),
        )
    }

    @Test
    fun preservesAndCorrelatesUnknownLoginQueries() = runTest {
        val channel = Identifier("unknown:login_query")
        val data = ByteString(byteArrayOf(4, 5, 6))
        val (client, server) = sessionPair()
        loginHandshake(client, server)

        server.send(LoginPluginRequestPacket(23, channel, data))
        assertEquals(
            UnknownPacket.Clientbound(
                PacketRoute.LoginQuery(
                    PacketDirection.CLIENTBOUND,
                    transactionId = 23,
                    channel = channel,
                ),
                data,
            ),
            client.receive(),
        )

        val response = UnknownPacket.Serverbound(
            PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                transactionId = 23,
                channel = channel,
            ),
            data,
        )
        client.send(response)
        assertEquals(response, server.receive())
    }

    @Test
    fun preservesLoginResponsesWithoutAnObservedRequest() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)
        val loginPluginResponsePacket = LoginPluginResponsePacket(
            messageId = 31,
            data = ByteString(byteArrayOf(1, 2, 3)),
        )

        client.send(loginPluginResponsePacket)

        assertEquals(loginPluginResponsePacket, server.receive())
    }

    @Test
    fun failedTransitionEncodingLeavesBothDirectionsInThePreviousState() = runTest {
        val (client, server) = sessionPair()
        val invalid = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "x".repeat(256),
            serverPort = 25_565,
            nextState = HandshakeNextState.LOGIN,
        )

        assertFailsWith<MinecraftSerializationException> { client.send(invalid) }
        assertEquals(ConnectionState.HANDSHAKE, client.connectionState)
        assertEquals(ConnectionState.HANDSHAKE, client.inboundState)

        val valid = invalid.copy(serverAddress = "localhost")
        client.send(valid)
        assertEquals(valid, server.receive())
    }

    @Test
    fun clientboundBundlesAreAssembledAndExpandedByPacketSessions() = runTest {
        val (client, server) = sessionPair()
        enterPlay(client, server)
        val subPackets = listOf<ClientboundPacket>(
            ChunkBatchStartPacket,
            ChunkBatchFinishedPacket(2),
        )

        server.send(ClientboundBundlePacket(subPackets))
        assertEquals(subPackets, assertIs<ClientboundBundlePacket>(client.receive()).subPackets)

        server.send(ClientboundBundlePacket(emptyList()))
        assertTrue(assertIs<ClientboundBundlePacket>(client.receive()).isEmpty)
    }

    @Test
    fun clientboundBundleCodecEnforcesSizeNestingAndDelimiterOwnership() = runTest {
        var packetIndex = 0
        val maximumBundle = ClientboundBundleCodec.receive {
            when (packetIndex++) {
                0,
                ClientboundBundlePacket.MAX_SUB_PACKET_COUNT + 1,
                    -> BundleDelimiterPacket

                else -> ChunkBatchStartPacket
            }
        }
        assertEquals(
            ClientboundBundlePacket.MAX_SUB_PACKET_COUNT,
            assertIs<ClientboundBundlePacket>(maximumBundle).size,
        )

        assertFailsWith<MinecraftSessionException> {
            ClientboundBundleCodec.send(
                ClientboundBundlePacket(
                    List(ClientboundBundlePacket.MAX_SUB_PACKET_COUNT + 1) { ChunkBatchStartPacket },
                ),
            ) {}
        }

        packetIndex = 0
        assertFailsWith<MinecraftSessionException> {
            ClientboundBundleCodec.receive {
                when (packetIndex++) {
                    0 -> BundleDelimiterPacket
                    else -> ChunkBatchStartPacket
                }
            }
        }

        var nestedPacketIndex = 0
        assertFailsWith<MinecraftSessionException> {
            ClientboundBundleCodec.receive {
                when (nestedPacketIndex++) {
                    0 -> BundleDelimiterPacket
                    else -> ClientboundBundlePacket(emptyList())
                }
            }
        }

        val (client, server) = sessionPair()
        enterPlay(client, server)
        assertFailsWith<MinecraftSessionException> {
            server.send(BundleDelimiterPacket)
        }
    }

    @Test
    fun unclosedClientboundBundleFailsTheReceivingSession() = runTest {
        val (client, server) = sessionPair()
        enterPlay(client, server)
        server.minecraftFrameStream.sendPacketData(byteArrayOf(0))
        server.minecraftFrameStream.output.flushAndClose()

        assertFails {
            client.receive()
        }
    }

    @Test
    fun skippableClientboundBundleMembersDoNotStopLaterPacketWrites() = runTest {
        val (client, server) = sessionPair()
        enterPlay(client, server)
        val oversizedText = TextComponent.literal("x".repeat(65_536))

        server.send(
            ClientboundBundlePacket(
                listOf(
                    SystemChatMessagePacket(oversizedText, overlay = false),
                    ChunkBatchStartPacket,
                ),
            ),
        )
        assertEquals(
            listOf(ChunkBatchStartPacket),
            assertIs<ClientboundBundlePacket>(client.receive()).subPackets,
        )

        server.send(ChunkBatchFinishedPacket(1))
        assertEquals(ChunkBatchFinishedPacket(1), client.receive())
    }

    private suspend fun loginHandshake(
        minecraftClientPacketSession: MinecraftClientPacketSession,
        minecraftServerPacketSession: MinecraftServerPacketSession,
    ) {
        minecraftClientPacketSession.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = "localhost",
                serverPort = 25_565,
                nextState = HandshakeNextState.LOGIN,
            ),
        )
        minecraftServerPacketSession.receive()
        minecraftClientPacketSession.send(LoginStartPacket("SessionProbe", Uuid.fromLongs(1, 2)))
        minecraftServerPacketSession.receive()
    }

    private suspend fun enterPlay(
        minecraftClientPacketSession: MinecraftClientPacketSession,
        minecraftServerPacketSession: MinecraftServerPacketSession,
    ) {
        loginHandshake(minecraftClientPacketSession, minecraftServerPacketSession)
        minecraftServerPacketSession.send(
            LoginSuccessPacket(
                GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid.fromLongs(3, 4),
            ),
        )
        minecraftClientPacketSession.receive()
        minecraftClientPacketSession.send(LoginAcknowledgedPacket)
        minecraftServerPacketSession.receive()
        minecraftServerPacketSession.send(FinishConfigurationPacket)
        minecraftClientPacketSession.receive()
        minecraftClientPacketSession.send(AcknowledgeFinishConfigurationPacket)
        minecraftServerPacketSession.receive()
        assertEquals(ConnectionState.PLAY, minecraftClientPacketSession.connectionState)
        assertEquals(ConnectionState.PLAY, minecraftServerPacketSession.connectionState)
    }

    private suspend fun enterConfiguration(
        minecraftClientPacketSession: MinecraftClientPacketSession,
        minecraftServerPacketSession: MinecraftServerPacketSession,
    ) {
        loginHandshake(minecraftClientPacketSession, minecraftServerPacketSession)
        minecraftServerPacketSession.send(
            LoginSuccessPacket(
                GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid.fromLongs(3, 4),
            ),
        )
        minecraftClientPacketSession.receive()
        minecraftClientPacketSession.send(LoginAcknowledgedPacket)
        minecraftServerPacketSession.receive()
        assertEquals(ConnectionState.CONFIGURATION, minecraftClientPacketSession.connectionState)
        assertEquals(ConnectionState.CONFIGURATION, minecraftServerPacketSession.connectionState)
    }

    private fun sessionPair(
        packetRegistry: PacketRegistry = MinecraftPacketRegistry,
    ): Pair<MinecraftClientPacketSession, MinecraftServerPacketSession> {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        return MinecraftClientPacketSession(
            minecraftFrameStream = MinecraftFrameStream(serverToClient, clientToServer),
            packetRegistry = packetRegistry,
        ) to MinecraftServerPacketSession(
            minecraftFrameStream = MinecraftFrameStream(clientToServer, serverToClient),
            packetRegistry = packetRegistry,
        )
    }

    private fun encodeVarInt(value: Int): ByteArray {
        var remaining = value
        val byteArray = ByteArray(5)
        var size = 0
        do {
            var current = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) current = current or 0x80
            byteArray[size++] = current.toByte()
        } while (remaining != 0)
        return byteArray.copyOf(size)
    }
}

private data class SessionNumberPayload(
    val value: Int,
) : ClientboundPacket.Extension

private data object SessionNumberPayloadCodec :
    PacketBodyCodec<SessionNumberPayload> {
    override fun encode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packet: SessionNumberPayload,
        sink: Sink,
    ) {
        var remaining = packet.value
        do {
            var current = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) current = current or 0x80
            sink.writeByte(current.toByte())
        } while (remaining != 0)
    }

    override fun decode(
        minecraftProtocolFormat: MinecraftProtocolFormat,
        packetRoute: PacketRoute,
        source: Source,
        byteCount: Int,
    ): SessionNumberPayload {
        var result = 0
        var shift = 0
        repeat(5) {
            val current = try {
                source.readByte().toInt() and 0xFF
            } catch (failure: EOFException) {
                throw MinecraftSerializationException(
                    "Truncated test payload VarInt",
                    failure,
                )
            }
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                return SessionNumberPayload(result)
            }
            shift += 7
        }
        throw MinecraftSerializationException("Test payload VarInt is too wide")
    }
}

private data class SessionQueryRequest(
    val transactionId: Int,
    val value: Int,
) : ClientboundPacket.Extension

private data class SessionQueryResponse(
    val transactionId: Int,
    val value: Int,
) : ServerboundPacket.Extension

@Serializable
private data class SessionQueryBody(
    val value: Byte,
)

private val sessionQueryRequestCodec = MappedKotlinxPacketBodyCodec(
    SessionQueryBody.serializer(),
    encodeBody = { sessionQueryRequest: SessionQueryRequest ->
        SessionQueryBody(sessionQueryRequest.value.toByte())
    },
    decodePacket = { route, body ->
        SessionQueryRequest(
            (route as PacketRoute.LoginQuery).transactionId,
            body.value.toInt() and 0xFF,
        )
    },
)

private val sessionQueryResponseCodec = MappedKotlinxPacketBodyCodec(
    SessionQueryBody.serializer(),
    encodeBody = { sessionQueryResponse: SessionQueryResponse ->
        SessionQueryBody(sessionQueryResponse.value.toByte())
    },
    decodePacket = { route, body ->
        SessionQueryResponse(
            (route as PacketRoute.LoginQuery).transactionId,
            body.value.toInt() and 0xFF,
        )
    },
)
