package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.serialization.*
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

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

    suspend fun installRegistryContext(context: ProtocolRegistryContext)

    suspend fun activateExtensionRoutes(routes: Set<PacketRouteKey>)

    /** Encodes one active custom payload without writing it to the wire. */
    fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload

    /** Decodes one reassembled active custom payload using this connection's current context. */
    fun decodeCustomPayload(payload: RoutedCustomPayload): Incoming

    suspend fun awaitState(state: ConnectionState)

    suspend fun prepareOutboundEncryption(sharedSecret: ByteArray)

    suspend fun enableEncryption(sharedSecret: ByteArray)
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

    init {
        require(incomingCapacity >= 0) {
            "Incoming channel capacity must be non-negative"
        }
        require(outgoingCapacity >= 0) {
            "Outgoing channel capacity must be non-negative"
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_CAPACITY: Int = 16

        /** Explicit pure factory; the caller owns and may share its result. */
        fun compose(
            extensionCodecs: List<PacketCodecRegistration<out Packet>> = emptyList(),
            registries: ProtocolRegistryContext = ProtocolRegistryContext.Empty,
            formatConfiguration: MinecraftProtocolFormatConfiguration =
                MinecraftProtocolFormatConfiguration(),
            serializersModule: SerializersModule = EmptySerializersModule(),
            incomingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
            outgoingCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
        ): MinecraftConnectionDefinition = MinecraftConnectionDefinition(
            packetRegistry = MinecraftPacketRegistry.compose(extensionCodecs),
            format = MinecraftProtocolFormat(
                configuration = formatConfiguration.copy(registries = registries),
                serializersModule = serializersModule,
            ),
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
    private val frames: MinecraftFrameStream,
    private val closeTransport: () -> Unit,
    private val side: MinecraftSessionSide,
    definition: MinecraftConnectionDefinition,
) : MinecraftPacketConnection<Incoming, Outgoing> {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.Default)
    private val incomingChannel = Channel<Incoming>(definition.incomingCapacity)
    private val outgoingChannel = Channel<Outgoing>(definition.outgoingCapacity)
    private val termination = MutableStateFlow<ConnectionTermination?>(null)
    private val completionValue = CompletableDeferred<Unit>()
    private val session = MinecraftSession(
        frames = frames,
        side = side,
        packetRegistry = definition.packetRegistry,
        format = definition.format,
    )

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
        scope.launch { runReader() }
        scope.launch { runWriter() }
    }

    override suspend fun installRegistryContext(context: ProtocolRegistryContext) {
        ensureOpen()
        session.installRegistryContext(context)
    }

    override suspend fun awaitClosed() {
        completionValue.await()
    }

    override suspend fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        ensureOpen()
        session.activateExtensionRoutes(routes)
    }

    override fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload {
        ensureOpen()
        requireOutboundType(packet)
        return session.encodeCustomPayload(packet)
    }

    override fun decodeCustomPayload(payload: RoutedCustomPayload): Incoming {
        ensureOpen()
        return requireInboundType(session.decodeCustomPayload(payload))
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

    override suspend fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        ensureOpen()
        session.prepareOutboundEncryption(sharedSecret)
    }

    override suspend fun enableEncryption(sharedSecret: ByteArray) {
        ensureOpen()
        session.enableEncryption(sharedSecret)
    }

    override fun close() {
        terminate(ConnectionTermination.Normal)
    }

    private suspend fun runReader() {
        try {
            while (currentCoroutineContext().isActive) {
                val packet = session.receive()
                incomingChannel.send(requireInboundType(packet))
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
            for (packet in outgoingChannel) {
                requireOutboundType(packet)
                session.send(packet)
            }
            terminate(ConnectionTermination.Normal)
        } catch (cause: CancellationException) {
            handlePumpFailure(cause)
            throw cause
        } catch (cause: Throwable) {
            handlePumpFailure(cause)
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

    private fun terminate(value: ConnectionTermination) {
        if (!termination.compareAndSet(null, value)) return
        val cause = (value as? ConnectionTermination.Failed)?.cause
        incomingChannel.close(cause)
        outgoingChannel.close(cause)
        frames.cancel(cause)
        closeTransport()
        rootJob.cancel(
            CancellationException(
                "Minecraft connection closed",
                cause,
            ),
        )
        if (cause == null) {
            completionValue.complete(Unit)
        } else {
            completionValue.completeExceptionally(cause)
        }
    }

    private fun ensureOpen() {
        when (val terminal = termination.value) {
            null -> Unit
            ConnectionTermination.Normal ->
                throw MinecraftSessionException("Minecraft connection is closed")

            is ConnectionTermination.Failed ->
                throw terminal.cause
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun requireInboundType(packet: Packet): Incoming {
        val valid = when (side) {
            MinecraftSessionSide.CLIENT -> packet is ClientboundPacket
            MinecraftSessionSide.SERVER -> packet is ServerboundPacket
        }
        if (!valid) {
            throw MinecraftSessionException(
                "Session $side decoded an invalid inbound ${packet::class.simpleName}",
            )
        }
        return packet as Incoming
    }

    private fun requireOutboundType(packet: Packet) {
        val valid = when (side) {
            MinecraftSessionSide.CLIENT -> packet is ServerboundPacket
            MinecraftSessionSide.SERVER -> packet is ClientboundPacket
        }
        if (!valid) {
            throw MinecraftSessionException(
                "Session $side received an invalid outbound ${packet::class.simpleName}",
            )
        }
    }

    private sealed interface ConnectionTermination {
        data object Normal : ConnectionTermination

        data class Failed(val cause: Throwable) : ConnectionTermination
    }
}
