package com.hiczp.minecraft.protocol.transport

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class KtorSocketTransportTest {
    @Test
    fun exchangesEncryptedCompressedFramesOverARealTcpSocket() = runTest {
        if (
            (PlatformUtils.IS_JS || PlatformUtils.IS_WASM_JS) &&
            !PlatformUtils.IS_NODE
        ) {
            return@runTest
        }
        runKtorSocketTransportScenario()
    }
}

private suspend fun runKtorSocketTransportScenario() = coroutineScope {
    SelectorManager(Dispatchers.Default).use { selectorManager ->
        aSocket(selectorManager).tcp().bind("127.0.0.1", 0).use { listener ->
            val server = async {
                MinecraftTransport(listener.accept())
            }
            val client = MinecraftTransport(
                aSocket(selectorManager).tcp().connect("127.0.0.1", listener.port),
            )
            val accepted = server.await()
            val secret = ByteArray(16) { (it * 11).toByte() }
            client.minecraftFrameStream.configureCompression(32)
            accepted.minecraftFrameStream.configureCompression(32)
            client.minecraftFrameStream.enableEncryption(secret)
            accepted.minecraftFrameStream.enableEncryption(secret)
            val request = ByteArray(32_768) { (it * 23).toByte() }
            val response = "transport-ok".encodeToByteArray()

            client.minecraftFrameStream.sendPacketData(request)
            client.minecraftFrameStream.flush()
            assertContentEquals(request, accepted.minecraftFrameStream.receivePacketData())
            accepted.minecraftFrameStream.sendPacketData(response)
            accepted.minecraftFrameStream.flush()
            assertContentEquals(response, client.minecraftFrameStream.receivePacketData())

            client.close()
            accepted.close()
        }
    }
}
