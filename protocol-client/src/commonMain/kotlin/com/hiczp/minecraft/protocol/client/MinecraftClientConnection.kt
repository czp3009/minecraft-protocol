package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.session.MinecraftSession
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftTransport
import com.hiczp.minecraft.protocol.transport.MinecraftTransportConfiguration
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*

class MinecraftClientConnection private constructor(
    val socket: Socket,
    val transport: MinecraftTransport,
    val protocol: MinecraftClientProtocol,
) : Closeable {
    val session: MinecraftSession
        get() = protocol.session

    override fun close() {
        transport.close()
    }

    companion object {
        suspend fun connect(
            selectorManager: SelectorManager,
            host: String,
            port: Int = DEFAULT_PORT,
            transportConfiguration: MinecraftTransportConfiguration =
                MinecraftTransportConfiguration(),
        ): MinecraftClientConnection {
            val socket = aSocket(selectorManager).tcp().connect(host, port)
            val transport = MinecraftTransport(socket, transportConfiguration)
            val session = MinecraftSession(
                frames = transport.frames,
                side = MinecraftSessionSide.CLIENT,
            )
            return MinecraftClientConnection(
                socket = socket,
                transport = transport,
                protocol = MinecraftClientProtocol(session, host, port),
            )
        }

        const val DEFAULT_PORT: Int = 25_565
    }
}
