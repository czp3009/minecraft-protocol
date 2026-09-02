package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.packet.PacketDirection
import com.hiczp.minecraft.protocol.model.packet.PacketRoute
import com.hiczp.minecraft.protocol.model.packet.UnknownPacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

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
        val loginQuery = request.packetRoute as? PacketRoute.LoginQuery
            ?: throw IllegalArgumentException("Forge Login response requires a Login query")
        return UnknownPacket.Serverbound(
            PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                loginQuery.transactionId,
                loginQuery.channel,
                hasPayload = true,
            ),
            wrap(loginQuery.channel, data),
        )
    }

    fun unsupported(
        request: UnknownPacket.Clientbound,
    ): UnknownPacket.Serverbound {
        val loginQuery = request.packetRoute as? PacketRoute.LoginQuery
            ?: throw IllegalArgumentException("Forge Login response requires a Login query")
        return UnknownPacket.Serverbound(
            PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                loginQuery.transactionId,
                loginQuery.channel,
                hasPayload = false,
            ),
            ByteString(byteArrayOf()),
        )
    }

    fun unwrap(response: UnknownPacket.Serverbound): ForgeLoginQueryPayload? {
        val loginQuery = response.packetRoute as? PacketRoute.LoginQuery
            ?: throw IllegalArgumentException("Forge Login wrapper requires a Login query")
        if (!loginQuery.hasPayload) return null
        val forgeLoginWrapperWire = MinecraftProtocolFormat.Default.decodeFromByteArray<ForgeLoginWrapperWire>(
            response.data.toByteArray(),
        )
        return ForgeLoginQueryPayload(forgeLoginWrapperWire.channel, forgeLoginWrapperWire.data)
    }

    fun wrap(channel: Identifier, data: ByteString): ByteString = ByteString(
        MinecraftProtocolFormat.Default.encodeToByteArray(
            ForgeLoginWrapperWire(channel, data),
        ),
    )
}

@Serializable
private data class ForgeLoginWrapperWire(
    val channel: Identifier,
    val data: ByteString,
)
