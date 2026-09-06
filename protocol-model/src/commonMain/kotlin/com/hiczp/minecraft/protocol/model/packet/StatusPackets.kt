package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.ServerStatus
import com.hiczp.minecraft.protocol.model.wire.JsonEncoded
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(0x00, ConnectionState.STATUS, PacketDirection.CLIENTBOUND, "status_response")
data class ClientboundStatusResponsePacket(
    @JsonEncoded
    @MaxLength(32_767)
    val status: ServerStatus,
) : StatusStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x00, ConnectionState.STATUS, PacketDirection.SERVERBOUND, "status_request")
data object ServerboundStatusRequestPacket : StatusStatePacket, ServerboundPacket
