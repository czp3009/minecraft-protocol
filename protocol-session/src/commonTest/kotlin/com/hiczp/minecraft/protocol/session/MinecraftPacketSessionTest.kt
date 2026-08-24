package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.Identifier
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
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )

        client.send(handshake)
        assertEquals(handshake, server.receive())
        assertEquals(ConnectionState.STATUS, client.state)
        assertEquals(ConnectionState.STATUS, server.state)

        client.send(StatusRequestPacket)
        assertEquals(StatusRequestPacket, server.receive())
        val response = StatusResponsePacket(
            """{"version":{"protocol":${MinecraftProtocol.PROTOCOL_VERSION}}}""",
        )
        server.send(response)
        assertEquals(response, client.receive())
    }

    @Test
    fun activatesCompressionAfterSetCompressionCrossesTheWire() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)
        val threshold = 32

        server.send(SetCompressionPacket(threshold))
        assertEquals(threshold, server.frameStream.codec.compressionThreshold)
        assertEquals(SetCompressionPacket(threshold), client.receive())
        assertEquals(threshold, client.frameStream.codec.compressionThreshold)

        val success = LoginSuccessPacket(
            GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
            sessionId = Uuid.fromLongs(3, 4),
        )
        server.send(success)
        assertEquals(success, client.receive())
        client.send(LoginAcknowledgedPacket)
        assertEquals(LoginAcknowledgedPacket, server.receive())
        assertEquals(ConnectionState.CONFIGURATION, client.state)
        assertEquals(ConnectionState.CONFIGURATION, server.state)
    }

    @Test
    fun negativeSetCompressionDisablesAnAlreadyActiveCodec() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)

        server.send(SetCompressionPacket(16))
        assertEquals(SetCompressionPacket(16), client.receive())
        assertEquals(16, server.frameStream.codec.compressionThreshold)
        assertEquals(16, client.frameStream.codec.compressionThreshold)

        server.send(SetCompressionPacket(-1))
        assertEquals(SetCompressionPacket(-1), client.receive())
        assertNull(server.frameStream.codec.compressionThreshold)
        assertNull(client.frameStream.codec.compressionThreshold)
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
        assertEquals(ConnectionState.CONFIGURATION, server.state)
        assertEquals(ConnectionState.CONFIGURATION, client.state)
        client.send(AcknowledgeFinishConfigurationPacket)
        assertEquals(AcknowledgeFinishConfigurationPacket, server.receive())
        assertEquals(ConnectionState.PLAY, server.state)
        assertEquals(ConnectionState.PLAY, client.state)
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
        client.frameStream.output.cancel(
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
        assertEquals(ConnectionState.HANDSHAKE, client.state)
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
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.TRANSFER,
        )

        client.send(handshake)

        assertEquals(handshake, server.receive())
        assertEquals(ConnectionState.LOGIN, client.state)
        assertEquals(ConnectionState.LOGIN, server.state)
    }

    @Test
    fun playConfigurationAcknowledgementReturnsBothPeersToConfiguration() =
        runTest {
            val (client, server) = sessionPair()
            enterPlay(client, server)

            client.send(AcknowledgeConfigurationPacket)

            assertEquals(ConnectionState.CONFIGURATION, client.state)
            assertEquals(AcknowledgeConfigurationPacket, server.receive())
            assertEquals(ConnectionState.CONFIGURATION, server.state)
        }

    @Test
    fun preservesUnknownPacketIdsButRejectsMalformedPacketIds() = runTest {
        val (unknownClient, unknownServer) = sessionPair()
        unknownClient.frameStream.sendPacketData(byteArrayOf(0x7F, 1, 2, 3))
        assertEquals(
            UnknownPacket.Serverbound(
                route = PacketRoute.TopLevel(
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
            client.frameStream.sendPacketData(packetData)
            assertFailsWith<MinecraftSessionException> {
                server.receive()
            }
        }

        reject(byteArrayOf(0x80.toByte()))
        reject(ByteArray(5) { 0x80.toByte() })
    }

    @Test
    fun propagatesPayloadDecodeFailuresWithoutChangingSessionState() =
        runTest {
            val (client, server) = sessionPair()
            val malformedHandshake = encodeVarInt(0) +
                    encodeVarInt(MinecraftProtocol.PROTOCOL_VERSION) +
                    byteArrayOf(1, 'x'.code.toByte(), 0x63, 0xDD.toByte(), 0)
            client.frameStream.sendPacketData(malformedHandshake)

            assertFailsWith<IllegalArgumentException> {
                server.receive()
            }

            assertEquals(ConnectionState.HANDSHAKE, server.state)
        }

    @Test
    fun liftsActiveCustomPayloadsAndPreservesInactiveOnes() = runTest {
        val channel = Identifier("test:number")
        val route = PacketRouteKey.CustomPayload(
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            channel,
        )
        val registry = PacketRegistry(
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
        val (client, server) = sessionPair(registry)
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
        assertEquals(route, unknown.route.key)
        assertEquals(ByteString(byteArrayOf(7)), unknown.data)

        client.activateExtensionRoutes(setOf(route))
        server.activateExtensionRoutes(setOf(route))
        server.send(SessionNumberPayload(300))
        assertEquals(SessionNumberPayload(300), client.receive())
    }

    @Test
    fun activeExtensionCodecPropagatesMalformedPayload() = runTest {
        val channel = Identifier("test:number")
        val route = PacketRouteKey.CustomPayload(
            ConnectionState.CONFIGURATION,
            PacketDirection.CLIENTBOUND,
            channel,
        )
        val registry = PacketRegistry(
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
        val (client, server) = sessionPair(registry)
        enterConfiguration(client, server)
        client.activateExtensionRoutes(setOf(route))

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
        val registry = PacketRegistry(
            MinecraftPacketRegistry.entries,
            listOf(
                PacketCodecRegistration.clientboundLoginQuery(
                    channel,
                    SessionQueryRequest::class,
                    sessionQueryRequestCodec,
                ) { packet ->
                    PacketRoute.LoginQuery(
                        PacketDirection.CLIENTBOUND,
                        packet.transactionId,
                        channel,
                    )
                },
                PacketCodecRegistration.serverboundLoginQuery(
                    channel,
                    SessionQueryResponse::class,
                    sessionQueryResponseCodec,
                ) { packet ->
                    PacketRoute.LoginQuery(
                        PacketDirection.SERVERBOUND,
                        packet.transactionId,
                        channel,
                    )
                },
            ),
        )
        val (client, server) = sessionPair(registry)
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
        val response = LoginPluginResponsePacket(
            messageId = 31,
            data = ByteString(byteArrayOf(1, 2, 3)),
        )

        client.send(response)

        assertEquals(response, server.receive())
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
        assertEquals(ConnectionState.HANDSHAKE, client.state)
        assertEquals(ConnectionState.HANDSHAKE, client.inboundState)

        val valid = invalid.copy(serverAddress = "localhost")
        client.send(valid)
        assertEquals(valid, server.receive())
    }

    private suspend fun loginHandshake(
        client: MinecraftClientPacketSession,
        server: MinecraftServerPacketSession,
    ) {
        client.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = "localhost",
                serverPort = 25_565,
                nextState = HandshakeNextState.LOGIN,
            ),
        )
        server.receive()
        client.send(LoginStartPacket("SessionProbe", Uuid.fromLongs(1, 2)))
        server.receive()
    }

    private suspend fun enterPlay(
        client: MinecraftClientPacketSession,
        server: MinecraftServerPacketSession,
    ) {
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
        client.receive()
        client.send(AcknowledgeFinishConfigurationPacket)
        server.receive()
        assertEquals(ConnectionState.PLAY, client.state)
        assertEquals(ConnectionState.PLAY, server.state)
    }

    private suspend fun enterConfiguration(
        client: MinecraftClientPacketSession,
        server: MinecraftServerPacketSession,
    ) {
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
        assertEquals(ConnectionState.CONFIGURATION, client.state)
        assertEquals(ConnectionState.CONFIGURATION, server.state)
    }

    private fun sessionPair(
        packetRegistry: PacketRegistry = MinecraftPacketRegistry,
    ): Pair<MinecraftClientPacketSession, MinecraftServerPacketSession> {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        return MinecraftClientPacketSession(
            frameStream = MinecraftFrameStream(serverToClient, clientToServer),
            packetRegistry = packetRegistry,
        ) to MinecraftServerPacketSession(
            frameStream = MinecraftFrameStream(clientToServer, serverToClient),
            packetRegistry = packetRegistry,
        )
    }

    private fun encodeVarInt(value: Int): ByteArray {
        var remaining = value
        val bytes = ByteArray(5)
        var size = 0
        do {
            var current = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) current = current or 0x80
            bytes[size++] = current.toByte()
        } while (remaining != 0)
        return bytes.copyOf(size)
    }
}

private data class SessionNumberPayload(
    val value: Int,
) : ClientboundPacket.Extension

private data object SessionNumberPayloadCodec :
    PacketBodyCodec<SessionNumberPayload> {
    override fun encode(
        format: MinecraftProtocolFormat,
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
        format: MinecraftProtocolFormat,
        route: PacketRoute,
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
    encodeBody = { packet: SessionQueryRequest ->
        SessionQueryBody(packet.value.toByte())
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
    encodeBody = { packet: SessionQueryResponse ->
        SessionQueryBody(packet.value.toByte())
    },
    decodePacket = { route, body ->
        SessionQueryResponse(
            (route as PacketRoute.LoginQuery).transactionId,
            body.value.toInt() and 0xFF,
        )
    },
)
