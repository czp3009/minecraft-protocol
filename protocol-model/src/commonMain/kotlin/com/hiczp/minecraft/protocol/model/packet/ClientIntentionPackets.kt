package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.ClientIntent
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.UnsignedByte
import com.hiczp.minecraft.protocol.model.wire.UnsignedShort
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(0x00, ConnectionState.HANDSHAKE, PacketDirection.SERVERBOUND, "intention")
data class ClientIntentionPacket(
    @VarInt
    val protocolVersion: Int,
    @MaxLength(255)
    val hostName: String,
    @UnsignedShort
    val port: Int,
    val intention: ClientIntent,
) : HandshakeStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0xFE,
    ConnectionState.HANDSHAKE,
    PacketDirection.SERVERBOUND,
    "legacy_server_list_ping",
)
data class LegacyServerListPingPacket(
    @UnsignedByte
    val payload: Int = 1,
) : HandshakeStatePacket, ServerboundPacket
