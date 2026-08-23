package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.ChatMessagePacket
import com.hiczp.minecraft.protocol.model.packet.PlayerChatMessagePacket
import com.hiczp.minecraft.protocol.model.packet.SignedChatCommandPacket
import com.hiczp.minecraft.protocol.model.type.*

fun ChatMessagePacket.toSignedMessageBody(
    lastSeen: List<ByteString>,
): SignedMessageBody = SignedMessageBody(
    content = message,
    timestampEpochMillis = timestampEpochMillis,
    salt = salt,
    lastSeen = lastSeen,
)

fun PackedSignedMessageBody.toSignedMessageBody(
    lastSeen: List<ByteString>,
): SignedMessageBody = SignedMessageBody(
    content = content,
    timestampEpochMillis = timestampEpochMillis,
    salt = salt,
    lastSeen = lastSeen,
)

fun SignedChatCommandPacket.toSignedMessageBody(
    argument: SignableCommandArgument,
    lastSeen: List<ByteString>,
): SignedMessageBody = SignedMessageBody(
    content = argument.value,
    timestampEpochMillis = timestampEpochMillis,
    salt = salt,
    lastSeen = lastSeen,
)

suspend fun MinecraftServerboundChatChainVerifier.verify(
    packet: ChatMessagePacket,
    lastSeen: List<ByteString>,
): MinecraftChatVerificationResult = verifyNext(
    body = packet.toSignedMessageBody(lastSeen),
    signature = packet.signature,
)

/** The supplied arguments are the caller's Brigadier-derived signable name/value pairs. */
suspend fun MinecraftServerboundChatChainVerifier.verify(
    packet: SignedChatCommandPacket,
    signableArguments: List<SignableCommandArgument>,
    lastSeen: List<ByteString>,
): MinecraftChatBatchVerificationResult {
    val argumentsByName = signableArguments.associateBy { it.name }
    if (argumentsByName.size != signableArguments.size) {
        return MinecraftChatBatchVerificationResult.Invalid(
            failure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
            failedAt = 0,
        )
    }
    if (packet.arguments.entries.isEmpty() && signableArguments.isNotEmpty()) {
        return MinecraftChatBatchVerificationResult.Invalid(
            failure = MinecraftChatChainFailure.MISSING_SIGNATURE,
            failedAt = 0,
        )
    }
    val seenNames = mutableSetOf<String>()
    val inputs = packet.arguments.entries.mapIndexed { index, entry ->
        val argument = argumentsByName[entry.name] ?: return MinecraftChatBatchVerificationResult.Invalid(
            failure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
            failedAt = index,
        )
        seenNames += entry.name
        MinecraftChatSignatureInput(
            body = packet.toSignedMessageBody(argument, lastSeen),
            signature = entry.signature,
        )
    }
    if (!seenNames.containsAll(argumentsByName.keys)) {
        return MinecraftChatBatchVerificationResult.Invalid(
            failure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
            failedAt = packet.arguments.entries.size,
        )
    }
    return verifyAll(inputs)
}

suspend fun MinecraftClientboundChatChainVerifier.verify(
    packet: PlayerChatMessagePacket,
    lastSeen: List<ByteString>,
): MinecraftChatVerificationResult = verify(
    index = packet.index,
    packetSender = packet.sender,
    body = packet.body.toSignedMessageBody(lastSeen),
    signature = packet.signature,
)

suspend fun MinecraftChatChainSigner.signChatMessagePacket(
    message: String,
    timestampEpochMillis: Long,
    salt: Long,
    lastSeen: List<ByteString>,
    lastSeenMessages: LastSeenMessagesUpdate,
): ChatMessagePacket = sign(
    SignedMessageBody(
        content = message,
        timestampEpochMillis = timestampEpochMillis,
        salt = salt,
        lastSeen = lastSeen,
    ),
).toChatMessagePacket(lastSeenMessages)

suspend fun MinecraftChatChainSigner.signCommandArguments(
    arguments: List<SignableCommandArgument>,
    timestampEpochMillis: Long,
    salt: Long,
    lastSeen: List<ByteString>,
): SignedCommandArguments {
    require(arguments.size <= MAX_SIGNED_COMMAND_ARGUMENTS) {
        "A signed command cannot contain more than $MAX_SIGNED_COMMAND_ARGUMENTS arguments"
    }
    val messages = signAll(
        arguments.map { argument ->
            SignedMessageBody(
                content = argument.value,
                timestampEpochMillis = timestampEpochMillis,
                salt = salt,
                lastSeen = lastSeen,
            )
        },
    )
    return SignedCommandArguments(
        arguments.zip(messages) { argument, message ->
            ArgumentSignature(argument.name, message.signature)
        },
    )
}

fun MinecraftSignedMessage.toChatMessagePacket(
    lastSeenMessages: LastSeenMessagesUpdate,
): ChatMessagePacket = ChatMessagePacket(
    message = body.content,
    timestampEpochMillis = body.timestampEpochMillis,
    salt = body.salt,
    signature = signature,
    lastSeenMessages = lastSeenMessages,
)

/** Builds the recipient-specific clientbound packet after the caller packs its last-seen signature cache. */
fun MinecraftSignedMessage.toPlayerChatMessagePacket(
    globalIndex: Int,
    chatType: BoundChatType,
    packedLastSeen: List<PackedMessageSignature>,
    unsignedContent: TextComponent? = null,
    filterMask: FilterMask = FilterMask.PassThrough,
): PlayerChatMessagePacket {
    require(packedLastSeen.size == body.lastSeen.size) {
        "Packed last-seen signatures must preserve the signed body's entry count"
    }
    return PlayerChatMessagePacket(
        globalIndex = globalIndex,
        sender = link.sender,
        index = link.index,
        signature = signature,
        body = PackedSignedMessageBody(
            content = body.content,
            timestampEpochMillis = body.timestampEpochMillis,
            salt = body.salt,
            lastSeen = packedLastSeen,
        ),
        unsignedContent = unsignedContent,
        filterMask = filterMask,
        chatType = chatType,
    )
}

private const val MAX_SIGNED_COMMAND_ARGUMENTS: Int = 8
