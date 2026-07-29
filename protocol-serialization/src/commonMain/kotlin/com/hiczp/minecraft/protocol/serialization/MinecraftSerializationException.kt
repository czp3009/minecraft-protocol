package com.hiczp.minecraft.protocol.serialization

import kotlinx.serialization.SerializationException

class MinecraftSerializationException(
    message: String,
    cause: Throwable? = null,
) : SerializationException(message, cause)
