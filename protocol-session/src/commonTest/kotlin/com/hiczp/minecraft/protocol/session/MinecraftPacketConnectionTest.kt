package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ClientIntent
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.model.type.ServerStatus
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormat
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
        val clientIntentionPacket = ClientIntentionPacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            hostName = "localhost",
            port = 25_565,
            intention = ClientIntent.STATUS,
        )

        client.outgoing.send(clientIntentionPacket)
        client.requestFlush()

        assertEquals(clientIntentionPacket, server.incoming.receive())
        client.awaitState(ConnectionState.STATUS)
        server.awaitState(ConnectionState.STATUS)
        assertEquals(ConnectionState.STATUS, client.connectionState)
        assertEquals(ConnectionState.STATUS, server.connectionState)

        client.close()
        client.awaitClosed()
        assertFalse(client.isOpen)
        server.close()
    }

    @Test
    fun writerFailureIsExposedAsTheOriginalChannelCause() = runTest {
        val (client, server) = enginePair()

        client.outgoing.send(ServerboundStatusRequestPacket)
        val failure = assertFailsWith<MinecraftSessionException> {
            client.awaitClosed()
        }
        val receiveFailure = assertFailsWith<MinecraftSessionException> {
            client.incoming.receive()
        }
        val sendFailure = assertFailsWith<MinecraftSessionException> {
            client.outgoing.send(
                ClientIntentionPacket(
                    MinecraftProtocol.PROTOCOL_VERSION,
                    "localhost",
                    25_565,
                    ClientIntent.STATUS,
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
        val packetCodecContext = PacketCodecContext.Empty
        val minecraftConnectionDefinition = MinecraftConnectionDefinition.compose(
            minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
                MinecraftPacketPayloadFormat.minecraftPacketPayloadFormatConfiguration.copy(
                    packetCodecContext = packetCodecContext,
                ),
            ),
        )
        val (client, server) = enginePair(minecraftConnectionDefinition)

        assertSame(packetCodecContext, minecraftConnectionDefinition.packetCodecContext)
        assertSame(packetCodecContext, client.packetCodecContext)
        assertSame(packetCodecContext, server.packetCodecContext)

        client.close()
        server.close()
    }

    @Test
    fun closingOutgoingDrainsAcceptedPacketsBeforeClosingTheConnection() = runTest {
        val harness = drainingClient(StandardTestDispatcher(testScheduler))
        val minecraftClientPacketConnection = harness.client
        val clientIntentionPacket = ClientIntentionPacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            hostName = "localhost",
            port = 25_565,
            intention = ClientIntent.STATUS,
        )

        minecraftClientPacketConnection.outgoing.send(clientIntentionPacket)
        minecraftClientPacketConnection.outgoing.close()

        minecraftClientPacketConnection.awaitClosed()
        assertEquals(clientIntentionPacket, harness.server.receive())
        assertFalse(minecraftClientPacketConnection.isOpen)
        harness.close()
    }

    @Test
    fun aPendingFlushAndOutgoingCloseFlushTheWireOnce() = runTest {
        val input = ByteChannel()
        val output = CountingFlushByteWriteChannel(ByteChannel())
        val minecraftFrameStream = MinecraftFrameStream(input, output)
        val minecraftClientPacketConnection = createMinecraftClientPacketConnection(
            minecraftFrameStream = minecraftFrameStream,
            closeTransport = { minecraftFrameStream.cancel() },
            minecraftConnectionDefinition = MinecraftConnectionDefinition(),
            connectionDispatcher = StandardTestDispatcher(testScheduler),
        )

        minecraftClientPacketConnection.requestFlush()
        minecraftClientPacketConnection.outgoing.close()
        minecraftClientPacketConnection.awaitClosed()

        assertEquals(1, output.flushCount)
    }

    @Test
    fun closingOutgoingWithACauseDrainsAcceptedPacketsAndPreservesTheCause() = runTest {
        val harness = drainingClient(StandardTestDispatcher(testScheduler))
        val minecraftClientPacketConnection = harness.client
        val failure = IllegalStateException("caller closed outgoing")
        val clientIntentionPacket = ClientIntentionPacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            hostName = "localhost",
            port = 25_565,
            intention = ClientIntent.STATUS,
        )

        minecraftClientPacketConnection.outgoing.send(clientIntentionPacket)
        minecraftClientPacketConnection.outgoing.close(failure)

        val completionFailure =
            assertIs<IllegalStateException>(assertFails { minecraftClientPacketConnection.awaitClosed() })
        val incomingFailure =
            assertIs<IllegalStateException>(assertFails { minecraftClientPacketConnection.incoming.receive() })
        assertEquals(clientIntentionPacket, harness.server.receive())
        assertEquals(failure.message, completionFailure.message)
        assertEquals(failure.message, incomingFailure.message)
        harness.close()
    }

    @Test
    fun transportCleanupFailureAlwaysCompletesTheConnection() = runTest {
        val failure = IllegalStateException("transport close failed")
        val minecraftFrameStream = MinecraftFrameStream(ByteChannel(), ByteChannel())
        val minecraftClientPacketConnection = createMinecraftClientPacketConnection(
            minecraftFrameStream = minecraftFrameStream,
            closeTransport = { throw failure },
            minecraftConnectionDefinition = MinecraftConnectionDefinition(),
        )

        minecraftClientPacketConnection.close()

        assertFalse(minecraftClientPacketConnection.isOpen)
        val completionFailure =
            assertIs<IllegalStateException>(assertFails { minecraftClientPacketConnection.awaitClosed() })
        val incomingFailure =
            assertIs<IllegalStateException>(assertFails { minecraftClientPacketConnection.incoming.receive() })
        assertEquals(failure.message, completionFailure.message)
        assertEquals(failure.message, incomingFailure.message)
    }

    @Test
    fun explicitAndRequestedFlushesFollowPreviouslyAcceptedPackets() = runTest {
        val (client, server) = enginePair()
        val clientIntentionPacket = ClientIntentionPacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            hostName = "localhost",
            port = 25_565,
            intention = ClientIntent.STATUS,
        )
        client.outgoing.send(clientIntentionPacket)
        client.requestFlush()
        assertEquals(clientIntentionPacket, server.incoming.receive())

        client.outgoing.send(ServerboundStatusRequestPacket)
        client.flush()
        assertEquals(ServerboundStatusRequestPacket, server.incoming.receive())

        val clientboundStatusResponsePacket = ClientboundStatusResponsePacket(ServerStatus())
        server.outgoing.send(clientboundStatusResponsePacket)
        server.requestFlush()
        assertEquals(clientboundStatusResponsePacket, client.incoming.receive())

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
            minecraftFrameStream = clientFrames,
            closeTransport = { clientFrames.cancel() },
            minecraftConnectionDefinition = MinecraftConnectionDefinition(),
        )
        val server = createMinecraftServerPacketConnection(
            minecraftFrameStream = serverFrames,
            closeTransport = { serverFrames.cancel() },
            minecraftConnectionDefinition = MinecraftConnectionDefinition(),
        )
        client.outgoing.send(
            ClientIntentionPacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                hostName = "localhost",
                port = 25_565,
                intention = ClientIntent.STATUS,
            ),
        )
        client.requestFlush()
        server.incoming.receive()

        client.outgoing.send(ServerboundStatusRequestPacket)
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

        server.outgoing.send(ClientboundKeepAlivePacket(41))
        server.requestFlush()
        assertEquals(ServerboundKeepAlivePacket(41), server.incoming.receive())
        assertTrue(client.incoming.tryReceive().isFailure)

        enterPlayFromConfiguration(client, server)
        server.outgoing.send(ClientboundKeepAlivePacket(42))
        server.requestFlush()
        assertEquals(ServerboundKeepAlivePacket(42), server.incoming.receive())
        assertTrue(client.incoming.tryReceive().isFailure)

        client.close()
        server.close()
    }

    @Test
    fun serverKeepAliveKeepsItsSendBaselineAndConsumesMatchingReplies() = runTest {
        val controlledServerPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(controlledServerPair.client, controlledServerPair.server)
        controlledServerPair.server.enableKeepAlive(interval = 1.seconds)

        advanceTimeBy(1_000)
        runCurrent()
        val firstRequest = assertIs<ClientboundKeepAlivePacket>(controlledServerPair.client.receive())

        advanceTimeBy(500)
        controlledServerPair.client.send(ServerboundKeepAlivePacket(firstRequest.id))
        runCurrent()
        assertTrue(controlledServerPair.server.incoming.tryReceive().isFailure)

        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, controlledServerPair.clientFrames.input.availableForRead)
        advanceTimeBy(1)
        runCurrent()
        assertIs<ClientboundKeepAlivePacket>(controlledServerPair.client.receive())

        controlledServerPair.close()
    }

    @Test
    fun serverKeepAliveRequiresAPositiveInterval() = runTest {
        val (client, server) = enginePair()

        assertFailsWith<IllegalArgumentException> {
            server.enableKeepAlive(interval = Duration.ZERO)
        }

        client.close()
        server.close()
    }

    @Test
    fun disabledServerKeepAliveLeavesRepliesOnThePublicIncomingChannel() = runTest {
        val controlledServerPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(controlledServerPair.client, controlledServerPair.server)

        controlledServerPair.client.send(ServerboundKeepAlivePacket(7))
        runCurrent()

        assertEquals(ServerboundKeepAlivePacket(7), controlledServerPair.server.incoming.receive())
        assertTrue(controlledServerPair.server.isOpen)
        controlledServerPair.close()
    }

    @Test
    fun serverKeepAliveTimesOutAndRejectsMissingOrMismatchedChallenges() = runTest {
        val timeoutPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(timeoutPair.client, timeoutPair.server)
        timeoutPair.server.enableKeepAlive(interval = 1.seconds)
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
        missingPair.server.enableKeepAlive(interval = 1.seconds)
        missingPair.client.send(ServerboundKeepAlivePacket(7))
        runCurrent()
        assertContains(
            assertFailsWith<MinecraftSessionException> { missingPair.server.awaitClosed() }.message.orEmpty(),
            "without a pending challenge",
        )

        val mismatchPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(mismatchPair.client, mismatchPair.server)
        mismatchPair.server.enableKeepAlive(interval = 1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        val request = assertIs<ClientboundKeepAlivePacket>(mismatchPair.client.receive())
        mismatchPair.client.send(ServerboundKeepAlivePacket(request.id + 1))
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
        replacementPair.server.enableKeepAlive(interval = 1.seconds)
        advanceTimeBy(500)
        replacementPair.server.enableKeepAlive(interval = 1.seconds)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, replacementPair.clientFrames.input.availableForRead)
        advanceTimeBy(500)
        runCurrent()
        assertIs<ClientboundKeepAlivePacket>(replacementPair.client.receive())
        replacementPair.close()

        val switchPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(switchPair.client, switchPair.server)
        switchPair.server.enableKeepAlive(interval = 1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<ClientboundKeepAlivePacket>(switchPair.client.receive())
        switchPair.server.disableKeepAlive()

        switchPair.server.outgoing.send(ClientboundFinishConfigurationPacket)
        switchPair.server.requestFlush()
        assertEquals(ClientboundFinishConfigurationPacket, switchPair.client.receive())
        switchPair.client.send(ServerboundFinishConfigurationPacket)
        assertEquals(ServerboundFinishConfigurationPacket, switchPair.server.incoming.receive())
        switchPair.server.awaitState(ConnectionState.PLAY)
        switchPair.server.enableKeepAlive(interval = 1.seconds)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, switchPair.clientFrames.input.availableForRead)
        advanceTimeBy(1)
        runCurrent()
        assertIs<ClientboundKeepAlivePacket>(switchPair.client.receive())
        switchPair.close()
    }

    @Test
    fun connectionOwnedPacketsPrecedeReadyPublicPacketsAtTheNextBoundary() = runTest {
        val controlledServerPair = controlledServerPair(
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
            gateFlushes = true,
        )
        enterConfiguration(controlledServerPair.client, controlledServerPair.server)
        val gatedOutput = checkNotNull(controlledServerPair.gatedOutput)
        val flushStarted = gatedOutput.blockNextFlush()
        val flush = async { controlledServerPair.server.flush() }
        flushStarted.await()
        controlledServerPair.server.enableKeepAlive(interval = 1.seconds)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controlledServerPair.server.outgoing.trySend(ClientboundUpdateEnabledFeaturesPacket(emptySet())).isSuccess)
        gatedOutput.releaseFlush()
        runCurrent()
        flush.await()

        assertIs<ClientboundKeepAlivePacket>(controlledServerPair.client.receive())
        assertEquals(ClientboundUpdateEnabledFeaturesPacket(emptySet()), controlledServerPair.client.receive())
        controlledServerPair.close()
    }

    @Test
    fun disablingKeepAliveCancelsARequestThatHasNotRendezvousedWithTheWriter() = runTest {
        val controlledServerPair = controlledServerPair(
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
            gateFlushes = true,
        )
        enterConfiguration(controlledServerPair.client, controlledServerPair.server)
        val gatedOutput = checkNotNull(controlledServerPair.gatedOutput)
        val flushStarted = gatedOutput.blockNextFlush()
        val flush = async { controlledServerPair.server.flush() }
        flushStarted.await()

        controlledServerPair.server.enableKeepAlive(interval = 1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        controlledServerPair.server.disableKeepAlive()
        gatedOutput.releaseFlush()
        runCurrent()
        flush.await()

        assertEquals(0, controlledServerPair.clientFrames.input.availableForRead)
        controlledServerPair.server.outgoing.send(ClientboundUpdateEnabledFeaturesPacket(emptySet()))
        controlledServerPair.server.requestFlush()
        assertEquals(ClientboundUpdateEnabledFeaturesPacket(emptySet()), controlledServerPair.client.receive())
        controlledServerPair.close()
    }

    @Test
    fun disablingKeepAliveAllowsARequestAlreadyAcceptedByTheWriterToComplete() = runTest {
        val controlledServerPair = controlledServerPair(
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
            gateFlushes = true,
        )
        enterConfiguration(controlledServerPair.client, controlledServerPair.server)
        val gatedOutput = checkNotNull(controlledServerPair.gatedOutput)
        val flushStarted = gatedOutput.blockNextFlush()

        controlledServerPair.server.enableKeepAlive(interval = 1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        flushStarted.await()
        controlledServerPair.server.disableKeepAlive()
        gatedOutput.releaseFlush()
        runCurrent()

        assertIs<ClientboundKeepAlivePacket>(controlledServerPair.client.receive())
        controlledServerPair.close()
    }

    @Test
    fun closingTheConnectionCancelsItsKeepAliveRun() = runTest {
        val controlledServerPair = controlledServerPair(StandardTestDispatcher(testScheduler))
        enterConfiguration(controlledServerPair.client, controlledServerPair.server)
        controlledServerPair.server.enableKeepAlive(interval = 1.seconds)

        controlledServerPair.server.close()
        controlledServerPair.server.awaitClosed()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(controlledServerPair.server.isOpen)
    }

    private suspend fun enterPlay(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        enterConfiguration(minecraftClientPacketConnection, minecraftServerPacketConnection)
        enterPlayFromConfiguration(minecraftClientPacketConnection, minecraftServerPacketConnection)
    }

    private suspend fun enterConfiguration(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        minecraftClientPacketConnection.outgoing.send(
            ClientIntentionPacket(
                protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                hostName = "localhost",
                port = 25_565,
                intention = ClientIntent.LOGIN,
            ),
        )
        minecraftClientPacketConnection.requestFlush()
        minecraftServerPacketConnection.incoming.receive()
        minecraftClientPacketConnection.outgoing.send(ServerboundHelloPacket("SessionProbe", Uuid.fromLongs(1, 2)))
        minecraftClientPacketConnection.requestFlush()
        minecraftServerPacketConnection.incoming.receive()
        minecraftServerPacketConnection.outgoing.send(
            ClientboundLoginFinishedPacket(
                GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid.fromLongs(3, 4),
            ),
        )
        minecraftServerPacketConnection.requestFlush()
        minecraftClientPacketConnection.incoming.receive()
        minecraftClientPacketConnection.outgoing.send(ServerboundLoginAcknowledgedPacket)
        minecraftClientPacketConnection.requestFlush()
        minecraftServerPacketConnection.incoming.receive()
        minecraftClientPacketConnection.awaitState(ConnectionState.CONFIGURATION)
        minecraftServerPacketConnection.awaitState(ConnectionState.CONFIGURATION)
    }

    private suspend fun enterPlayFromConfiguration(
        minecraftClientPacketConnection: MinecraftClientPacketConnection,
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        minecraftServerPacketConnection.outgoing.send(ClientboundFinishConfigurationPacket)
        minecraftServerPacketConnection.requestFlush()
        minecraftClientPacketConnection.incoming.receive()
        minecraftClientPacketConnection.outgoing.send(ServerboundFinishConfigurationPacket)
        minecraftClientPacketConnection.requestFlush()
        minecraftServerPacketConnection.incoming.receive()
        minecraftClientPacketConnection.awaitState(ConnectionState.PLAY)
        minecraftServerPacketConnection.awaitState(ConnectionState.PLAY)
    }

    private suspend fun enterConfiguration(
        minecraftClientPacketSession: MinecraftClientPacketSession,
        minecraftServerPacketConnection: MinecraftServerPacketConnection,
    ) {
        val clientIntentionPacket = ClientIntentionPacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            hostName = "localhost",
            port = 25_565,
            intention = ClientIntent.LOGIN,
        )
        minecraftClientPacketSession.send(clientIntentionPacket)
        assertEquals(clientIntentionPacket, minecraftServerPacketConnection.incoming.receive())
        val serverboundHelloPacket = ServerboundHelloPacket("SessionProbe", Uuid.fromLongs(1, 2))
        minecraftClientPacketSession.send(serverboundHelloPacket)
        assertEquals(serverboundHelloPacket, minecraftServerPacketConnection.incoming.receive())
        minecraftServerPacketConnection.outgoing.send(
            ClientboundLoginFinishedPacket(
                GameProfile(Uuid.fromLongs(1, 2), "SessionProbe", emptyList()),
                sessionId = Uuid.fromLongs(3, 4),
            ),
        )
        minecraftServerPacketConnection.requestFlush()
        minecraftClientPacketSession.receive()
        minecraftClientPacketSession.send(ServerboundLoginAcknowledgedPacket)
        assertEquals(ServerboundLoginAcknowledgedPacket, minecraftServerPacketConnection.incoming.receive())
        minecraftClientPacketSession.awaitState(ConnectionState.CONFIGURATION)
        minecraftServerPacketConnection.awaitState(ConnectionState.CONFIGURATION)
    }

    private fun enginePair(
        minecraftConnectionDefinition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
    ): Pair<
            MinecraftClientPacketConnection,
            MinecraftServerPacketConnection,
            > {
        val (client, server) = enginePairWithFrames(minecraftConnectionDefinition)
        return client to server
    }

    private fun enginePairWithFrames(
        minecraftConnectionDefinition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
    ): EnginePair {
        val clientToServer = ByteChannel()
        val serverToClient = ByteChannel()
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val serverFrames = MinecraftFrameStream(clientToServer, serverToClient)
        val client = createMinecraftClientPacketConnection(
            minecraftFrameStream = clientFrames,
            closeTransport = { clientFrames.cancel() },
            minecraftConnectionDefinition = minecraftConnectionDefinition,
        )
        val server = createMinecraftServerPacketConnection(
            minecraftFrameStream = serverFrames,
            closeTransport = { serverFrames.cancel() },
            minecraftConnectionDefinition = minecraftConnectionDefinition,
        )
        return EnginePair(client, server, clientFrames, serverFrames)
    }

    private fun controlledServerPair(
        coroutineDispatcher: CoroutineDispatcher,
        gateFlushes: Boolean = false,
    ): ControlledServerPair {
        val clientToServer = ByteChannel(autoFlush = true)
        val serverToClient = ByteChannel(autoFlush = true)
        val clientFrames = MinecraftFrameStream(serverToClient, clientToServer)
        val gatedOutput = if (gateFlushes) GatedFlushByteWriteChannel(serverToClient) else null
        val serverFrames = MinecraftFrameStream(clientToServer, gatedOutput ?: serverToClient)
        val minecraftServerPacketConnection = createMinecraftServerPacketConnection(
            minecraftFrameStream = serverFrames,
            closeTransport = { serverFrames.cancel() },
            minecraftConnectionDefinition = MinecraftConnectionDefinition(),
            connectionDispatcher = coroutineDispatcher,
        )
        return ControlledServerPair(
            client = MinecraftClientPacketSession(clientFrames),
            server = minecraftServerPacketConnection,
            clientFrames = clientFrames,
            gatedOutput = gatedOutput,
        )
    }

    private fun drainingClient(coroutineDispatcher: CoroutineDispatcher): DrainingClient {
        val clientToServer = ByteChannel()
        val clientFrames = MinecraftFrameStream(ByteChannel(), clientToServer)
        val serverFrames = MinecraftFrameStream(clientToServer, ByteChannel())
        val minecraftClientPacketConnection = createMinecraftClientPacketConnection(
            minecraftFrameStream = clientFrames,
            closeTransport = {},
            minecraftConnectionDefinition = MinecraftConnectionDefinition(),
            connectionDispatcher = coroutineDispatcher,
        )
        return DrainingClient(
            client = minecraftClientPacketConnection,
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
