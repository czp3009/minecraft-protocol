package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.UnsignedByte
import com.hiczp.minecraft.protocol.model.wire.UnsignedShort
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
enum class HandshakeNextState {
    /** Wire value zero is reserved and is not a valid next state. */
    UNUSED,
    STATUS,
    LOGIN,
    TRANSFER,
}

@Serializable
@PacketInfo(0x00, ConnectionState.HANDSHAKE, PacketDirection.SERVERBOUND, "intention")
data class HandshakePacket(
    @VarInt
    val protocolVersion: Int,
    @MaxLength(255)
    val serverAddress: String,
    @UnsignedShort
    val serverPort: Int,
    val nextState: HandshakeNextState,
) : HandshakeStatePacket, ServerboundPacket {
    init {
        require(nextState != HandshakeNextState.UNUSED) {
            "Handshake next-state wire value zero is invalid"
        }
    }
}

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
) : HandshakeStatePacket, ServerboundPacket {
    init {
        require(payload == 1) { "Legacy server-list ping payload must be 0x01" }
    }
}
