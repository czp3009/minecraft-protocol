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
    signableCommandArgument: SignableCommandArgument,
    lastSeen: List<ByteString>,
): SignedMessageBody = SignedMessageBody(
    content = signableCommandArgument.value,
    timestampEpochMillis = timestampEpochMillis,
    salt = salt,
    lastSeen = lastSeen,
)

suspend fun MinecraftServerboundChatChainVerifier.verify(
    chatMessagePacket: ChatMessagePacket,
    lastSeen: List<ByteString>,
): MinecraftChatVerificationResult = verifyNext(
    signedMessageBody = chatMessagePacket.toSignedMessageBody(lastSeen),
    signature = chatMessagePacket.signature,
)

/** The supplied arguments are the caller's Brigadier-derived signable name/value pairs. */
suspend fun MinecraftServerboundChatChainVerifier.verify(
    signedChatCommandPacket: SignedChatCommandPacket,
    signableArguments: List<SignableCommandArgument>,
    lastSeen: List<ByteString>,
): MinecraftChatBatchVerificationResult {
    val argumentsByName = signableArguments.associateBy { it.name }
    if (argumentsByName.size != signableArguments.size) {
        return MinecraftChatBatchVerificationResult.Invalid(
            minecraftChatChainFailure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
            failedAt = 0,
        )
    }
    if (signedChatCommandPacket.arguments.entries.isEmpty() && signableArguments.isNotEmpty()) {
        return MinecraftChatBatchVerificationResult.Invalid(
            minecraftChatChainFailure = MinecraftChatChainFailure.MISSING_SIGNATURE,
            failedAt = 0,
        )
    }
    val seenNames = mutableSetOf<String>()
    val inputs = signedChatCommandPacket.arguments.entries.mapIndexed { index, entry ->
        val signableCommandArgument =
            argumentsByName[entry.name] ?: return MinecraftChatBatchVerificationResult.Invalid(
                minecraftChatChainFailure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
                failedAt = index,
            )
        seenNames += entry.name
        MinecraftChatSignatureInput(
            signedMessageBody = signedChatCommandPacket.toSignedMessageBody(signableCommandArgument, lastSeen),
            signature = entry.signature,
        )
    }
    if (!seenNames.containsAll(argumentsByName.keys)) {
        return MinecraftChatBatchVerificationResult.Invalid(
            minecraftChatChainFailure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
            failedAt = signedChatCommandPacket.arguments.entries.size,
        )
    }
    return verifyAll(inputs)
}

suspend fun MinecraftClientboundChatChainVerifier.verify(
    playerChatMessagePacket: PlayerChatMessagePacket,
    lastSeen: List<ByteString>,
): MinecraftChatVerificationResult = verify(
    index = playerChatMessagePacket.index,
    packetSender = playerChatMessagePacket.sender,
    signedMessageBody = playerChatMessagePacket.body.toSignedMessageBody(lastSeen),
    signature = playerChatMessagePacket.signature,
)

suspend fun MinecraftChatChainSigner.signChatMessagePacket(
    message: String,
    timestampEpochMillis: Long,
    salt: Long,
    lastSeen: List<ByteString>,
    lastSeenMessagesUpdate: LastSeenMessagesUpdate,
): ChatMessagePacket = sign(
    SignedMessageBody(
        content = message,
        timestampEpochMillis = timestampEpochMillis,
        salt = salt,
        lastSeen = lastSeen,
    ),
).toChatMessagePacket(lastSeenMessagesUpdate)

suspend fun MinecraftChatChainSigner.signCommandArguments(
    arguments: List<SignableCommandArgument>,
    timestampEpochMillis: Long,
    salt: Long,
    lastSeen: List<ByteString>,
): SignedCommandArguments {
    val messages = signAll(
        arguments.map { signableCommandArgument ->
            SignedMessageBody(
                content = signableCommandArgument.value,
                timestampEpochMillis = timestampEpochMillis,
                salt = salt,
                lastSeen = lastSeen,
            )
        },
    )
    return SignedCommandArguments(
        arguments.zip(messages) { signableCommandArgument, minecraftSignedMessage ->
            ArgumentSignature(signableCommandArgument.name, minecraftSignedMessage.signature)
        },
    )
}

fun MinecraftSignedMessage.toChatMessagePacket(
    lastSeenMessagesUpdate: LastSeenMessagesUpdate,
): ChatMessagePacket = ChatMessagePacket(
    message = signedMessageBody.content,
    timestampEpochMillis = signedMessageBody.timestampEpochMillis,
    salt = signedMessageBody.salt,
    signature = signature,
    lastSeenMessages = lastSeenMessagesUpdate,
)

/** Builds the recipient-specific clientbound packet after the caller packs its last-seen signature cache. */
fun MinecraftSignedMessage.toPlayerChatMessagePacket(
    globalIndex: Int,
    boundChatType: BoundChatType,
    packedLastSeen: List<PackedMessageSignature>,
    unsignedContent: TextComponent? = null,
    filterMask: FilterMask = FilterMask.PassThrough,
): PlayerChatMessagePacket = PlayerChatMessagePacket(
    globalIndex = globalIndex,
    sender = signedMessageLink.sender,
    index = signedMessageLink.index,
    signature = signature,
    body = PackedSignedMessageBody(
        content = signedMessageBody.content,
        timestampEpochMillis = signedMessageBody.timestampEpochMillis,
        salt = signedMessageBody.salt,
        lastSeen = packedLastSeen,
    ),
    unsignedContent = unsignedContent,
    filterMask = filterMask,
    chatType = boundChatType,
)
