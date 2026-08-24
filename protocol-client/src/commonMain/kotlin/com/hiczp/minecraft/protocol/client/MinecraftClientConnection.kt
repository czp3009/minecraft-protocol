@file:OptIn(com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi::class)

package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.session.MinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.createMinecraftClientPacketConnection
import com.hiczp.minecraft.protocol.transport.MinecraftTransport
import com.hiczp.minecraft.protocol.transport.MinecraftTransportConfiguration
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.ContinuationInterceptor

class MinecraftClientConnection internal constructor(
    private val connection: MinecraftClientPacketConnection,
    val serverAddress: String,
    val serverPort: Int,
) : MinecraftClientPacketConnection by connection {
    companion object {
        suspend fun connect(
            selectorManager: SelectorManager,
            host: String,
            port: Int = DEFAULT_PORT,
            definition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
            transportConfiguration: MinecraftTransportConfiguration = MinecraftTransportConfiguration(),
            connectionDispatcher: CoroutineDispatcher = selectorManager.connectionDispatcher,
        ): MinecraftClientConnection {
            val socket = aSocket(selectorManager).tcp().connect(host, port)
            val transport = MinecraftTransport(socket, transportConfiguration)
            return MinecraftClientConnection(
                connection = createMinecraftClientPacketConnection(
                    frameStream = transport.frameStream,
                    closeTransport = transport::close,
                    definition = definition,
                    connectionDispatcher = connectionDispatcher,
                ),
                serverAddress = host,
                serverPort = port,
            )
        }

        const val DEFAULT_PORT: Int = 25565
    }
}

private val SelectorManager.connectionDispatcher: CoroutineDispatcher
    get() = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
