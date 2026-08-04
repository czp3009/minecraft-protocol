package com.hiczp.minecraft.protocol.serialization

internal actual suspend fun openOfficialServerTransport(
    port: Int,
): OfficialServerTransport = error(
    "JavaScript has no Minecraft TCP transport for official-peer tests",
)
