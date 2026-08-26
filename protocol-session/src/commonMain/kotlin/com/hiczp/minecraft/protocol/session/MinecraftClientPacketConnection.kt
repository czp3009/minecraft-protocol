package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Client endpoint contract: receives clientbound packets and sends serverbound packets.
 * Direct official Configuration and Play KeepAlive requests are answered and consumed internally.
 */
interface MinecraftClientPacketConnection : MinecraftPacketConnection<ClientboundPacket, ServerboundPacket> {
    /** Arms encryption for the wire boundary immediately after Encryption Response. */
    fun prepareOutboundEncryption(sharedSecret: ByteArray)
}

/** Creates the low-level client endpoint used by client orchestration modules. */
@InternalMinecraftConnectionApi
fun createMinecraftClientPacketConnection(
    frameStream: MinecraftFrameStream,
    closeTransport: () -> Unit,
    definition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): MinecraftClientPacketConnection {
    val clientSession = MinecraftClientPacketSession(
        frameStream = frameStream,
        packetRegistry = definition.packetRegistry,
        format = definition.format,
    )
    val core = MinecraftPacketConnectionCore(
        session = clientSession,
        closeTransport = closeTransport,
        definition = definition,
        connectionDispatcher = connectionDispatcher,
    )
    return MinecraftClientPacketConnectionImplementation(clientSession, core).also { connection ->
        connection.start()
    }
}

private class MinecraftClientPacketConnectionImplementation(
    private val clientSession: MinecraftClientPacketSession,
    private val core: MinecraftPacketConnectionCore<ClientboundPacket, ServerboundPacket>,
) : MinecraftClientPacketConnection,
    MinecraftPacketConnection<ClientboundPacket, ServerboundPacket> by core {
    private val initialPlayContext = CompletableDeferred<Unit>()

    fun start() {
        core.start(::handleIncoming)
    }

    private suspend fun handleIncoming(packet: ClientboundPacket) {
        val keepAliveResponse = when (packet) {
            is ConfigurationClientboundKeepAlivePacket -> ConfigurationServerboundKeepAlivePacket(packet.id)
            is PlayClientboundKeepAlivePacket -> PlayServerboundKeepAlivePacket(packet.id)
            else -> null
        }
        if (keepAliveResponse != null) {
            core.sendConnectionOwned(keepAliveResponse)
            return
        }
        core.publishIncoming(packet)
        if (packet is PlayLoginPacket) initialPlayContext.await()
    }

    override fun installProtocolRegistryContext(protocolRegistryContext: ProtocolRegistryContext) {
        core.installProtocolRegistryContext(protocolRegistryContext)
        if (protocolRegistryContext.chunkSectionCount != null) initialPlayContext.complete(Unit)
    }

    override fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        core.ensureOpen()
        clientSession.prepareOutboundEncryption(sharedSecret)
    }
}
