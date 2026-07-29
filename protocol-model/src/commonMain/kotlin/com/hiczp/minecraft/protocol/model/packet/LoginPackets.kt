package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.wire.MaxByteLength
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.RemainingBytes
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(0x00, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "login_disconnect")
data class LoginDisconnectPacket(
    @MaxLength(262_144)
    val reason: JsonTextComponent,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "hello")
data class EncryptionRequestPacket(
    @MaxLength(20)
    val serverId: String,
    val publicKey: ByteString,
    val verifyToken: ByteString,
    val shouldAuthenticate: Boolean,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x02, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "login_finished")
data class LoginSuccessPacket(
    val profile: GameProfile,
    val sessionId: Uuid,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "login_compression")
data class SetCompressionPacket(
    @VarInt
    val threshold: Int,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x04, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "custom_query")
data class LoginPluginRequestPacket(
    @VarInt
    val messageId: Int,
    val channel: Identifier,
    @RemainingBytes
    @MaxByteLength(1_048_576)
    val data: ByteString,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x05, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "cookie_request")
data class LoginCookieRequestPacket(
    val key: Identifier,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x00, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "hello")
data class LoginStartPacket(
    @MaxLength(16)
    val name: String,
    val playerUuid: Uuid,
) : LoginStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "key")
data class EncryptionResponsePacket(
    val sharedSecret: ByteString,
    val verifyToken: ByteString,
) : LoginStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x02, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "custom_query_answer")
data class LoginPluginResponsePacket(
    @VarInt
    val messageId: Int,
    @RemainingBytes
    @MaxByteLength(1_048_576)
    val data: ByteString?,
) : LoginStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "login_acknowledged")
data object LoginAcknowledgedPacket : LoginStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x04, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "cookie_response")
data class LoginCookieResponsePacket(
    val key: Identifier,
    @MaxByteLength(5_120)
    val payload: ByteString?,
) : LoginStatePacket, ServerboundPacket
