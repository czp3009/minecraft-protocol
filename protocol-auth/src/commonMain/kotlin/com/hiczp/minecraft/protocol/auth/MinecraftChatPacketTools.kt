package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.ClientboundPlayerChatPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundChatCommandSignedPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundChatPacket
import com.hiczp.minecraft.protocol.model.type.*

fun ServerboundChatPacket.toSignedMessageBody(
    lastSeen: List<ByteString>,
): SignedMessageBody = SignedMessageBody(
    content = message,
    timestampEpochMillis = timeStamp,
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

fun ServerboundChatCommandSignedPacket.toSignedMessageBody(
    signableCommandArgument: SignableCommandArgument,
    lastSeen: List<ByteString>,
): SignedMessageBody = SignedMessageBody(
    content = signableCommandArgument.value,
    timestampEpochMillis = timeStamp,
    salt = salt,
    lastSeen = lastSeen,
)

suspend fun MinecraftServerboundChatChainVerifier.verify(
    serverboundChatPacket: ServerboundChatPacket,
    lastSeen: List<ByteString>,
): MinecraftChatVerificationResult = verifyNext(
    signedMessageBody = serverboundChatPacket.toSignedMessageBody(lastSeen),
    signature = serverboundChatPacket.signature,
)

/** The supplied arguments are the caller's Brigadier-derived signable name/value pairs. */
suspend fun MinecraftServerboundChatChainVerifier.verify(
    serverboundChatCommandSignedPacket: ServerboundChatCommandSignedPacket,
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
    if (serverboundChatCommandSignedPacket.argumentSignatures.entries.isEmpty() && signableArguments.isNotEmpty()) {
        return MinecraftChatBatchVerificationResult.Invalid(
            minecraftChatChainFailure = MinecraftChatChainFailure.MISSING_SIGNATURE,
            failedAt = 0,
        )
    }
    val seenNames = mutableSetOf<String>()
    val inputs = serverboundChatCommandSignedPacket.argumentSignatures.entries.mapIndexed { index, entry ->
        val signableCommandArgument =
            argumentsByName[entry.name] ?: return MinecraftChatBatchVerificationResult.Invalid(
                minecraftChatChainFailure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
                failedAt = index,
            )
        seenNames += entry.name
        MinecraftChatSignatureInput(
            signedMessageBody = serverboundChatCommandSignedPacket.toSignedMessageBody(
                signableCommandArgument,
                lastSeen
            ),
            signature = entry.signature,
        )
    }
    if (!seenNames.containsAll(argumentsByName.keys)) {
        return MinecraftChatBatchVerificationResult.Invalid(
            minecraftChatChainFailure = MinecraftChatChainFailure.ARGUMENT_MISMATCH,
            failedAt = serverboundChatCommandSignedPacket.argumentSignatures.entries.size,
        )
    }
    return verifyAll(inputs)
}

suspend fun MinecraftClientboundChatChainVerifier.verify(
    clientboundPlayerChatPacket: ClientboundPlayerChatPacket,
    lastSeen: List<ByteString>,
): MinecraftChatVerificationResult = verify(
    index = clientboundPlayerChatPacket.index,
    packetSender = clientboundPlayerChatPacket.sender,
    signedMessageBody = clientboundPlayerChatPacket.body.toSignedMessageBody(lastSeen),
    signature = clientboundPlayerChatPacket.signature,
)

suspend fun MinecraftChatChainSigner.signServerboundChatPacket(
    message: String,
    timestampEpochMillis: Long,
    salt: Long,
    lastSeen: List<ByteString>,
    lastSeenMessagesUpdate: LastSeenMessagesUpdate,
): ServerboundChatPacket = sign(
    SignedMessageBody(
        content = message,
        timestampEpochMillis = timestampEpochMillis,
        salt = salt,
        lastSeen = lastSeen,
    ),
).toServerboundChatPacket(lastSeenMessagesUpdate)

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

fun MinecraftSignedMessage.toServerboundChatPacket(
    lastSeenMessagesUpdate: LastSeenMessagesUpdate,
): ServerboundChatPacket = ServerboundChatPacket(
    message = signedMessageBody.content,
    timeStamp = signedMessageBody.timestampEpochMillis,
    salt = signedMessageBody.salt,
    signature = signature,
    lastSeenMessages = lastSeenMessagesUpdate,
)

/** Builds the recipient-specific clientbound packet after the caller packs its last-seen signature cache. */
fun MinecraftSignedMessage.toClientboundPlayerChatPacket(
    globalIndex: Int,
    boundChatType: BoundChatType,
    packedLastSeen: List<PackedMessageSignature>,
    unsignedContent: TextComponent? = null,
    filterMask: FilterMask = FilterMask.PassThrough,
): ClientboundPlayerChatPacket = ClientboundPlayerChatPacket(
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
