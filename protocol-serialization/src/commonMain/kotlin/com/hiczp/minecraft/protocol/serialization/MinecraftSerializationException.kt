package com.hiczp.minecraft.protocol.serialization

import kotlinx.serialization.SerializationException

/** A packet-payload format failure in the standard serialization hierarchy. */
class MinecraftSerializationException(
    message: String,
    cause: Throwable? = null,
) : SerializationException(message, cause)
