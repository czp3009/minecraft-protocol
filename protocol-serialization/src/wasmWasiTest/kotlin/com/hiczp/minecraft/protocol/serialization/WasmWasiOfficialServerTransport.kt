package com.hiczp.minecraft.protocol.serialization

internal actual suspend fun openOfficialServerTransport(
    port: Int,
): OfficialServerTransport = error(
    "Wasm/WASI has no network transport for official-peer tests",
)
