package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.GameProfile
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.JsonTextComponent
import com.hiczp.minecraft.protocol.model.wire.MaxByteLength
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.RemainingBytes
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@PacketInfo(0x00, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "login_disconnect")
data class ClientboundLoginDisconnectPacket(
    @MaxLength(262_144)
    val reason: JsonTextComponent,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "hello")
data class ClientboundHelloPacket(
    @MaxLength(20)
    val serverId: String,
    val publicKey: ByteString,
    val challenge: ByteString,
    val shouldAuthenticate: Boolean,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x02, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "login_finished")
data class ClientboundLoginFinishedPacket(
    val gameProfile: GameProfile,
    val sessionId: Uuid,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "login_compression")
data class ClientboundLoginCompressionPacket(
    @VarInt
    val compressionThreshold: Int,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(
    0x04, ConnectionState.LOGIN, PacketDirection.CLIENTBOUND, "custom_query",
    shapeException = "CustomQueryPayload is a runtime interface that writes buffers; channel and owned data bytes provide its lossless, buffer-independent payload representation.",
)
data class ClientboundCustomQueryPacket(
    @VarInt
    val transactionId: Int,
    val channel: Identifier,
    @RemainingBytes
    @MaxByteLength(1_048_576)
    val data: ByteString,
) : LoginStatePacket, ClientboundPacket

@Serializable
@PacketInfo(0x00, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "hello")
data class ServerboundHelloPacket(
    @MaxLength(16)
    val name: String,
    val profileId: Uuid,
) : LoginStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x01, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "key")
data class ServerboundKeyPacket(
    val keybytes: ByteString,
    val encryptedChallenge: ByteString,
) : LoginStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x02, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "custom_query_answer")
data class ServerboundCustomQueryAnswerPacket(
    @VarInt
    val transactionId: Int,
    @RemainingBytes
    @MaxByteLength(1_048_576)
    val payload: ByteString?,
) : LoginStatePacket, ServerboundPacket

@Serializable
@PacketInfo(0x03, ConnectionState.LOGIN, PacketDirection.SERVERBOUND, "login_acknowledged")
data object ServerboundLoginAcknowledgedPacket : LoginStatePacket, ServerboundPacket
