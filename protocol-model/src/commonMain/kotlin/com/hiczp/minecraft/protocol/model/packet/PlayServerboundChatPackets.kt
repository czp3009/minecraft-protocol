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
data class SignedChatCommandPacket(
    @MaxLength(32_767)
    val command: String,
    val timestampEpochMillis: Long,
    val salt: Long,
    val arguments: SignedCommandArguments,
    val lastSeenMessages: LastSeenMessagesUpdate,
) : PlayStatePacket, ServerboundPacket

@Serializable
@PacketInfo(
    0x09,
    ConnectionState.PLAY,
    PacketDirection.SERVERBOUND,
    officialName = "chat",
)
data class ChatMessagePacket(
    @MaxLength(256)
    val message: String,
    val timestampEpochMillis: Long,
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
data class PlayerSessionPacket(
    val session: ChatSessionData,
) : PlayStatePacket, ServerboundPacket
