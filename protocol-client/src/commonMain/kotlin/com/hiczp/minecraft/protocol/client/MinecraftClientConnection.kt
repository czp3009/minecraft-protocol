@file:OptIn(com.hiczp.minecraft.protocol.session.InternalMinecraftConnectionApi::class)

package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundPacket
import com.hiczp.minecraft.protocol.session.MinecraftConnectionDefinition
import com.hiczp.minecraft.protocol.session.MinecraftConnectionEngine
import com.hiczp.minecraft.protocol.session.MinecraftPacketConnection
import com.hiczp.minecraft.protocol.session.MinecraftSessionSide
import com.hiczp.minecraft.protocol.transport.MinecraftTransport
import com.hiczp.minecraft.protocol.transport.MinecraftTransportConfiguration
import io.ktor.network.selector.*
import io.ktor.network.sockets.*

class MinecraftClientConnection internal constructor(
    private val connection: MinecraftPacketConnection<ClientboundPacket, ServerboundPacket>,
    val serverAddress: String,
    val serverPort: Int,
) : MinecraftPacketConnection<ClientboundPacket, ServerboundPacket> by connection {
    companion object {
        suspend fun connect(
            selectorManager: SelectorManager,
            host: String,
            port: Int = DEFAULT_PORT,
            definition: MinecraftConnectionDefinition = MinecraftConnectionDefinition(),
            transportConfiguration: MinecraftTransportConfiguration = MinecraftTransportConfiguration(),
        ): MinecraftClientConnection {
            val socket = aSocket(selectorManager).tcp().connect(host, port)
            val transport = MinecraftTransport(socket, transportConfiguration)
            return MinecraftClientConnection(
                connection = MinecraftConnectionEngine(
                    frames = transport.frames,
                    closeTransport = transport::close,
                    side = MinecraftSessionSide.CLIENT,
                    definition = definition,
                ),
                serverAddress = host,
                serverPort = port,
            )
        }

        const val DEFAULT_PORT: Int = 25565
    }
}
