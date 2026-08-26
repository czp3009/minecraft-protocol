package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.BundleDelimiterPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundBundlePacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel

/** Enqueues one complete logical bundle as a single outgoing-channel element. */
suspend fun SendChannel<ClientboundPacket>.sendBundle(subPackets: Collection<ClientboundPacket>) {
    send(ClientboundBundlePacket(subPackets))
}

/** Attempts to enqueue one complete logical bundle without suspending. */
fun SendChannel<ClientboundPacket>.trySendBundle(
    subPackets: Collection<ClientboundPacket>,
): ChannelResult<Unit> = trySend(ClientboundBundlePacket(subPackets))

/** Shared logical/wire boundary for the clientbound Play bundle protocol. */
internal object ClientboundBundleCodec {
    suspend fun receive(
        receivePacket: suspend () -> ClientboundPacket,
    ): ClientboundPacket {
        val first = receivePacket()
        if (first !== BundleDelimiterPacket) return first

        val subPackets = mutableListOf<ClientboundPacket>()
        while (true) {
            val packet = receivePacket()
            if (packet === BundleDelimiterPacket) return ClientboundBundlePacket(subPackets)
            requireSubPacket(packet)
            if (subPackets.size == ClientboundBundlePacket.MAX_SUB_PACKET_COUNT) {
                val maximum = ClientboundBundlePacket.MAX_SUB_PACKET_COUNT
                throw MinecraftSessionException("A clientbound bundle exceeds $maximum packets")
            }
            subPackets += packet
        }
    }

    suspend fun send(
        bundle: ClientboundBundlePacket,
        sendPacket: suspend (ClientboundPacket) -> Unit,
    ) {
        bundle.forEach(::requireSubPacket)
        sendPacket(BundleDelimiterPacket)
        bundle.forEach { packet -> sendPacket(packet) }
        sendPacket(BundleDelimiterPacket)
    }

    fun rejectStandaloneDelimiter(packet: ClientboundPacket) {
        if (packet === BundleDelimiterPacket) {
            throw MinecraftSessionException(
                "Bundle delimiters are session-owned; send a ClientboundBundlePacket instead",
            )
        }
    }

    private fun requireSubPacket(packet: ClientboundPacket) {
        if (packet === BundleDelimiterPacket || packet is ClientboundBundlePacket) {
            throw MinecraftSessionException("A clientbound bundle cannot contain delimiters or nested bundles")
        }
    }
}
