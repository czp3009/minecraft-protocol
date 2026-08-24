@file:OptIn(com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi::class)

package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketConnection
import com.hiczp.minecraft.protocol.session.createMinecraftServerPacketConnection
import com.hiczp.minecraft.protocol.transport.MinecraftTransport
import com.hiczp.minecraft.protocol.transport.MinecraftTransportConfiguration
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.ContinuationInterceptor

class MinecraftServer private constructor(
    private val socket: ServerSocket,
    private val definition: MinecraftConnectionDefinition,
    private val authentication: MinecraftServerAuthentication,
    private val transportConfiguration: MinecraftTransportConfiguration,
    private val connectionDispatcher: CoroutineDispatcher,
) : Closeable {
    val port: Int
        get() = socket.port

    val isOpen: Boolean
        get() = !socket.isClosed

    suspend fun accept(): MinecraftServerConnection {
        val clientSocket = socket.accept()
        val transport = MinecraftTransport(clientSocket, transportConfiguration)
        return MinecraftServerConnection(
            connection = createMinecraftServerPacketConnection(
                frameStream = transport.frameStream,
                closeTransport = transport::close,
                definition = definition,
                connectionDispatcher = connectionDispatcher,
            ),
            authentication = authentication,
            clientIpAddress = (clientSocket.remoteAddress as? InetSocketAddress)
                ?.numericHostAddress(),
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
            definition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
            authentication: MinecraftServerAuthentication = MinecraftServerAuthentication.Offline,
            transportConfiguration: MinecraftTransportConfiguration = MinecraftTransportConfiguration(
                validateCompressionThreshold = true,
            ),
            connectionDispatcher: CoroutineDispatcher = selectorManager.connectionDispatcher,
        ): MinecraftServer = MinecraftServer(
            socket = aSocket(selectorManager).tcp().bind(host, port),
            definition = definition,
            authentication = authentication,
            transportConfiguration = transportConfiguration,
            connectionDispatcher = connectionDispatcher,
        )
    }
}

private val SelectorManager.connectionDispatcher: CoroutineDispatcher
    get() = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default

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
    private val connection: MinecraftServerPacketConnection,
    val authentication: MinecraftServerAuthentication,
    val clientIpAddress: String?,
) : MinecraftServerPacketConnection by connection {
    companion object {
        const val DEFAULT_PORT: Int = 25565
    }
}
