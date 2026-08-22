package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.PacketCodecRegistration
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
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
    val state: ConnectionState
    val registries: ProtocolRegistryContext
    val declaredExtensionRoutes: Set<PacketRouteKey>
    val activeExtensionRoutes: Set<PacketRouteKey>
    val isOpen: Boolean

    /** Returns on an orderly close and throws the original wire/pump failure. */
    suspend fun awaitClosed()

    fun installRegistryContext(context: ProtocolRegistryContext)

    fun activateExtensionRoutes(routes: Set<PacketRouteKey>)

    /** Flushes buffered wire bytes on this connection's network dispatcher. */
    suspend fun flush()

    /** Requests a coalesced background flush on this connection's network dispatcher. */
    fun requestFlush()

    /** Encodes one active custom payload without writing it to the wire. */
    fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload

    /** Decodes one reassembled active custom payload using this connection's current context. */
    fun decodeCustomPayload(payload: RoutedCustomPayload): Incoming

    suspend fun awaitState(state: ConnectionState)

    fun prepareOutboundEncryption(sharedSecret: ByteArray)

    fun enableEncryption(sharedSecret: ByteArray)
}

/**
 * Shareable immutable definition for any number of connections. The caller
 * owns its packet and registry snapshots; connections retain those references
 * without rebuilding or cloning them.
 */
class MinecraftConnectionDefinition(
    val packetRegistry: PacketRegistry = MinecraftPacketRegistry.compose(emptyList()),
    val format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
    val incomingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    val outgoingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
) {
    val registries: ProtocolRegistryContext
        get() = format.configuration.registries

    companion object {
        const val DEFAULT_CHANNEL_CAPACITY: Int = 16

        /** Explicit pure factory; the caller owns and may share its result. */
        fun compose(
            extensionCodecs: List<PacketCodecRegistration<out Packet>> = emptyList(),
            format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
            incomingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
            outgoingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
        ): MinecraftConnectionDefinition = MinecraftConnectionDefinition(
            packetRegistry = MinecraftPacketRegistry.compose(extensionCodecs),
            format = format,
            incomingCapacity = incomingCapacity,
            outgoingCapacity = outgoingCapacity,
        )
    }
}

@RequiresOptIn(
    message = "The connection engine is for client/server orchestration modules.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class InternalMinecraftConnectionApi

/** Internal bridge used by the public side-specific connection wrappers. */
@InternalMinecraftConnectionApi
class MinecraftConnectionEngine<
        Incoming : Packet,
        Outgoing : Packet,
        >(
    private val frameStream: MinecraftFrameStream,
    private val closeTransport: () -> Unit,
    private val side: MinecraftSessionSide,
    definition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MinecraftPacketConnection<Incoming, Outgoing> {
    private val connectionJob = Job()
    private val connectionScope = CoroutineScope(connectionJob + connectionDispatcher)
    private val termination = MutableStateFlow<ConnectionTermination?>(null)
    private val backgroundFlushPending = MutableStateFlow(false)
    private val flushRequests = Channel<FlushRequest>(Channel.UNLIMITED)
    private val completionValue = CompletableDeferred<Unit>()
    private val session = MinecraftSession(
        frameStream = frameStream,
        side = side,
        packetRegistry = definition.packetRegistry,
        format = definition.format,
    )
    private val incomingChannel = Channel<Incoming>(definition.incomingCapacity)
    private val outgoingChannel = Channel<Outgoing>(definition.outgoingCapacity)

    override val incoming: ReceiveChannel<Incoming>
        get() = incomingChannel

    override val outgoing: SendChannel<Outgoing>
        get() = outgoingChannel

    override val state: ConnectionState
        get() = session.state

    override val registries: ProtocolRegistryContext
        get() = session.registries

    override val declaredExtensionRoutes: Set<PacketRouteKey>
        get() = session.declaredExtensionRoutes

    override val activeExtensionRoutes: Set<PacketRouteKey>
        get() = session.activeExtensionRoutes

    override val isOpen: Boolean
        get() = termination.value == null

    init {
        connectionScope.launch { runReader() }
        connectionScope.launch { runWriter() }
    }

    override fun installRegistryContext(context: ProtocolRegistryContext) {
        ensureOpen()
        session.installRegistryContext(context)
    }

    override suspend fun awaitClosed() {
        completionValue.await()
    }

    override fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        ensureOpen()
        session.activateExtensionRoutes(routes)
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
        return session.encodeCustomPayload(packet)
    }

    override fun decodeCustomPayload(payload: RoutedCustomPayload): Incoming {
        ensureOpen()
        return asIncoming(session.decodeCustomPayload(payload))
    }

    override suspend fun awaitState(state: ConnectionState) {
        ensureOpen()
        coroutineScope {
            val stateWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                session.awaitState(state)
            }
            try {
                select {
                    stateWaiter.onAwait { Unit }
                    completionValue.onAwait { ensureOpen() }
                }
            } finally {
                stateWaiter.cancel()
            }
        }
    }

    override fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        ensureOpen()
        session.prepareOutboundEncryption(sharedSecret)
    }

    override fun enableEncryption(sharedSecret: ByteArray) {
        ensureOpen()
        session.enableEncryption(sharedSecret)
    }

    override fun close() {
        terminate(ConnectionTermination.Normal)
    }

    private suspend fun runReader() {
        var bundledPackets: MutableList<ClientboundPacket>? = null
        try {
            while (currentCoroutineContext().isActive) {
                val packet = session.receive()
                if (side == MinecraftSessionSide.CLIENT && packet is ClientboundPacket) {
                    when {
                        packet === BundleDelimiterPacket -> {
                            val currentBundle = bundledPackets
                            if (currentBundle == null) {
                                bundledPackets = mutableListOf()
                            } else {
                                bundledPackets = null
                                incomingChannel.send(asIncoming(ClientboundBundlePacket(currentBundle)))
                            }
                        }

                        bundledPackets != null -> {
                            val currentBundle = bundledPackets
                            if (currentBundle.size == ClientboundBundlePacket.MAX_SUB_PACKET_COUNT) {
                                val maximum = ClientboundBundlePacket.MAX_SUB_PACKET_COUNT
                                throw MinecraftSessionException("A clientbound bundle exceeds $maximum packets")
                            }
                            currentBundle += packet
                        }

                        else -> incomingChannel.send(asIncoming(packet))
                    }
                } else {
                    incomingChannel.send(asIncoming(packet))
                }
                if (
                    side == MinecraftSessionSide.SERVER &&
                    packet is EncryptionResponsePacket
                ) {
                    session.awaitInboundEncryptionActivation()
                }
                if (
                    side == MinecraftSessionSide.CLIENT &&
                    packet is PlayLoginPacket
                ) {
                    session.awaitInitialPlayContext()
                }
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
                when (val action = select<WriterAction> {
                    flushRequests.onReceive { request ->
                        processFlushRequest(request)
                    }
                    outgoingChannel.onReceiveCatching { result ->
                        val packet = result.getOrNull()
                        if (packet == null) {
                            WriterAction.Close(result.exceptionOrNull(), flushRequired = true)
                        } else {
                            sendOutgoingPacket(packet)
                            WriterAction.Continue
                        }
                    }
                }) {
                    WriterAction.Continue -> Unit
                    is WriterAction.Close -> {
                        finishWriter(action.cause, action.flushRequired)
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
                val result = outgoingChannel.tryReceive()
                val packet = result.getOrNull()
                if (packet != null) {
                    sendOutgoingPacket(packet)
                    continue
                }
                val closeCause = result.exceptionOrNull()
                while (true) {
                    val request = flushRequests.tryReceive().getOrNull() ?: break
                    recordFlushRequest(request, waiters)
                }
                if (result.isClosed && closeCause != null) {
                    try {
                        frameStream.flush()
                    } catch (flushFailure: Throwable) {
                        closeCause.addSuppressed(flushFailure)
                    }
                    waiters.forEach { it.completeExceptionally(closeCause) }
                    return WriterAction.Close(closeCause, flushRequired = false)
                }
                frameStream.flush()
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
                frameStream.flush()
            } catch (flushFailure: Throwable) {
                failure = combineFailures(failure, flushFailure)
            }
        }
        completeQueuedFlushRequests(failure)
        terminate(failure?.let(ConnectionTermination::Failed) ?: ConnectionTermination.Normal)
    }

    private suspend fun sendOutgoingPacket(packet: Outgoing) {
        if (packet is ClientboundBundlePacket) {
            sendPacket(BundleDelimiterPacket)
            packet.forEach { subPacket -> sendPacket(subPacket) }
            sendPacket(BundleDelimiterPacket)
        } else {
            sendPacket(packet)
        }
    }

    private suspend fun sendPacket(packet: Packet) {
        try {
            session.send(packet)
        } catch (_: SkippablePacketEncodingException) {
            // Vanilla omits this small, explicit packet set when payload encoding fails.
        }
    }

    private fun recordFlushRequest(
        request: FlushRequest,
        waiters: MutableList<CompletableDeferred<Unit>>,
    ) {
        when (request) {
            FlushRequest.Background -> backgroundFlushPending.value = false
            is FlushRequest.Await -> waiters += request.completion
        }
    }

    private fun handlePumpFailure(cause: Throwable) {
        if (
            cause is CancellationException &&
            termination.value != null
        ) {
            return
        }
        failConnection(cause)
    }

    private fun failConnection(cause: Throwable) {
        terminate(ConnectionTermination.Failed(cause))
    }

    private fun terminate(value: ConnectionTermination) {
        val requestedCause = (value as? ConnectionTermination.Failed)?.cause
        if (!termination.compareAndSet(null, ConnectionTermination.Closing(requestedCause))) return
        connectionJob.cancel(
            CancellationException(
                "Minecraft connection closed",
                requestedCause,
            ),
        )
        session.clearSensitiveState()
        var failure = requestedCause
        try {
            closeTransport()
        } catch (closeFailure: Throwable) {
            failure = combineFailures(failure, closeFailure)
        }
        val finalValue = failure?.let(ConnectionTermination::Failed) ?: ConnectionTermination.Normal
        termination.value = finalValue
        incomingChannel.close(failure)
        outgoingChannel.close(failure)
        flushRequests.close(failure)
        drainChannel(incomingChannel)
        drainChannel(outgoingChannel)
        failQueuedFlushRequests(failure)
        if (failure == null) {
            completionValue.complete(Unit)
        } else {
            completionValue.completeExceptionally(failure)
        }
    }

    private fun completeQueuedFlushRequests(failure: Throwable?) {
        while (true) {
            val request = flushRequests.tryReceive().getOrNull() ?: return
            when (request) {
                FlushRequest.Background -> backgroundFlushPending.value = false
                is FlushRequest.Await -> if (failure == null) {
                    request.completion.complete(Unit)
                } else {
                    request.completion.completeExceptionally(failure)
                }
            }
        }
    }

    private fun failQueuedFlushRequests(failure: Throwable?) {
        val cause = failure ?: MinecraftSessionException("Minecraft connection is closed")
        completeQueuedFlushRequests(cause)
    }

    private fun ensureOpen() {
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

    @Suppress("UNCHECKED_CAST")
    private fun asIncoming(packet: Packet): Incoming = packet as Incoming

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

private fun <T> drainChannel(channel: ReceiveChannel<T>) {
    while (channel.tryReceive().isSuccess) {
        // Drop retained packets after the pumps have stopped.
    }
}
