package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(InternalMinecraftConnectionApi::class, ExperimentalCoroutinesApi::class)
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
        val protocolRegistryContext = ProtocolRegistryContext.Empty.withChunkSectionCount(24)
        val definition = MinecraftConnectionDefinition.compose(
            format = MinecraftProtocolFormat(
                MinecraftProtocolFormat.configuration.copy(
                    protocolRegistryContext = protocolRegistryContext,
                ),
            ),
        )
        val (client, server) = enginePair(definition)

        assertSame(protocolRegistryContext, definition.protocolRegistryContext)
        assertSame(protocolRegistryContext, client.protocolRegistryContext)
        assertSame(protocolRegistryContext, server.protocolRegistryContext)

        client.close()
        server.close()
    }

    @Test
    fun closingOutgoingDrainsAcceptedPacketsBeforeClosingTheConnection() = runTest {
        val harness = drainingClient(StandardTestDispatcher(testScheduler))
        val client = harness.client
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )

        client.outgoing.send(handshake)
        client.outgoing.close()

        client.awaitClosed()
        assertEquals(handshake, harness.server.receive())
        assertFalse(client.isOpen)
        harness.close()
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
        val harness = drainingClient(StandardTestDispatcher(testScheduler))
        val client = harness.client
        val failure = IllegalStateException("caller closed outgoing")
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.STATUS,
        )

        client.outgoing.send(handshake)
        client.outgoing.close(failure)

        val completionFailure = assertIs<IllegalStateException>(assertFails { client.awaitClosed() })
        val incomingFailure = assertIs<IllegalStateException>(assertFails { client.incoming.receive() })
        assertEquals(handshake, harness.server.receive())
        assertEquals(failure.message, completionFailure.message)
        assertEquals(failure.message, incomingFailure.message)
        harness.close()
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
    fun clientAutomaticallyAnswersDirectConfigurationAndPlayKeepAlives() = runTest {
        val (client, server) = enginePair()
        enterConfiguration(client, server)

        server.outgoing.send(ConfigurationClientboundKeepAlivePacket(41))
        server.requestFlush()
        assertEquals(ConfigurationServerboundKeepAlivePacket(41), server.incoming.receive())
        assertTrue(client.incoming.tryReceive().isFailure)

        enterPlayFromConfiguration(client, server)
        server.outgoing.send(PlayClientboundKeepAlivePacket(42))
        server.requestFlush()
        assertEquals(PlayServerboundKeepAlivePacket(42), server.incoming.receive())
        assertTrue(client.incoming.tryReceive().isFailure)

        client.close()
        server.close()
    }

    @Test
    fun serverKeepAliveKeepsItsSendBaselineAndConsumesMatchingReplies() = runTest {
        val pair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(pair.client, pair.server)
        pair.server.enableConfigurationKeepAlive(1.seconds)

        advanceTimeBy(1_000)
        runCurrent()
        val firstRequest = assertIs<ConfigurationClientboundKeepAlivePacket>(pair.client.receive())

        advanceTimeBy(500)
        pair.client.send(ConfigurationServerboundKeepAlivePacket(firstRequest.id))
        runCurrent()
        assertTrue(pair.server.incoming.tryReceive().isFailure)

        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, pair.clientFrames.input.availableForRead)
        advanceTimeBy(1)
        runCurrent()
        assertIs<ConfigurationClientboundKeepAlivePacket>(pair.client.receive())

        pair.close()
    }

    @Test
    fun serverKeepAliveRequiresAPositiveInterval() = runTest {
        val (client, server) = enginePair()

        assertFailsWith<IllegalArgumentException> {
            server.enableConfigurationKeepAlive(Duration.ZERO)
        }

        client.close()
        server.close()
    }

    @Test
    fun disabledServerKeepAliveLeavesRepliesOnThePublicIncomingChannel() = runTest {
        val pair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(pair.client, pair.server)

        pair.client.send(ConfigurationServerboundKeepAlivePacket(7))
        runCurrent()

        assertEquals(ConfigurationServerboundKeepAlivePacket(7), pair.server.incoming.receive())
        assertTrue(pair.server.isOpen)
        pair.close()
    }

    @Test
    fun serverKeepAliveTimesOutAndRejectsMissingOrMismatchedChallenges() = runTest {
        val timeoutPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(timeoutPair.client, timeoutPair.server)
        timeoutPair.server.enableConfigurationKeepAlive(1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        timeoutPair.client.receive()
        advanceTimeBy(1_000)
        runCurrent()
        assertContains(
            assertFailsWith<MinecraftSessionException> { timeoutPair.server.awaitClosed() }.message.orEmpty(),
            "timed out",
        )

        val missingPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(missingPair.client, missingPair.server)
        missingPair.server.enableConfigurationKeepAlive(1.seconds)
        missingPair.client.send(ConfigurationServerboundKeepAlivePacket(7))
        runCurrent()
        assertContains(
            assertFailsWith<MinecraftSessionException> { missingPair.server.awaitClosed() }.message.orEmpty(),
            "without a pending challenge",
        )

        val mismatchPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(mismatchPair.client, mismatchPair.server)
        mismatchPair.server.enableConfigurationKeepAlive(1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        val request = assertIs<ConfigurationClientboundKeepAlivePacket>(mismatchPair.client.receive())
        mismatchPair.client.send(ConfigurationServerboundKeepAlivePacket(request.id + 1))
        runCurrent()
        assertContains(
            assertFailsWith<MinecraftSessionException> { mismatchPair.server.awaitClosed() }.message.orEmpty(),
            "did not match",
        )
    }

    @Test
    fun replacingAndSwitchingKeepAliveRunsResetTheirTimerAndPendingChallenge() = runTest {
        val replacementPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(replacementPair.client, replacementPair.server)
        replacementPair.server.enableConfigurationKeepAlive(1.seconds)
        advanceTimeBy(500)
        replacementPair.server.enableConfigurationKeepAlive(1.seconds)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, replacementPair.clientFrames.input.availableForRead)
        advanceTimeBy(500)
        runCurrent()
        assertIs<ConfigurationClientboundKeepAlivePacket>(replacementPair.client.receive())
        replacementPair.close()

        val switchPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(switchPair.client, switchPair.server)
        switchPair.server.enableConfigurationKeepAlive(1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<ConfigurationClientboundKeepAlivePacket>(switchPair.client.receive())
        switchPair.server.disableKeepAlive()

        switchPair.server.outgoing.send(FinishConfigurationPacket)
        switchPair.server.requestFlush()
        assertEquals(FinishConfigurationPacket, switchPair.client.receive())
        switchPair.client.send(AcknowledgeFinishConfigurationPacket)
        assertEquals(AcknowledgeFinishConfigurationPacket, switchPair.server.incoming.receive())
        switchPair.server.awaitState(ConnectionState.PLAY)
        switchPair.server.enablePlayKeepAlive(1.seconds)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, switchPair.clientFrames.input.availableForRead)
        advanceTimeBy(1)
        runCurrent()
        assertIs<PlayClientboundKeepAlivePacket>(switchPair.client.receive())
        switchPair.close()
    }

    @Test
    fun connectionOwnedPacketsPrecedeReadyPublicPacketsAtTheNextBoundary() = runTest {
        val pair = controlledServerPair(
            dispatcher = StandardTestDispatcher(testScheduler),
            gateFlushes = true,
        )
        enterConfiguration(pair.client, pair.server)
        val gatedOutput = checkNotNull(pair.gatedOutput)
        val flushStarted = gatedOutput.blockNextFlush()
        val flush = async { pair.server.flush() }
        flushStarted.await()
        pair.server.enableConfigurationKeepAlive(1.seconds)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(pair.server.outgoing.trySend(FeatureFlagsPacket(emptySet())).isSuccess)
        gatedOutput.releaseFlush()
        runCurrent()
        flush.await()

        assertIs<ConfigurationClientboundKeepAlivePacket>(pair.client.receive())
        assertEquals(FeatureFlagsPacket(emptySet()), pair.client.receive())
        pair.close()
    }

    @Test
    fun disablingKeepAliveCancelsARequestThatHasNotRendezvousedWithTheWriter() = runTest {
        val pair = controlledServerPair(
            dispatcher = StandardTestDispatcher(testScheduler),
            gateFlushes = true,
        )
        enterConfiguration(pair.client, pair.server)
        val gatedOutput = checkNotNull(pair.gatedOutput)
        val flushStarted = gatedOutput.blockNextFlush()
        val flush = async { pair.server.flush() }
        flushStarted.await()

        pair.server.enableConfigurationKeepAlive(1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        pair.server.disableKeepAlive()
        gatedOutput.releaseFlush()
        runCurrent()
        flush.await()

        assertEquals(0, pair.clientFrames.input.availableForRead)
        pair.server.outgoing.send(FeatureFlagsPacket(emptySet()))
        pair.server.requestFlush()
        assertEquals(FeatureFlagsPacket(emptySet()), pair.client.receive())
        pair.close()
    }

    @Test
    fun disablingKeepAliveAllowsARequestAlreadyAcceptedByTheWriterToComplete() = runTest {
        val pair = controlledServerPair(
            dispatcher = StandardTestDispatcher(testScheduler),
            gateFlushes = true,
        )
        enterConfiguration(pair.client, pair.server)
        val gatedOutput = checkNotNull(pair.gatedOutput)
        val flushStarted = gatedOutput.blockNextFlush()

        pair.server.enableConfigurationKeepAlive(1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        flushStarted.await()
        pair.server.disableKeepAlive()
        gatedOutput.releaseFlush()
        runCurrent()

        assertIs<ConfigurationClientboundKeepAlivePacket>(pair.client.receive())
        pair.close()
    }

    @Test
    fun closingTheConnectionCancelsItsKeepAliveRun() = runTest {
        val pair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(pair.client, pair.server)
        pair.server.enableConfigurationKeepAlive(1.seconds)

        pair.server.close()
        pair.server.awaitClosed()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(pair.server.isOpen)
    }

    private suspend fun enterPlay(
        client: MinecraftClientPacketConnection,
        server: MinecraftServerPacketConnection,
    ) {
        enterConfiguration(client, server)
        enterPlayFromConfiguration(client, server)
    }

    private suspend fun enterConfiguration(
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
        client.awaitState(ConnectionState.CONFIGURATION)
        server.awaitState(ConnectionState.CONFIGURATION)
    }

    private suspend fun enterPlayFromConfiguration(
        client: MinecraftClientPacketConnection,
        server: MinecraftServerPacketConnection,
    ) {
        server.outgoing.send(FinishConfigurationPacket)
        server.requestFlush()
        client.incoming.receive()
        client.outgoing.send(AcknowledgeFinishConfigurationPacket)
        client.requestFlush()
        server.incoming.receive()
        client.awaitState(ConnectionState.PLAY)
        server.awaitState(ConnectionState.PLAY)
    }

    private suspend fun enterConfiguration(
        client: MinecraftClientPacketSession,
        server: MinecraftServerPacketConnection,
    ) {
        val handshake = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.LOGIN,
        )
        client.send(handshake)
        assertEquals(handshake, server.incoming.receive())
        val loginStart = LoginStartPacket("SessionProbe", Uuid.fromLongs(1, 2))
        client.send(loginStart)
        assertEquals(loginStart, server.incoming.receive())
        server.outgoing.send(
            LoginSuccessPacket(
                GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid.fromLongs(3, 4),
            ),
        )
        server.requestFlush()
        client.receive()
        client.send(LoginAcknowledgedPacket)
        assertEquals(LoginAcknowledgedPacket, server.incoming.receive())
        client.awaitState(ConnectionState.CONFIGURATION)
        server.awaitState(ConnectionState.CONFIGURATION)
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

    private fun controlledServerPair(
        dispatcher: CoroutineDispatcher,
        gateFlushes: Boolean = false,
    ): ControlledServerPair {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val gatedOutput = if (gateFlushes) GatedFlushByteWriteChannel(serverToClient) else null
        val serverFrames = MinecraftFrameStream(clientToServer, gatedOutput ?: serverToClient)
        val server = createMinecraftServerPacketConnection(
            frameStream = serverFrames,
            closeTransport = { serverFrames.cancel() },
            definition = MinecraftConnectionDefinition(),
            connectionDispatcher = dispatcher,
        )
        return ControlledServerPair(
            client = MinecraftClientPacketSession(clientFrames),
            server = server,
            clientFrames = clientFrames,
            gatedOutput = gatedOutput,
        )
    }

    private fun drainingClient(dispatcher: CoroutineDispatcher): DrainingClient {
        val clientToServer = ByteChannel()
        val clientFrames = MinecraftFrameStream(ByteChannel(), clientToServer)
        val serverFrames = MinecraftFrameStream(clientToServer, ByteChannel())
        val client = createMinecraftClientPacketConnection(
            frameStream = clientFrames,
            closeTransport = {},
            definition = MinecraftConnectionDefinition(),
            connectionDispatcher = dispatcher,
        )
        return DrainingClient(
            client = client,
            server = MinecraftServerPacketSession(serverFrames),
            clientFrames = clientFrames,
            serverFrames = serverFrames,
        )
    }

    private data class EnginePair(
        val client: MinecraftClientPacketConnection,
        val server: MinecraftServerPacketConnection,
        val clientFrames: MinecraftFrameStream,
        val serverFrames: MinecraftFrameStream,
    )

    private data class DrainingClient(
        val client: MinecraftClientPacketConnection,
        val server: MinecraftServerPacketSession,
        val clientFrames: MinecraftFrameStream,
        val serverFrames: MinecraftFrameStream,
    ) {
        fun close() {
            clientFrames.cancel()
            serverFrames.cancel()
        }
    }

    private data class ControlledServerPair(
        val client: MinecraftClientPacketSession,
        val server: MinecraftServerPacketConnection,
        val clientFrames: MinecraftFrameStream,
        val gatedOutput: GatedFlushByteWriteChannel?,
    ) {
        fun close() {
            server.close()
            clientFrames.cancel()
        }
    }
}

private class GatedFlushByteWriteChannel(
    private val delegate: ByteWriteChannel,
) : ByteWriteChannel by delegate {
    private var flushGate: CompletableDeferred<Unit>? = null
    private var flushStarted: CompletableDeferred<Unit>? = null

    fun blockNextFlush(): CompletableDeferred<Unit> {
        check(flushGate == null) { "A flush is already blocked" }
        flushGate = CompletableDeferred()
        return CompletableDeferred<Unit>().also { started -> flushStarted = started }
    }

    fun releaseFlush() {
        checkNotNull(flushGate).complete(Unit)
    }

    override suspend fun flush() {
        val gate = flushGate
        if (gate != null) {
            checkNotNull(flushStarted).complete(Unit)
            gate.await()
            flushGate = null
            flushStarted = null
        }
        delegate.flush()
    }
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
