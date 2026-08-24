package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Client endpoint contract: receives clientbound packets and sends serverbound packets. */
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
): MinecraftClientPacketConnection = MinecraftClientConnectionEngine(
    clientSession = MinecraftClientPacketSession(
        frameStream = frameStream,
        packetRegistry = definition.packetRegistry,
        format = definition.format,
    ),
    closeTransport = closeTransport,
    definition = definition,
    connectionDispatcher = connectionDispatcher,
)

private class MinecraftClientConnectionEngine(
    private val clientSession: MinecraftClientPacketSession,
    closeTransport: () -> Unit,
    definition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher,
) : MinecraftConnectionEngine<ClientboundPacket, ServerboundPacket>(
    session = clientSession,
    closeTransport = closeTransport,
    definition = definition,
    connectionDispatcher = connectionDispatcher,
), MinecraftClientPacketConnection {
    private val initialPlayContext = CompletableDeferred<Unit>()
    private var bundledPackets: MutableList<ClientboundPacket>? = null

    init {
        start()
    }

    override suspend fun receiveIncomingPacket(): ClientboundPacket {
        while (true) {
            val packet = clientSession.receive()
            val currentBundle = bundledPackets
            when {
                packet === BundleDelimiterPacket && currentBundle == null -> bundledPackets = mutableListOf()
                packet === BundleDelimiterPacket -> {
                    bundledPackets = null
                    return ClientboundBundlePacket(checkNotNull(currentBundle))
                }

                currentBundle != null -> {
                    if (currentBundle.size == ClientboundBundlePacket.MAX_SUB_PACKET_COUNT) {
                        val maximum = ClientboundBundlePacket.MAX_SUB_PACKET_COUNT
                        throw MinecraftSessionException("A clientbound bundle exceeds $maximum packets")
                    }
                    currentBundle += packet
                }

                else -> return packet
            }
        }
    }

    override suspend fun afterIncomingPacket(packet: ClientboundPacket) {
        if (packet is PlayLoginPacket) initialPlayContext.await()
    }

    override fun registryContextInstalled(context: ProtocolRegistryContext) {
        if (context.chunkSectionCount != null) initialPlayContext.complete(Unit)
    }

    override fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        ensureOpen()
        clientSession.prepareOutboundEncryption(sharedSecret)
    }
}
