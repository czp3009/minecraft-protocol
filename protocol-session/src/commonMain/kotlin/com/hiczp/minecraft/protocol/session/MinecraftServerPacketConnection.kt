package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Server endpoint contract: receives serverbound packets and sends clientbound packets. */
interface MinecraftServerPacketConnection : MinecraftPacketConnection<ServerboundPacket, ClientboundPacket> {
    /** Enables encryption after the complete Encryption Response has been received. */
    fun enableEncryption(sharedSecret: ByteArray)

    /**
     * Starts a fresh server-managed KeepAlive run, replacing any active run.
     * Use [enableConfigurationKeepAlive] or [enablePlayKeepAlive] for the official protocol.
     * [extractChallenge] returns null for packets outside this mapping; [createRequest] places the generated challenge
     * in its clientbound request.
     */
    fun enableKeepAlive(
        extractChallenge: (ServerboundPacket) -> Long?,
        createRequest: (Long) -> ClientboundPacket,
        interval: Duration = DEFAULT_KEEP_ALIVE_INTERVAL,
    )

    /** Stops the active KeepAlive run and clears its pending challenge. */
    fun disableKeepAlive()

    companion object {
        /** The interval used by the matching official dedicated server. */
        val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 15.seconds
    }
}

/** Creates the low-level server endpoint used by server orchestration modules. */
@InternalMinecraftConnectionApi
fun createMinecraftServerPacketConnection(
    minecraftFrameStream: MinecraftFrameStream,
    closeTransport: () -> Unit,
    minecraftConnectionDefinition: MinecraftConnectionDefinition,
    connectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): MinecraftServerPacketConnection {
    val minecraftServerPacketSession = MinecraftServerPacketSession(
        minecraftFrameStream = minecraftFrameStream,
        packetRegistry = minecraftConnectionDefinition.packetRegistry,
        minecraftProtocolFormat = minecraftConnectionDefinition.minecraftProtocolFormat,
    )
    val minecraftPacketConnectionCore = MinecraftPacketConnectionCore(
        minecraftPacketSession = minecraftServerPacketSession,
        closeTransport = closeTransport,
        minecraftConnectionDefinition = minecraftConnectionDefinition,
        connectionDispatcher = connectionDispatcher,
    )
    return MinecraftServerPacketConnectionImplementation(minecraftServerPacketSession, minecraftPacketConnectionCore).also { minecraftServerPacketConnectionImplementation ->
        minecraftServerPacketConnectionImplementation.start()
    }
}

private class MinecraftServerPacketConnectionImplementation(
    private val minecraftServerPacketSession: MinecraftServerPacketSession,
    private val minecraftPacketConnectionCore: MinecraftPacketConnectionCore<ServerboundPacket, ClientboundPacket>,
) : MinecraftServerPacketConnection,
    MinecraftPacketConnection<ServerboundPacket, ClientboundPacket> by minecraftPacketConnectionCore {
    private val inboundEncryptionActivation = CompletableDeferred<Unit>()
    private val minecraftServerKeepAliveController = MinecraftServerKeepAliveController(minecraftPacketConnectionCore)

    fun start() {
        minecraftPacketConnectionCore.start(::handleIncoming)
    }

    private suspend fun handleIncoming(serverboundPacket: ServerboundPacket) {
        if (minecraftServerKeepAliveController.handle(serverboundPacket)) return
        minecraftPacketConnectionCore.publishIncoming(serverboundPacket)
        if (serverboundPacket is EncryptionResponsePacket) inboundEncryptionActivation.await()
    }

    override fun enableKeepAlive(
        extractChallenge: (ServerboundPacket) -> Long?,
        createRequest: (Long) -> ClientboundPacket,
        interval: Duration,
    ) {
        minecraftServerKeepAliveController.enable(extractChallenge, createRequest, interval)
    }

    override fun disableKeepAlive() {
        minecraftServerKeepAliveController.disable()
    }

    override fun enableEncryption(sharedSecret: ByteArray) {
        minecraftPacketConnectionCore.ensureOpen()
        minecraftServerPacketSession.enableEncryption(sharedSecret)
        inboundEncryptionActivation.complete(Unit)
    }
}
