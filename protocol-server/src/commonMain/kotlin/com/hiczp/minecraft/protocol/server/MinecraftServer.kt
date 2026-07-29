package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.session.MinecraftSession
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftTransport
import com.hiczp.minecraft.protocol.transport.MinecraftTransportConfiguration
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*

class MinecraftServer private constructor(
    val socket: ServerSocket,
    val configuration: MinecraftServerConfiguration,
    val handler: MinecraftServerHandler,
    private val transportConfiguration: MinecraftTransportConfiguration,
) : Closeable {
    val port: Int
        get() = socket.port

    suspend fun accept(): MinecraftServerConnection {
        val clientSocket = socket.accept()
        val transport = MinecraftTransport(clientSocket, transportConfiguration)
        val session = MinecraftSession(
            frames = transport.frames,
            side = MinecraftSessionSide.SERVER,
        )
        return MinecraftServerConnection(
            socket = clientSocket,
            transport = transport,
            protocol = MinecraftServerProtocol(
                session = session,
                configuration = configuration,
                handler = handler,
            ),
        )
    }

    override fun close() {
        socket.close()
    }

    companion object {
        suspend fun bind(
            selectorManager: SelectorManager,
            host: String = "0.0.0.0",
            port: Int = MinecraftServerConnection.DEFAULT_PORT,
            configuration: MinecraftServerConfiguration =
                MinecraftServerConfiguration(),
            handler: MinecraftServerHandler = DefaultMinecraftServerHandler,
            transportConfiguration: MinecraftTransportConfiguration =
                MinecraftTransportConfiguration(),
        ): MinecraftServer =
            MinecraftServer(
                socket = aSocket(selectorManager).tcp().bind(host, port),
                configuration = configuration,
                handler = handler,
                transportConfiguration = transportConfiguration,
            )
    }
}

class MinecraftServerConnection internal constructor(
    val socket: Socket,
    val transport: MinecraftTransport,
    val protocol: MinecraftServerProtocol,
) : Closeable {
    val session: MinecraftSession
        get() = protocol.session

    suspend fun negotiate(): MinecraftServerNegotiationResult =
        protocol.negotiate()

    override fun close() {
        transport.close()
    }

    companion object {
        const val DEFAULT_PORT: Int = 25_565
    }
}
