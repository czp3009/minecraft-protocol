package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftConnectionEngineTest {
    @Test
    fun channelsCommitPacketsAndStateInWireOrder() = runTest {
        val (client, server) = enginePair()
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )

        client.outgoing.send(handshake)

        assertEquals(handshake, server.incoming.receive())
        client.awaitState(ConnectionState.STATUS)
        server.awaitState(ConnectionState.STATUS)
        assertEquals(ConnectionState.STATUS, client.state)
        assertEquals(ConnectionState.STATUS, server.state)

        client.close()
        client.awaitClosed()
        assertFalse(client.isOpen)
        server.close()
    }

    @Test
    fun writerFailureIsExposedAsTheOriginalChannelCause() = runTest {
        val (client, server) = enginePair()

        client.outgoing.send(StatusRequestPacket)
        val failure = assertFailsWith<MinecraftSessionException> {
            client.awaitClosed()
        }
        val receiveFailure = assertFailsWith<MinecraftSessionException> {
            client.incoming.receive()
        }
        val sendFailure = assertFailsWith<MinecraftSessionException> {
            client.outgoing.send(
                HandshakePacket(
                    MinecraftProtocol.PROTOCOL_VERSION,
                    "localhost",
                    25_565,
                    HandshakeNextState.STATUS,
                ),
            )
        }

        assertEquals(failure.message, receiveFailure.message)
        assertEquals(failure.message, sendFailure.message)
        server.close()
    }

    @Test
    fun malformedWireInputFailsIncomingAndCompletionWithoutAReply() = runTest {
        val (client, server, clientFrames, _) = enginePairWithFrames()
        clientFrames.sendPacketData(byteArrayOf(0x80.toByte()))

        val receiveFailure = assertFailsWith<MinecraftSessionException> {
            server.incoming.receive()
        }
        val completionFailure = assertFailsWith<MinecraftSessionException> {
            server.awaitClosed()
        }

        assertEquals(receiveFailure.message, completionFailure.message)
        assertTrue(client.incoming.tryReceive().isFailure)
        client.close()
    }

    @Test
    fun connectionDefinitionsRetainCallerOwnedRegistryReferences() = runTest {
        val context = ProtocolRegistryContext.Empty.withChunkSectionCount(24)
        val definition = MinecraftConnectionDefinition.compose(
            registries = context,
        )
        val (client, server) = enginePair(definition)

        assertSame(context, definition.registries)
        assertSame(context, client.registries)
        assertSame(context, server.registries)

        client.close()
        server.close()
    }

    @Test
    fun closingOutgoingDrainsAcceptedPacketsBeforeClosingTheConnection() = runTest {
        val (client, server) = enginePair()
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )

        client.outgoing.send(handshake)
        client.outgoing.close()

        assertEquals(handshake, server.incoming.receive())
        client.awaitClosed()
        assertFalse(client.isOpen)
        server.close()
    }

    private fun enginePair(
        definition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
    ): Pair<
            MinecraftConnectionEngine<ClientboundPacket, ServerboundPacket>,
            MinecraftConnectionEngine<ServerboundPacket, ClientboundPacket>,
            > {
        val (client, server) = enginePairWithFrames(definition)
        return client to server
    }

    private fun enginePairWithFrames(
        definition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
    ): EnginePair {
        val clientToServer = ByteChannel()
        val serverToClient = ByteChannel()
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val serverFrames = MinecraftFrameStream(clientToServer, serverToClient)
        val client = MinecraftConnectionEngine<ClientboundPacket, ServerboundPacket>(
            frames = clientFrames,
            closeTransport = { clientFrames.cancel() },
            side = MinecraftSessionSide.CLIENT,
            definition = definition,
        )
        val server = MinecraftConnectionEngine<ServerboundPacket, ClientboundPacket>(
            frames = serverFrames,
            closeTransport = { serverFrames.cancel() },
            side = MinecraftSessionSide.SERVER,
            definition = definition,
        )
        return EnginePair(client, server, clientFrames, serverFrames)
    }

    private data class EnginePair(
        val client: MinecraftConnectionEngine<ClientboundPacket, ServerboundPacket>,
        val server: MinecraftConnectionEngine<ServerboundPacket, ClientboundPacket>,
        val clientFrames: MinecraftFrameStream,
        val serverFrames: MinecraftFrameStream,
    )
}
