package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.PacketDirection
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.packet.UnknownPacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException
import kotlinx.serialization.Serializable

data class ForgeLoginQueryPayload(
    val channel: Identifier,
    val data: ByteString,
)

/** Public helpers for Forge's serverbound LoginWrapper convention. */
object ForgeLoginQueries {
    fun query(
        transactionId: Int,
        channel: Identifier,
        data: ByteString,
    ): UnknownPacket.Clientbound = UnknownPacket.Clientbound(
        PacketRoute.LoginQuery(
            PacketDirection.CLIENTBOUND,
            transactionId,
            channel,
        ),
        data,
    )

    fun response(
        request: UnknownPacket.Clientbound,
        data: ByteString,
    ): UnknownPacket.Serverbound {
        val route = request.route as? PacketRoute.LoginQuery
            ?: throw IllegalArgumentException("Forge Login response requires a Login query")
        return UnknownPacket.Serverbound(
            PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                route.transactionId,
                route.channel,
                hasPayload = true,
            ),
            wrap(route.channel, data),
        )
    }

    fun unsupported(
        request: UnknownPacket.Clientbound,
    ): UnknownPacket.Serverbound {
        val route = request.route as? PacketRoute.LoginQuery
            ?: throw IllegalArgumentException("Forge Login response requires a Login query")
        return UnknownPacket.Serverbound(
            PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                route.transactionId,
                route.channel,
                hasPayload = false,
            ),
            ByteString(byteArrayOf()),
        )
    }

    fun unwrap(response: UnknownPacket.Serverbound): ForgeLoginQueryPayload? {
        val route = response.route as? PacketRoute.LoginQuery
            ?: throw IllegalArgumentException("Forge Login wrapper requires a Login query")
        if (!route.hasPayload) return null
        val wrapper = MinecraftProtocolFormat.Default.decodeFromByteArray(
            ForgeLoginWrapperWire.serializer(),
            response.data.toByteArray(),
        )
        if (wrapper.channel != route.channel) {
            throw MinecraftSerializationException(
                "Forge Login wrapper uses ${wrapper.channel}, but transaction ${route.transactionId} belongs to ${route.channel}",
            )
        }
        return ForgeLoginQueryPayload(wrapper.channel, wrapper.data)
    }

    fun wrap(channel: Identifier, data: ByteString): ByteString = ByteString(
        MinecraftProtocolFormat.Default.encodeToByteArray(
            ForgeLoginWrapperWire.serializer(),
            ForgeLoginWrapperWire(channel, data),
        ),
    )
}

@Serializable
private data class ForgeLoginWrapperWire(
    val channel: Identifier,
    val data: ByteString,
)
