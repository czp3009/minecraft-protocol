package com.hiczp.minecraft.protocol.serialization

data class MinecraftFormatConfiguration(
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
    /**
     * Number of vertical sections in the active dimension. The chunk packet
     * does not carry this count, so decoding chunk data requires this context.
     */
    val chunkSectionCount: Int? = null,
    /** Size of the global block-state ID map used by paletted containers. */
    val blockStateRegistrySize: Int = DEFAULT_BLOCK_STATE_REGISTRY_SIZE,
    /** Size of the synchronized biome registry used by paletted containers. */
    val biomeRegistrySize: Int = DEFAULT_BIOME_REGISTRY_SIZE,
) {
    init {
        require(maximumCollectionSize >= 0)
        require(maximumByteArraySize >= 0)
        require(maximumNbtDepth >= 0)
        require(chunkSectionCount == null || chunkSectionCount >= 0)
        require(blockStateRegistrySize > 0)
        require(biomeRegistrySize > 0)
    }

    companion object {
        /** Largest block-state registry ID accepted by the selected target. */
        const val DEFAULT_BLOCK_STATE_REGISTRY_SIZE: Int = 32_366

        /** Synchronized biome registry size for the selected target. */
        const val DEFAULT_BIOME_REGISTRY_SIZE: Int = 66
    }
}
