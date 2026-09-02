package com.hiczp.minecraft.protocol.model.packet

/**
 * One logical clientbound Play bundle.
 *
 * It has no packet ID of its own. The session layer expands it to a delimiter, [subPackets], and a closing delimiter,
 * and reconstructs the same logical value on receive.
 */
data class ClientboundBundlePacket(
    val subPackets: List<ClientboundPacket>,
) : PlayStatePacket, ClientboundPacket, Iterable<ClientboundPacket> {
    constructor(subPackets: Collection<ClientboundPacket>) : this(
        if (subPackets is List) subPackets else subPackets.toList(),
    )

    val size: Int
        get() = subPackets.size

    val isEmpty: Boolean
        get() = subPackets.isEmpty()

    override fun iterator(): Iterator<ClientboundPacket> = subPackets.iterator()

    companion object {
        const val MAX_SUB_PACKET_COUNT: Int = 4_096
    }
}
