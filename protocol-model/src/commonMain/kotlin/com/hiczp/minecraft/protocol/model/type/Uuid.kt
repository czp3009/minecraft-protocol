package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.Serializable

/**
 * A UUID represented as the two unsigned 64-bit words used by the protocol.
 * Kotlin Long preserves the bits even though its arithmetic interpretation is
 * signed.
 */
@Serializable
data class Uuid(
    val mostSignificantBits: Long,
    val leastSignificantBits: Long,
)
