package com.hiczp.minecraft.protocol.serialization.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

internal fun minecraftJson(serializersModule: SerializersModule): Json = Json {
    this.serializersModule = serializersModule
    explicitNulls = false
    ignoreUnknownKeys = true
}
