package com.hiczp.minecraft.test

internal actual fun openMinecraftTestSupportServiceConnection(
    rpcUrl: String,
): MinecraftTestSupportServiceConnection =
    error("The WasmWasi runtime has no Ktor kRPC network transport")

internal actual fun platformEnvironmentVariable(name: String): String? = null
