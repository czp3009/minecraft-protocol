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
    private val minecraftClientPacketConnection: MinecraftClientPacketConnection,
    val serverAddress: String,
    val serverPort: Int,
) : MinecraftClientPacketConnection by minecraftClientPacketConnection {
    companion object {
        suspend fun connect(
            selectorManager: SelectorManager,
            host: String,
            port: Int = DEFAULT_PORT,
            minecraftConnectionDefinition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
            minecraftTransportConfiguration: MinecraftTransportConfiguration = MinecraftTransportConfiguration(),
            connectionDispatcher: CoroutineDispatcher = selectorManager.connectionDispatcher,
        ): MinecraftClientConnection {
            val socket = aSocket(selectorManager).tcp().connect(host, port)
            val minecraftTransport = MinecraftTransport(socket, minecraftTransportConfiguration)
            return MinecraftClientConnection(
                minecraftClientPacketConnection = createMinecraftClientPacketConnection(
                    minecraftFrameStream = minecraftTransport.minecraftFrameStream,
                    closeTransport = minecraftTransport::close,
                    minecraftConnectionDefinition = minecraftConnectionDefinition,
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
