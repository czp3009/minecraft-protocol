package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext

data class MinecraftProtocolFormatConfiguration(
    /** Reject boolean bytes other than exactly 0 and 1. */
    val strictBooleans: Boolean = false,
    /** Reject non-minimal VarInt and VarLong encodings permitted but discouraged by the Wiki. */
    val rejectNonMinimalVarNumbers: Boolean = false,
    /** Immutable, connection-specific registry and active-dimension context. */
    val protocolRegistryContext: ProtocolRegistryContext = ProtocolRegistryContext.Empty,
) {
    val chunkSectionCount: Int?
        get() = protocolRegistryContext.chunkSectionCount

    val blockStateRegistrySize: Int?
        get() = protocolRegistryContext.blockStateRegistrySize.takeIf { it > 0 }

    val biomeRegistrySize: Int?
        get() = protocolRegistryContext.biomeRegistrySize

    fun requireBlockStateRegistrySize(): Int =
        blockStateRegistrySize ?: throw MinecraftSerializationException(
            "Block-state palette encoding requires a ProtocolRegistryContext with block states",
        )

    fun requireBiomeRegistrySize(): Int =
        biomeRegistrySize ?: throw MinecraftSerializationException(
            "Biome palette encoding requires a ProtocolRegistryContext with the biome registry",
        )
}
