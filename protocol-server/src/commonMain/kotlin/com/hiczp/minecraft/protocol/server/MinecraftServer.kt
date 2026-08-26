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
    private val serverSocket: ServerSocket,
    private val minecraftConnectionDefinition: MinecraftConnectionDefinition,
    private val minecraftServerAuthentication: MinecraftServerAuthentication,
    private val minecraftTransportConfiguration: MinecraftTransportConfiguration,
    private val connectionDispatcher: CoroutineDispatcher,
) : Closeable {
    val port: Int
        get() = serverSocket.port

    val isOpen: Boolean
        get() = !serverSocket.isClosed

    suspend fun accept(): MinecraftServerConnection {
        val clientSocket = serverSocket.accept()
        val minecraftTransport = MinecraftTransport(clientSocket, minecraftTransportConfiguration)
        return MinecraftServerConnection(
            minecraftServerPacketConnection = createMinecraftServerPacketConnection(
                minecraftFrameStream = minecraftTransport.minecraftFrameStream,
                closeTransport = minecraftTransport::close,
                minecraftConnectionDefinition = minecraftConnectionDefinition,
                connectionDispatcher = connectionDispatcher,
            ),
            minecraftServerAuthentication = minecraftServerAuthentication,
            clientIpAddress = (clientSocket.remoteAddress as? InetSocketAddress)
                ?.numericHostAddress(),
        )
    }

    override fun close() {
        serverSocket.close()
    }

    companion object {
        suspend fun bind(
            selectorManager: SelectorManager,
            host: String = "0.0.0.0",
            port: Int = MinecraftServerConnection.DEFAULT_PORT,
            minecraftConnectionDefinition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
            minecraftServerAuthentication: MinecraftServerAuthentication = MinecraftServerAuthentication.Offline,
            minecraftTransportConfiguration: MinecraftTransportConfiguration = MinecraftTransportConfiguration(
                validateCompressionThreshold = true,
            ),
            connectionDispatcher: CoroutineDispatcher = selectorManager.connectionDispatcher,
        ): MinecraftServer = MinecraftServer(
            serverSocket = aSocket(selectorManager).tcp().bind(host, port),
            minecraftConnectionDefinition = minecraftConnectionDefinition,
            minecraftServerAuthentication = minecraftServerAuthentication,
            minecraftTransportConfiguration = minecraftTransportConfiguration,
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
    private val minecraftServerPacketConnection: MinecraftServerPacketConnection,
    val minecraftServerAuthentication: MinecraftServerAuthentication,
    val clientIpAddress: String?,
) : MinecraftServerPacketConnection by minecraftServerPacketConnection {
    companion object {
        const val DEFAULT_PORT: Int = 25565
    }
}
