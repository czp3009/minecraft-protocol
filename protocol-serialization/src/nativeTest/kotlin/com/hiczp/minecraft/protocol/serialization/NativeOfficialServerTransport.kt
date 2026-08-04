package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.transport.MinecraftTransport
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers

internal actual suspend fun openOfficialServerTransport(
    port: Int,
): OfficialServerTransport = openKtorOfficialServerTransport(port)

private suspend fun openKtorOfficialServerTransport(
    port: Int,
): OfficialServerTransport {
    val selector = SelectorManager(Dispatchers.Default)
    return try {
        val transport = MinecraftTransport(
            aSocket(selector).tcp().connect("127.0.0.1", port),
        )
        object : OfficialServerTransport {
            override suspend fun sendPacketData(packetData: ByteArray) =
                transport.sendPacketData(packetData)

            override suspend fun receivePacketData(): ByteArray =
                transport.receivePacketData()

            override fun configureCompression(threshold: Int) {
                transport.configureCompression(threshold)
            }

            override fun close() {
                transport.close()
                selector.close()
            }
        }
    } catch (failure: Throwable) {
        selector.close()
        throw failure
    }
}
