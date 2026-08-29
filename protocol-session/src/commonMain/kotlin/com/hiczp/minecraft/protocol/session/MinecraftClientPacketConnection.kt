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
    minecraftFrameStream: MinecraftFrameStream,
    closeTransport: () -> Unit,
    minecraftConnectionDefinition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): MinecraftClientPacketConnection {
    val minecraftClientPacketSession = MinecraftClientPacketSession(
        minecraftFrameStream = minecraftFrameStream,
        packetRegistry = minecraftConnectionDefinition.packetRegistry,
        minecraftProtocolFormat = minecraftConnectionDefinition.minecraftProtocolFormat,
    )
    val minecraftPacketConnectionCore = MinecraftPacketConnectionCore(
        minecraftPacketSession = minecraftClientPacketSession,
        closeTransport = closeTransport,
        minecraftConnectionDefinition = minecraftConnectionDefinition,
        connectionDispatcher = connectionDispatcher,
    )
    return MinecraftClientPacketConnectionImplementation(
        minecraftClientPacketSession,
        minecraftPacketConnectionCore
    ).also { minecraftClientPacketConnectionImplementation ->
        minecraftClientPacketConnectionImplementation.start()
    }
}

private class MinecraftClientPacketConnectionImplementation(
    private val minecraftClientPacketSession: MinecraftClientPacketSession,
    private val minecraftPacketConnectionCore: MinecraftPacketConnectionCore<ClientboundPacket, ServerboundPacket>,
) : MinecraftClientPacketConnection,
    MinecraftPacketConnection<ClientboundPacket, ServerboundPacket> by minecraftPacketConnectionCore {
    private val initialPlayContext = CompletableDeferred<Unit>()

    fun start() {
        minecraftPacketConnectionCore.start(::handleIncoming)
    }

    private suspend fun handleIncoming(clientboundPacket: ClientboundPacket) {
        val keepAliveResponse = when (clientboundPacket) {
            is ConfigurationClientboundKeepAlivePacket -> ConfigurationServerboundKeepAlivePacket(clientboundPacket.id)
            is PlayClientboundKeepAlivePacket -> PlayServerboundKeepAlivePacket(clientboundPacket.id)
            else -> null
        }
        if (keepAliveResponse != null) {
            minecraftPacketConnectionCore.sendConnectionOwned(keepAliveResponse)
            return
        }
        minecraftPacketConnectionCore.publishIncoming(clientboundPacket)
        if (clientboundPacket is PlayLoginPacket) initialPlayContext.await()
    }

    override fun installProtocolRegistryContext(protocolRegistryContext: ProtocolRegistryContext) {
        minecraftPacketConnectionCore.installProtocolRegistryContext(protocolRegistryContext)
        if (protocolRegistryContext.chunkSectionCount != null) initialPlayContext.complete(Unit)
    }

    override fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        minecraftPacketConnectionCore.ensureOpen()
        minecraftClientPacketSession.prepareOutboundEncryption(sharedSecret)
    }
}
