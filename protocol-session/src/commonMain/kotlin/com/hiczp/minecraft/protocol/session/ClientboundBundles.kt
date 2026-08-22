package com.hiczp.minecraft.protocol.session

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
