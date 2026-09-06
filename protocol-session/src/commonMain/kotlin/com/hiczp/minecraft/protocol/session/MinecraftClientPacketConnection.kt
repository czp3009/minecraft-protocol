package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.ClientboundKeepAlivePacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundKeepAlivePacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Client endpoint contract: receives clientbound packets and sends serverbound packets.
 * Direct official Configuration and Play KeepAlive requests are answered and consumed internally.
 */
interface MinecraftClientPacketConnection : MinecraftPacketConnection<ClientboundPacket, ServerboundPacket> {
    /** Arms encryption for the wire boundary immediately after Encryption Response. */
    fun prepareOutboundEncryption(sharedSecret: ByteArray)

    companion object {
        /** Creates the low-level client endpoint used by client orchestration modules. */
        @InternalMinecraftConnectionApi
        fun create(
            minecraftFrameStream: MinecraftFrameStream,
            closeTransport: () -> Unit,
            minecraftConnectionDefinition: MinecraftConnectionDefinition,
            connectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): MinecraftClientPacketConnection {
            val minecraftClientPacketSession = MinecraftClientPacketSession(
                minecraftFrameStream = minecraftFrameStream,
                packetRegistry = minecraftConnectionDefinition.packetRegistry,
                minecraftPacketPayloadFormat = minecraftConnectionDefinition.minecraftPacketPayloadFormat,
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
    }
}

private class MinecraftClientPacketConnectionImplementation(
    private val minecraftClientPacketSession: MinecraftClientPacketSession,
    private val minecraftPacketConnectionCore: MinecraftPacketConnectionCore<ClientboundPacket, ServerboundPacket>,
) : MinecraftClientPacketConnection,
    MinecraftPacketConnection<ClientboundPacket, ServerboundPacket> by minecraftPacketConnectionCore {

    fun start() {
        minecraftPacketConnectionCore.start(::handleIncoming)
    }

    private suspend fun handleIncoming(clientboundPacket: ClientboundPacket) {
        val keepAliveResponse = when (clientboundPacket) {
            is ClientboundKeepAlivePacket -> ServerboundKeepAlivePacket(clientboundPacket.id)
            else -> null
        }
        if (keepAliveResponse != null) {
            minecraftPacketConnectionCore.sendConnectionOwned(keepAliveResponse)
            return
        }
        minecraftPacketConnectionCore.publishIncoming(clientboundPacket)
    }

    override fun installPacketCodecContext(packetCodecContext: PacketCodecContext) {
        minecraftPacketConnectionCore.installPacketCodecContext(packetCodecContext)
    }

    override fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        minecraftPacketConnectionCore.ensureOpen()
        minecraftClientPacketSession.prepareOutboundEncryption(sharedSecret)
    }
}
