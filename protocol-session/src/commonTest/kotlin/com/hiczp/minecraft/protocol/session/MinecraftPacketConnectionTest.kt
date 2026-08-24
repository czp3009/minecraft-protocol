package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.TextComponent
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(InternalMinecraftConnectionApi::class)
class MinecraftPacketConnectionTest {
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
        client.requestFlush()

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
        clientFrames.flush()

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
            format = MinecraftProtocolFormat(
                MinecraftProtocolFormat.configuration.copy(registries = context),
            ),
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

    @Test
    fun aPendingFlushAndOutgoingCloseFlushTheWireOnce() = runTest {
        val input = ByteChannel()
        val output = CountingFlushByteWriteChannel(ByteChannel())
        val frameStream = MinecraftFrameStream(input, output)
        val connection = createMinecraftClientPacketConnection(
            frameStream = frameStream,
            closeTransport = { frameStream.cancel() },
            definition = MinecraftConnectionDefinition(),
            connectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        connection.requestFlush()
        connection.outgoing.close()
        connection.awaitClosed()

        assertEquals(1, output.flushCount)
    }

    @Test
    fun closingOutgoingWithACauseDrainsAcceptedPacketsAndPreservesTheCause() = runTest {
        val (client, server) = enginePair()
        val failure = IllegalStateException("caller closed outgoing")
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )

        client.outgoing.send(handshake)
        client.outgoing.close(failure)

        assertEquals(handshake, server.incoming.receive())
        val completionFailure = assertIs<IllegalStateException>(assertFails { client.awaitClosed() })
        val incomingFailure = assertIs<IllegalStateException>(assertFails { client.incoming.receive() })
        assertEquals(failure.message, completionFailure.message)
        assertEquals(failure.message, incomingFailure.message)
        server.close()
    }

    @Test
    fun transportCleanupFailureAlwaysCompletesTheConnection() = runTest {
        val failure = IllegalStateException("transport close failed")
        val frameStream = MinecraftFrameStream(ByteChannel(), ByteChannel())
        val connection = createMinecraftClientPacketConnection(
            frameStream = frameStream,
            closeTransport = { throw failure },
            definition = MinecraftConnectionDefinition(),
        )

        connection.close()

        assertFalse(connection.isOpen)
        val completionFailure = assertIs<IllegalStateException>(assertFails { connection.awaitClosed() })
        val incomingFailure = assertIs<IllegalStateException>(assertFails { connection.incoming.receive() })
        assertEquals(failure.message, completionFailure.message)
        assertEquals(failure.message, incomingFailure.message)
    }

    @Test
    fun explicitAndRequestedFlushesFollowPreviouslyAcceptedPackets() = runTest {
        val (client, server) = enginePair()
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )
        client.outgoing.send(handshake)
        client.requestFlush()
        assertEquals(handshake, server.incoming.receive())

        client.outgoing.send(StatusRequestPacket)
        client.flush()
        assertEquals(StatusRequestPacket, server.incoming.receive())

        val response = StatusResponsePacket("{}")
        server.outgoing.send(response)
        server.requestFlush()
        assertEquals(response, client.incoming.receive())

        client.close()
        server.close()
    }

    @Test
    fun requestedFlushFailureTerminatesWithTheOriginalCause() = runTest {
        val clientToServer = ByteChannel()
        val serverToClient = ByteChannel()
        val failure = IllegalStateException("flush failed")
        val failingOutput = FailingFlushByteWriteChannel(clientToServer, failureAt = 2, failure)
        val clientFrames = MinecraftFrameStream(serverToClient, failingOutput)
        val serverFrames = MinecraftFrameStream(clientToServer, serverToClient)
        val client = createMinecraftClientPacketConnection(
            frameStream = clientFrames,
            closeTransport = { clientFrames.cancel() },
            definition = MinecraftConnectionDefinition(),
        )
        val server = createMinecraftServerPacketConnection(
            frameStream = serverFrames,
            closeTransport = { serverFrames.cancel() },
            definition = MinecraftConnectionDefinition(),
        )
        client.outgoing.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = "localhost",
                serverPort = 25_565,
                nextState = HandshakeNextState.STATUS,
            ),
        )
        client.requestFlush()
        server.incoming.receive()

        client.outgoing.send(StatusRequestPacket)
        client.requestFlush()

        val completionFailure = assertIs<IllegalStateException>(assertFails { client.awaitClosed() })
        val incomingFailure = assertIs<IllegalStateException>(assertFails { client.incoming.receive() })
        assertEquals(failure.message, completionFailure.message)
        assertEquals(failure.message, incomingFailure.message)
        server.close()
    }

    @Test
    fun clientboundBundlesAreAtomicAtThePublicChannelBoundary() = runTest {
        val (client, server) = enginePair()
        enterPlay(client, server)
        val subPackets = listOf<ClientboundPacket>(
            ChunkBatchStartPacket,
            ChunkBatchFinishedPacket(2),
        )

        server.outgoing.sendBundle(subPackets)
        server.requestFlush()

        val bundle = assertIs<ClientboundBundlePacket>(client.incoming.receive())
        assertEquals(subPackets, bundle.subPackets)
        assertTrue(client.incoming.tryReceive().isFailure)

        server.outgoing.send(BundleDelimiterPacket)
        server.outgoing.send(BundleDelimiterPacket)
        server.requestFlush()
        assertTrue(assertIs<ClientboundBundlePacket>(client.incoming.receive()).isEmpty)

        client.close()
        server.close()
    }

    @Test
    fun officialSkippablePacketEncodingFailureDoesNotStopTheWriter() = runTest {
        val (client, server) = enginePair()
        enterPlay(client, server)
        val oversizedText = TextComponent.literal("x".repeat(65_536))

        server.outgoing.send(SystemChatMessagePacket(oversizedText, overlay = false))
        server.outgoing.send(ChunkBatchStartPacket)
        server.requestFlush()

        assertEquals(ChunkBatchStartPacket, client.incoming.receive())
        assertTrue(server.isOpen)

        client.close()
        server.close()
    }

    private suspend fun enterPlay(
        client: MinecraftClientPacketConnection,
        server: MinecraftServerPacketConnection,
    ) {
        client.outgoing.send(
            HandshakePacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                serverAddress = "localhost",
                serverPort = 25_565,
                nextState = HandshakeNextState.LOGIN,
            ),
        )
        client.requestFlush()
        server.incoming.receive()
        client.outgoing.send(LoginStartPacket("SessionProbe", Uuid.fromLongs(1, 2)))
        client.requestFlush()
        server.incoming.receive()
        server.outgoing.send(
            LoginSuccessPacket(
                GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid.fromLongs(3, 4),
            ),
        )
        server.requestFlush()
        client.incoming.receive()
        client.outgoing.send(LoginAcknowledgedPacket)
        client.requestFlush()
        server.incoming.receive()
        server.outgoing.send(FinishConfigurationPacket)
        server.requestFlush()
        client.incoming.receive()
        client.outgoing.send(AcknowledgeFinishConfigurationPacket)
        client.requestFlush()
        server.incoming.receive()
        client.awaitState(ConnectionState.PLAY)
        server.awaitState(ConnectionState.PLAY)
    }

    private fun enginePair(
        definition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
    ): Pair<
            MinecraftClientPacketConnection,
            MinecraftServerPacketConnection,
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
        val client = createMinecraftClientPacketConnection(
            frameStream = clientFrames,
            closeTransport = { clientFrames.cancel() },
            definition = definition,
        )
        val server = createMinecraftServerPacketConnection(
            frameStream = serverFrames,
            closeTransport = { serverFrames.cancel() },
            definition = definition,
        )
        return EnginePair(client, server, clientFrames, serverFrames)
    }

    private data class EnginePair(
        val client: MinecraftClientPacketConnection,
        val server: MinecraftServerPacketConnection,
        val clientFrames: MinecraftFrameStream,
        val serverFrames: MinecraftFrameStream,
    )
}

private class FailingFlushByteWriteChannel(
    private val delegate: ByteWriteChannel,
    private val failureAt: Int,
    private val failure: Throwable,
) : ByteWriteChannel by delegate {
    private var flushCount = 0

    override suspend fun flush() {
        flushCount++
        if (flushCount == failureAt) throw failure
        delegate.flush()
    }
}

private class CountingFlushByteWriteChannel(
    private val delegate: ByteWriteChannel,
) : ByteWriteChannel by delegate {
    var flushCount: Int = 0
        private set

    override suspend fun flush() {
        flushCount++
        delegate.flush()
    }
}
