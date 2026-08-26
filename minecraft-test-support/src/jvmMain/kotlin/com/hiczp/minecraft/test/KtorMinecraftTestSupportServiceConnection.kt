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
    val httpClient = HttpClient(CIO) {
        installKrpc {
            serialization {
                json()
            }
        }
    }
    return try {
        val ktorRpcClient = httpClient.rpc(rpcUrl)
        object : MinecraftTestSupportServiceConnection {
            override val minecraftTestSupportService: MinecraftTestSupportService = ktorRpcClient.withService()

            override fun close() {
                closeServiceConnection(
                    closeActions = arrayOf(
                        { ktorRpcClient.close() },
                        { httpClient.close() },
                    ),
                )
            }
        }
    } catch (failure: Throwable) {
        closeServiceConnection(failure, httpClient::close)
        throw failure
    }
}
