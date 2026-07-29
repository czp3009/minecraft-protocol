package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.wire.MaxLength
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(0x00, ConnectionState.STATUS, PacketDirection.CLIENTBOUND, "status_response")
data class StatusResponsePacket(
    @MaxLength(32_767)
    val jsonResponse: String,
) : StatusStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.STATUS, PacketDirection.CLIENTBOUND, "pong_response")
data class StatusPongResponsePacket(
    val timestamp: Long,
) : StatusStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x00, ConnectionState.STATUS, PacketDirection.SERVERBOUND, "status_request")
data object StatusRequestPacket : StatusStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.STATUS, PacketDirection.SERVERBOUND, "ping_request")
data class StatusPingRequestPacket(
    val timestamp: Long,
) : StatusStatePacket, ServerboundPacket
