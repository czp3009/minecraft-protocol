package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.Uuid
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class MinecraftSessionTest {
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
        val response = StatusResponsePacket("""{"version":{"protocol":776}}""")
        server.send(response)
        assertEquals(response, client.receive())
    }

    @Test
    fun activatesCompressionAfterSetCompressionCrossesTheWire() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)
        val threshold = 32

        server.send(SetCompressionPacket(threshold))
        assertEquals(threshold, server.frames.codec.compressionThreshold)
        assertEquals(SetCompressionPacket(threshold), client.receive())
        assertEquals(threshold, client.frames.codec.compressionThreshold)

        val success = LoginSuccessPacket(
            GameProfile(Uuid(1, 2), "SessionProbe", emptyList()),
            sessionId = Uuid(3, 4),
        )
        server.send(success)
        assertEquals(success, client.receive())
        client.send(LoginAcknowledgedPacket)
        assertEquals(LoginAcknowledgedPacket, server.receive())
        assertEquals(ConnectionState.CONFIGURATION, client.state)
        assertEquals(ConnectionState.CONFIGURATION, server.state)
    }

    @Test
    fun entersPlayOnlyAfterTheConfigurationAcknowledgement() = runTest {
        val (client, server) = sessionPair()
        loginHandshake(client, server)
        server.send(
            LoginSuccessPacket(
                GameProfile(Uuid(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid(3, 4),
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
    fun rejectsTheWrongDirectionAndState() = runTest {
        val (client, _) = sessionPair()

        assertFailsWith<MinecraftSessionException> {
            client.send(StatusResponsePacket("{}"))
        }
        assertFailsWith<MinecraftSessionException> {
            client.send(StatusRequestPacket)
        }
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
    fun rejectsUnknownTruncatedAndOversizedInboundPacketIds() = runTest {
        suspend fun reject(packetData: ByteArray) {
            val (client, server) = sessionPair()
            client.frames.sendPacketData(packetData)
            assertFailsWith<MinecraftSessionException> {
                server.receive()
            }
        }

        reject(byteArrayOf(0x7F))
        reject(byteArrayOf(0x80.toByte()))
        reject(ByteArray(5) { 0x80.toByte() })
    }

    @Test
    fun wrapsPayloadDecodeFailuresWithStateDirectionAndPacketContext() =
        runTest {
            val (client, server) = sessionPair()
            val malformedHandshake = encodeVarInt(0) +
                    encodeVarInt(MinecraftProtocol.PROTOCOL_VERSION) +
                    byteArrayOf(1, 'x'.code.toByte(), 0x63, 0xDD.toByte(), 0)
            client.frames.sendPacketData(malformedHandshake)

            val failure = assertFailsWith<MinecraftSessionException> {
                server.receive()
            }

            assertNotNull(failure.cause)
            assertEquals(ConnectionState.HANDSHAKE, server.state)
        }

    private suspend fun loginHandshake(
        client: MinecraftSession,
        server: MinecraftSession,
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
        client.send(LoginStartPacket("SessionProbe", Uuid(1, 2)))
        server.receive()
    }

    private suspend fun enterPlay(
        client: MinecraftSession,
        server: MinecraftSession,
    ) {
        loginHandshake(client, server)
        server.send(
            LoginSuccessPacket(
                GameProfile(Uuid(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid(3, 4),
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

    private fun sessionPair(): Pair<MinecraftSession, MinecraftSession> {
        val clientToServer = ByteChannel()
        val serverToClient = ByteChannel()
        return MinecraftSession(
            frames = MinecraftFrameStream(serverToClient, clientToServer),
            side = MinecraftSessionSide.CLIENT,
        ) to MinecraftSession(
            frames = MinecraftFrameStream(clientToServer, serverToClient),
            side = MinecraftSessionSide.SERVER,
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
