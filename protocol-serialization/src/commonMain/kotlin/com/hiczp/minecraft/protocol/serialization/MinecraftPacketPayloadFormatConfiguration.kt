package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.PacketCodecContext

data class MinecraftPacketPayloadFormatConfiguration(
    /** Reject boolean bytes other than exactly 0 and 1. */
    val strictBooleans: Boolean = false,
    /** Reject non-minimal VarInt and VarLong encodings permitted but discouraged by the Wiki. */
    val rejectNonMinimalVarNumbers: Boolean = false,
    /** Connection-specific registry and raw-ID mappings. */
    val packetCodecContext: PacketCodecContext = PacketCodecContext.Empty,
) {
    val blockStateRegistrySize: Int?
        get() = packetCodecContext.blockStateRegistrySize.takeIf { it > 0 }

    val biomeRegistrySize: Int?
        get() = packetCodecContext.biomeRegistrySize

    fun requireBlockStateRegistrySize(): Int =
        blockStateRegistrySize ?: throw MinecraftSerializationException(
            "Block-state palette encoding requires a PacketCodecContext with block states",
        )

    fun requireBiomeRegistrySize(): Int =
        biomeRegistrySize ?: throw MinecraftSerializationException(
            "Biome palette encoding requires a PacketCodecContext with the biome registry",
        )
}
