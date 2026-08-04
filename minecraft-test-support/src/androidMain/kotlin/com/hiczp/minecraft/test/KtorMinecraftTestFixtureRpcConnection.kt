package com.hiczp.minecraft.test

import io.ktor.client.*
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

internal actual fun openMinecraftTestFixtureRpcConnection(
    rpcUrl: String,
): MinecraftTestFixtureRpcConnection {
    val client = HttpClient {
        installKrpc {
            serialization {
                json()
            }
        }
    }
    return try {
        val rpcClient = client.rpc(rpcUrl)
        object : MinecraftTestFixtureRpcConnection {
            override val service: MinecraftTestFixtureRpc =
                rpcClient.withService()

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
