@file:OptIn(com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi::class)

package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftConnectionEngine
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftTransport
import com.hiczp.minecraft.protocol.transport.MinecraftTransportConfiguration
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.flow.MutableStateFlow

class MinecraftServer private constructor(
    private val socket: ServerSocket,
    private val definition: MinecraftConnectionDefinition,
    private val authentication: MinecraftServerAuthentication,
    private val transportConfiguration: MinecraftTransportConfiguration,
) : Closeable {
    private val open = MutableStateFlow(true)

    val port: Int
        get() = socket.port

    val isOpen: Boolean
        get() = open.value

    suspend fun accept(): MinecraftServerConnection {
        check(isOpen) { "Minecraft server listener is closed" }
        val clientSocket = socket.accept()
        val transport = MinecraftTransport(clientSocket, transportConfiguration)
        return MinecraftServerConnection(
            connection = MinecraftConnectionEngine(
                frames = transport.frames,
                closeTransport = transport::close,
                side = MinecraftSessionSide.SERVER,
                definition = definition,
            ),
            authentication = authentication,
            clientIpAddress = (clientSocket.remoteAddress as? InetSocketAddress)
                ?.numericHostAddress(),
        )
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        socket.close()
    }

    companion object {
        suspend fun bind(
            selectorManager: SelectorManager,
            host: String = "0.0.0.0",
            port: Int = MinecraftServerConnection.DEFAULT_PORT,
            definition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
            authentication: MinecraftServerAuthentication =
                MinecraftServerAuthentication.Offline,
            transportConfiguration: MinecraftTransportConfiguration =
                MinecraftTransportConfiguration(),
        ): MinecraftServer = MinecraftServer(
            socket = aSocket(selectorManager).tcp().bind(host, port),
            definition = definition,
            authentication = authentication,
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
    private val connection: MinecraftPacketConnection<ServerboundPacket, ClientboundPacket>,
    val authentication: MinecraftServerAuthentication,
    val clientIpAddress: String?,
) : MinecraftPacketConnection<ServerboundPacket, ClientboundPacket> by connection {
    var playLogin: PlayLoginPacket? = null
        internal set

    companion object {
        const val DEFAULT_PORT: Int = 25565
    }
}
