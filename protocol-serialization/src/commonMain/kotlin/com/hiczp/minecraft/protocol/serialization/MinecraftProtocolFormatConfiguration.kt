package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext

data class MinecraftProtocolFormatConfiguration(
    /** Reject boolean bytes other than exactly 0 and 1. */
    val strictBooleans: Boolean = true,
    /** Reject non-minimal VarInt and VarLong encodings permitted but discouraged by the Wiki. */
    val rejectNonMinimalVarNumbers: Boolean = false,
    /** General allocation guard for protocol-controlled collection lengths. */
    val maximumCollectionSize: Int = 1_048_576,
    /** General allocation guard for protocol-controlled byte arrays. */
    val maximumByteArraySize: Int = 16 * 1_048_576,
    /** Recursion guard for untrusted NBT. */
    val maximumNbtDepth: Int = 512,
    /** Immutable, connection-specific registry and active-dimension context. */
    val registries: ProtocolRegistryContext = ProtocolRegistryContext.Empty,
) {
    init {
        require(maximumCollectionSize >= 0)
        require(maximumByteArraySize >= 0)
        require(maximumNbtDepth >= 0)
    }

    val chunkSectionCount: Int?
        get() = registries.chunkSectionCount

    val blockStateRegistrySize: Int?
        get() = registries.blockStateRegistrySize.takeIf { it > 0 }

    val biomeRegistrySize: Int?
        get() = registries.biomeRegistrySize

    fun requireBlockStateRegistrySize(): Int =
        blockStateRegistrySize ?: throw MinecraftSerializationException(
            "Block-state palette encoding requires a ProtocolRegistryContext with block states",
        )

    fun requireBiomeRegistrySize(): Int =
        biomeRegistrySize ?: throw MinecraftSerializationException(
            "Biome palette encoding requires a ProtocolRegistryContext with the biome registry",
        )
}
