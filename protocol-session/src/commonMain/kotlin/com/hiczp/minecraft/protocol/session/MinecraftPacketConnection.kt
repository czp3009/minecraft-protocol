package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.PacketRouteKey
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.PacketCodecRegistration
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.selects.select

/** Public packet and wire-state surface shared by client and server wrappers. */
interface MinecraftPacketConnection<
        Incoming : Packet,
        Outgoing : Packet,
        > : Closeable {
    val incoming: ReceiveChannel<Incoming>

    /** Closing this channel drains packets already accepted by it, then closes the connection. */
    val outgoing: SendChannel<Outgoing>
    val connectionState: ConnectionState
    val protocolRegistryContext: ProtocolRegistryContext
    val declaredExtensionRoutes: Set<PacketRouteKey>
    val activeExtensionRoutes: Set<PacketRouteKey>
    val isOpen: Boolean

    /** Returns on an orderly close and throws the original wire/pump failure. */
    suspend fun awaitClosed()

    fun installProtocolRegistryContext(protocolRegistryContext: ProtocolRegistryContext)

    fun activateExtensionRoutes(routes: Set<PacketRouteKey>)

    /** Flushes buffered wire bytes on this connection's network dispatcher. */
    suspend fun flush()

    /** Requests a coalesced background flush on this connection's network dispatcher. */
    fun requestFlush()

    /** Encodes one active custom payload without writing it to the wire. */
    fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload

    /** Decodes one reassembled active custom payload using this connection's current context. */
    fun decodeCustomPayload(routedCustomPayload: RoutedCustomPayload): Incoming

    suspend fun awaitState(connectionState: ConnectionState)
}

/**
 * Shareable immutable definition for any number of connections. The caller
 * owns its packet and registry snapshots; connections retain those references
 * without rebuilding or cloning them.
 */
class MinecraftConnectionDefinition(
    val packetRegistry: PacketRegistry = MinecraftPacketRegistry,
    val minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    val incomingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    val outgoingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
) {
    val protocolRegistryContext: ProtocolRegistryContext
        get() = minecraftProtocolFormat.minecraftProtocolFormatConfiguration.protocolRegistryContext

    companion object {
        const val DEFAULT_CHANNEL_CAPACITY: Int = 16

        /** Explicit pure factory; the caller owns and may share its result. */
        fun compose(
            extensionCodecs: List<PacketCodecRegistration<out Packet>> = emptyList(),
            minecraftProtocolFormat: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
            incomingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
            outgoingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
        ): MinecraftConnectionDefinition = MinecraftConnectionDefinition(
            packetRegistry = PacketRegistry(MinecraftPacketRegistry.entries, extensionCodecs),
            minecraftProtocolFormat = minecraftProtocolFormat,
            incomingCapacity = incomingCapacity,
            outgoingCapacity = outgoingCapacity,
        )
    }
}

@RequiresOptIn(
    message = "Low-level packet connection factories are for client/server orchestration modules.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class InternalMinecraftConnectionApi

/** Packet-agnostic channel, lifetime, writer, and flush core shared by the two fixed endpoints. */
internal class MinecraftPacketConnectionCore<
        Incoming : Packet,
        Outgoing : Packet,
        >(
    private val minecraftPacketSession: MinecraftPacketSession<Incoming, Outgoing>,
    private val closeTransport: () -> Unit,
    minecraftConnectionDefinition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher,
) : MinecraftPacketConnection<Incoming, Outgoing> {
    private val connectionJob = Job()
    private val connectionScope = CoroutineScope(connectionJob + connectionDispatcher)
    private val termination = MutableStateFlow<ConnectionTermination?>(null)
    private val backgroundFlushPending = MutableStateFlow(false)
    private val flushRequests = Channel<FlushRequest>(Channel.UNLIMITED)
    private val completionValue = CompletableDeferred<Unit>()
    private val connectionOwnedOutgoingChannel = Channel<Outgoing>(Channel.RENDEZVOUS)
    private var started = false
    private lateinit var incomingHandler: suspend (Incoming) -> Unit

    override val incoming: ReceiveChannel<Incoming>
        field = Channel<Incoming>(minecraftConnectionDefinition.incomingCapacity)

    override val outgoing: SendChannel<Outgoing>
        field = Channel<Outgoing>(minecraftConnectionDefinition.outgoingCapacity)

    override val connectionState: ConnectionState
        get() = minecraftPacketSession.connectionState

    override val protocolRegistryContext: ProtocolRegistryContext
        get() = minecraftPacketSession.protocolRegistryContext

    override val declaredExtensionRoutes: Set<PacketRouteKey>
        get() = minecraftPacketSession.declaredExtensionRoutes

    override val activeExtensionRoutes: Set<PacketRouteKey>
        get() = minecraftPacketSession.activeExtensionRoutes

    override val isOpen: Boolean
        get() = termination.value == null

    internal fun start(incomingHandler: suspend (Incoming) -> Unit) {
        check(!started) {
            "Minecraft packet connection core is already started"
        }
        started = true
        this.incomingHandler = incomingHandler
        connectionScope.launch { runReader() }
        connectionScope.launch { runWriter() }
    }

    internal suspend fun publishIncoming(packet: Incoming) {
        incoming.send(packet)
    }

    internal suspend fun sendConnectionOwned(packet: Outgoing) {
        ensureOpen()
        try {
            connectionOwnedOutgoingChannel.send(packet)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            ensureOpen()
            throw cause
        }
    }

    internal fun launchTask(
        coroutineStart: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        ensureOpen()
        return connectionScope.launch(start = coroutineStart, block = block)
    }

    internal fun fail(cause: Throwable) {
        terminate(ConnectionTermination.Failed(cause))
    }

    override fun installProtocolRegistryContext(protocolRegistryContext: ProtocolRegistryContext) {
        ensureOpen()
        minecraftPacketSession.installProtocolRegistryContext(protocolRegistryContext)
    }

    override suspend fun awaitClosed() {
        completionValue.await()
    }

    override fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        ensureOpen()
        minecraftPacketSession.activateExtensionRoutes(routes)
    }

    override suspend fun flush() {
        ensureOpen()
        val flushed = CompletableDeferred<Unit>()
        try {
            flushRequests.send(FlushRequest.Await(flushed))
            select<Unit> {
                flushed.onAwait { }
                completionValue.onAwait {
                    ensureOpen()
                }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            ensureOpen()
            throw cause
        }
    }

    override fun requestFlush() {
        ensureOpen()
        if (!backgroundFlushPending.compareAndSet(expect = false, update = true)) return
        if (flushRequests.trySend(FlushRequest.Background).isFailure) {
            backgroundFlushPending.value = false
            ensureOpen()
        }
    }

    override fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload {
        ensureOpen()
        return minecraftPacketSession.encodeCustomPayload(packet)
    }

    override fun decodeCustomPayload(routedCustomPayload: RoutedCustomPayload): Incoming {
        ensureOpen()
        return minecraftPacketSession.decodeCustomPayload(routedCustomPayload)
    }

    override suspend fun awaitState(connectionState: ConnectionState) {
        ensureOpen()
        coroutineScope {
            val stateWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                minecraftPacketSession.awaitState(connectionState)
            }
            try {
                select {
                    stateWaiter.onAwait { }
                    completionValue.onAwait { ensureOpen() }
                }
            } finally {
                stateWaiter.cancel()
            }
        }
    }

    override fun close() {
        terminate(ConnectionTermination.Normal)
    }

    private suspend fun runReader() {
        try {
            while (currentCoroutineContext().isActive) {
                incomingHandler(minecraftPacketSession.receive())
            }
        } catch (cause: CancellationException) {
            handlePumpFailure(cause)
            throw cause
        } catch (cause: Throwable) {
            handlePumpFailure(cause)
        }
    }

    private suspend fun runWriter() {
        try {
            while (currentCoroutineContext().isActive) {
                if (writePendingConnectionOwnedPacket()) continue
                when (val writerAction = select {
                    connectionOwnedOutgoingChannel.onReceiveCatching { result ->
                        val packet = result.getOrNull()
                        if (packet == null) {
                            WriterAction.Stop
                        } else {
                            writeConnectionOwnedPacket(packet)
                            WriterAction.Continue
                        }
                    }
                    flushRequests.onReceive { flushRequest ->
                        processFlushRequest(flushRequest)
                    }
                    outgoing.onReceiveCatching { result ->
                        val packet = result.getOrNull()
                        if (packet == null) {
                            WriterAction.Close(result.exceptionOrNull(), flushRequired = true)
                        } else {
                            minecraftPacketSession.send(packet)
                            WriterAction.Continue
                        }
                    }
                }) {
                    WriterAction.Continue -> Unit
                    WriterAction.Stop -> return
                    is WriterAction.Close -> {
                        finishWriter(writerAction.cause, writerAction.flushRequired)
                        return
                    }
                }
            }
        } catch (cause: CancellationException) {
            handlePumpFailure(cause)
            throw cause
        } catch (cause: Throwable) {
            handlePumpFailure(cause)
        }
    }

    private suspend fun processFlushRequest(first: FlushRequest): WriterAction {
        val waiters = mutableListOf<CompletableDeferred<Unit>>()
        try {
            recordFlushRequest(first, waiters)
            while (true) {
                if (writePendingConnectionOwnedPacket()) continue
                val result = outgoing.tryReceive()
                val packet = result.getOrNull()
                if (packet != null) {
                    minecraftPacketSession.send(packet)
                    continue
                }
                val closeCause = result.exceptionOrNull()
                while (true) {
                    val flushRequest = flushRequests.tryReceive().getOrNull() ?: break
                    recordFlushRequest(flushRequest, waiters)
                }
                if (writePendingConnectionOwnedPacket()) continue
                if (result.isClosed && closeCause != null) {
                    try {
                        minecraftPacketSession.minecraftFrameStream.flush()
                    } catch (flushFailure: Throwable) {
                        closeCause.addSuppressed(flushFailure)
                    }
                    waiters.forEach { it.completeExceptionally(closeCause) }
                    return WriterAction.Close(closeCause, flushRequired = false)
                }
                minecraftPacketSession.minecraftFrameStream.flush()
                waiters.forEach { it.complete(Unit) }
                return if (result.isClosed) {
                    WriterAction.Close(null, flushRequired = false)
                } else {
                    WriterAction.Continue
                }
            }
        } catch (cause: Throwable) {
            waiters.forEach { it.completeExceptionally(cause) }
            throw cause
        }
    }

    private suspend fun finishWriter(
        closeCause: Throwable?,
        flushRequired: Boolean,
    ) {
        var failure = closeCause
        if (flushRequired) {
            try {
                minecraftPacketSession.minecraftFrameStream.flush()
            } catch (flushFailure: Throwable) {
                failure = combineFailures(failure, flushFailure)
            }
        }
        completeQueuedFlushRequests(failure)
        terminate(failure?.let(ConnectionTermination::Failed) ?: ConnectionTermination.Normal)
    }

    private suspend fun writeConnectionOwnedPacket(packet: Outgoing) {
        minecraftPacketSession.send(packet)
        minecraftPacketSession.minecraftFrameStream.flush()
    }

    private suspend fun writePendingConnectionOwnedPacket(): Boolean {
        val packet = connectionOwnedOutgoingChannel.tryReceive().getOrNull() ?: return false
        writeConnectionOwnedPacket(packet)
        return true
    }

    private fun recordFlushRequest(
        flushRequest: FlushRequest,
        waiters: MutableList<CompletableDeferred<Unit>>,
    ) {
        when (flushRequest) {
            FlushRequest.Background -> backgroundFlushPending.value = false
            is FlushRequest.Await -> waiters += flushRequest.completion
        }
    }

    private fun handlePumpFailure(cause: Throwable) {
        if (
            cause is CancellationException &&
            termination.value != null
        ) {
            return
        }
        terminate(ConnectionTermination.Failed(cause))
    }

    private fun terminate(connectionTermination: ConnectionTermination) {
        val requestedCause = (connectionTermination as? ConnectionTermination.Failed)?.cause
        if (!termination.compareAndSet(null, ConnectionTermination.Closing(requestedCause))) return
        connectionJob.cancel(
            CancellationException(
                "Minecraft connection closed",
                requestedCause,
            ),
        )
        minecraftPacketSession.clearSensitiveState()
        var failure = requestedCause
        try {
            closeTransport()
        } catch (closeFailure: Throwable) {
            failure = combineFailures(failure, closeFailure)
        }
        val finalValue = failure?.let(ConnectionTermination::Failed) ?: ConnectionTermination.Normal
        termination.value = finalValue
        incoming.close(failure)
        outgoing.close(failure)
        connectionOwnedOutgoingChannel.close(failure)
        flushRequests.close(failure)
        drainChannel(incoming)
        drainChannel(outgoing)
        drainChannel(connectionOwnedOutgoingChannel)
        failQueuedFlushRequests(failure)
        if (failure == null) {
            completionValue.complete(Unit)
        } else {
            completionValue.completeExceptionally(failure)
        }
    }

    private fun completeQueuedFlushRequests(failure: Throwable?) {
        while (true) {
            val flushRequest = flushRequests.tryReceive().getOrNull() ?: return
            when (flushRequest) {
                FlushRequest.Background -> backgroundFlushPending.value = false
                is FlushRequest.Await -> if (failure == null) {
                    flushRequest.completion.complete(Unit)
                } else {
                    flushRequest.completion.completeExceptionally(failure)
                }
            }
        }
    }

    private fun failQueuedFlushRequests(failure: Throwable?) {
        val cause = failure ?: MinecraftSessionException("Minecraft connection is closed")
        completeQueuedFlushRequests(cause)
    }

    internal fun ensureOpen() {
        when (val terminal = termination.value) {
            null -> Unit
            is ConnectionTermination.Closing ->
                throw terminal.cause ?: MinecraftSessionException("Minecraft connection is closed")

            ConnectionTermination.Normal ->
                throw MinecraftSessionException("Minecraft connection is closed")

            is ConnectionTermination.Failed ->
                throw terminal.cause
        }
    }

    private sealed interface ConnectionTermination {
        data class Closing(val cause: Throwable?) : ConnectionTermination

        data object Normal : ConnectionTermination

        data class Failed(val cause: Throwable) : ConnectionTermination
    }

    private sealed interface FlushRequest {
        data object Background : FlushRequest

        data class Await(
            val completion: CompletableDeferred<Unit>,
        ) : FlushRequest
    }

    private sealed interface WriterAction {
        data object Continue : WriterAction

        data object Stop : WriterAction

        data class Close(
            val cause: Throwable?,
            val flushRequired: Boolean,
        ) : WriterAction
    }
}

private fun combineFailures(
    primary: Throwable?,
    secondary: Throwable,
): Throwable {
    if (primary == null) return secondary
    if (primary !== secondary) primary.addSuppressed(secondary)
    return primary
}

private fun <T> drainChannel(receiveChannel: ReceiveChannel<T>) {
    while (receiveChannel.tryReceive().isSuccess) {
        // Drop retained packets after the pumps have stopped.
    }
}
