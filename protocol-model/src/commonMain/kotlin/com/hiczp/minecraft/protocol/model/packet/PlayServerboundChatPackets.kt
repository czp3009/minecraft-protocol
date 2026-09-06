package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.ChatSessionData
import com.hiczp.minecraft.protocol.model.type.LastSeenMessagesUpdate
import com.hiczp.minecraft.protocol.model.type.SignedCommandArguments
import com.hiczp.minecraft.protocol.model.wire.FixedLength
import com.hiczp.minecraft.protocol.model.wire.MaxLength
import kotlinx.serialization.Serializable

@Serializable
@PacketInfo(
    0x08,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "chat_command_signed",
)
data class ServerboundChatCommandSignedPacket(
    @MaxLength(32_767)
    val command: String,
    val timeStamp: Long,
    val salt: Long,
    val argumentSignatures: SignedCommandArguments,
    val lastSeenMessages: LastSeenMessagesUpdate,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x09,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "chat",
)
data class ServerboundChatPacket(
    @MaxLength(256)
    val message: String,
    val timeStamp: Long,
    val salt: Long,
    @FixedLength(256)
    val signature: ByteString?,
    val lastSeenMessages: LastSeenMessagesUpdate,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x0A,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "chat_session_update",
)
data class ServerboundChatSessionUpdatePacket(
    val chatSession: ChatSessionData,
) : PlayStatePacket, ServerboundPacket
