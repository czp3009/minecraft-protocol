package com.hiczp.minecraft.protocol.transport

data class MinecraftTransportConfiguration(
    /** Vanilla's three-byte VarInt21 frame-body ceiling. */
    val maximumFrameSize: Int = MAXIMUM_FRAME_SIZE,
    /** Vanilla's post-decompression packet-data ceiling. */
    val maximumUncompressedPacketSize: Int = MAXIMUM_UNCOMPRESSED_PACKET_SIZE,
    /** Enforce the compression threshold on untrusted peers. */
    val validateCompressionThreshold: Boolean = true,
    /** Reject longer-than-necessary frame and compression VarInts. */
    val rejectNonMinimalVarInts: Boolean = true,
) {
    init {
        require(maximumFrameSize in 1..MAXIMUM_FRAME_SIZE)
        require(
            maximumUncompressedPacketSize in
                    maximumFrameSize..MAXIMUM_UNCOMPRESSED_PACKET_SIZE,
        )
    }

    companion object {
        const val MAXIMUM_FRAME_SIZE: Int = 0x1F_FFFF
        const val MAXIMUM_UNCOMPRESSED_PACKET_SIZE: Int = 8 * 1_048_576
    }
}

class MinecraftTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
