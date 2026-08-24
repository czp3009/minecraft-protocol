package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Server endpoint contract: receives serverbound packets and sends clientbound packets. */
interface MinecraftServerPacketConnection : MinecraftPacketConnection<ServerboundPacket, ClientboundPacket> {
    /** Enables encryption after the complete Encryption Response has been received. */
    fun enableEncryption(sharedSecret: ByteArray)
}

/** Creates the low-level server endpoint used by server orchestration modules. */
@InternalMinecraftConnectionApi
fun createMinecraftServerPacketConnection(
    frameStream: MinecraftFrameStream,
    closeTransport: () -> Unit,
    definition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): MinecraftServerPacketConnection = MinecraftServerConnectionEngine(
    serverSession = MinecraftServerPacketSession(
        frameStream = frameStream,
        packetRegistry = definition.packetRegistry,
        format = definition.format,
    ),
    closeTransport = closeTransport,
    definition = definition,
    connectionDispatcher = connectionDispatcher,
)

private class MinecraftServerConnectionEngine(
    private val serverSession: MinecraftServerPacketSession,
    closeTransport: () -> Unit,
    definition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher,
) : MinecraftConnectionEngine<ServerboundPacket, ClientboundPacket>(
    session = serverSession,
    closeTransport = closeTransport,
    definition = definition,
    connectionDispatcher = connectionDispatcher,
), MinecraftServerPacketConnection {
    private val inboundEncryptionActivation = CompletableDeferred<Unit>()

    init {
        start()
    }

    override suspend fun afterIncomingPacket(packet: ServerboundPacket) {
        if (packet is EncryptionResponsePacket) inboundEncryptionActivation.await()
    }

    override suspend fun writeOutgoingPacket(packet: ClientboundPacket) {
        if (packet is ClientboundBundlePacket) {
            sendClientboundPacket(BundleDelimiterPacket)
            packet.forEach { subPacket -> sendClientboundPacket(subPacket) }
            sendClientboundPacket(BundleDelimiterPacket)
        } else {
            sendClientboundPacket(packet)
        }
    }

    private suspend fun sendClientboundPacket(packet: ClientboundPacket) {
        try {
            sendPacket(packet)
        } catch (_: SkippablePacketEncodingException) {
            // Vanilla omits this small, explicit packet set when payload encoding fails.
        }
    }

    override fun enableEncryption(sharedSecret: ByteArray) {
        ensureOpen()
        serverSession.enableEncryption(sharedSecret)
        inboundEncryptionActivation.complete(Unit)
    }
}
