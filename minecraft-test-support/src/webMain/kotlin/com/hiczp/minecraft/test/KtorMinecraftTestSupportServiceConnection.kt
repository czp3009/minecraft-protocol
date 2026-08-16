package com.hiczp.minecraft.test

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

internal actual fun openMinecraftTestSupportServiceConnection(
    rpcUrl: String,
): MinecraftTestSupportServiceConnection {
    val client = HttpClient(CIO) {
        installKrpc {
            serialization {
                json()
            }
        }
    }
    return try {
        val rpcClient = client.rpc(rpcUrl)
        object : MinecraftTestSupportServiceConnection {
            override val service: MinecraftTestSupportService = rpcClient.withService()

            override fun close() {
                rpcClient.close()
                client.close()
            }
        }
    } catch (failure: Throwable) {
        client.close()
        throw failure
    }
}
