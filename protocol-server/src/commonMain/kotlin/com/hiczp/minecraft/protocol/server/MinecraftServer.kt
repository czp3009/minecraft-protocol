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
                clientIpAddress = (clientSocket.remoteAddress as? InetSocketAddress)
                    ?.numericHostAddress(),
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

internal fun InetSocketAddress.numericHostAddress(): String =
    resolveAddress()?.toNumericIpAddress() ?: hostname

internal fun ByteArray.toNumericIpAddress(): String =
    when (size) {
        4 -> joinToString(".") { byte ->
            (byte.toInt() and 0xFF).toString()
        }

        16 -> asList().chunked(2).joinToString(":") { pair ->
            (
                    (pair[0].toInt() and 0xFF) shl 8 or
                            (pair[1].toInt() and 0xFF)
                    ).toString(16)
        }

        else -> throw IllegalArgumentException(
            "IP addresses must contain 4 or 16 bytes",
        )
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
