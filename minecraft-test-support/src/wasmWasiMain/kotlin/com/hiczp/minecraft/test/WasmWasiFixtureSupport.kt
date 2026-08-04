package com.hiczp.minecraft.test

internal actual fun openMinecraftTestFixtureRpcConnection(
    rpcUrl: String,
): MinecraftTestFixtureRpcConnection =
    error("The WasmWasi runtime has no Ktor kRPC network transport")

internal actual fun platformEnvironmentVariable(name: String): String? = null
