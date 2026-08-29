package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.PackedMessageSignature
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

fun interface MinecraftChatSignatureSigner {
    suspend fun sign(
        signedMessageLink: SignedMessageLink,
        signedMessageBody: SignedMessageBody,
    ): ByteString
}

fun interface MinecraftChatSignatureVerifier {
    suspend fun verify(
        signedMessageLink: SignedMessageLink,
        signedMessageBody: SignedMessageBody,
        signature: ByteString,
    ): Boolean
}

data class MinecraftSignedMessage(
    val signedMessageLink: SignedMessageLink,
    val signedMessageBody: SignedMessageBody,
    val signature: ByteString,
) {
    init {
        require(signature.size == PackedMessageSignature.SIGNATURE_BYTES) {
            "A signed chat message must have a ${PackedMessageSignature.SIGNATURE_BYTES}-byte signature"
        }
    }
}

data class MinecraftChatSignatureInput(
    val signedMessageBody: SignedMessageBody,
    val signature: ByteString?,
)

enum class MinecraftChatChainFailure {
    MISSING_SIGNATURE,
    INVALID_SIGNATURE,
    OUT_OF_ORDER_TIMESTAMP,
    OUT_OF_ORDER_INDEX,
    UNEXPECTED_LINK,
    CHAIN_EXHAUSTED,
    ARGUMENT_MISMATCH,
}

sealed interface MinecraftChatVerificationResult {
    data class Valid(
        val minecraftSignedMessage: MinecraftSignedMessage,
    ) : MinecraftChatVerificationResult

    data class Invalid(
        val minecraftChatChainFailure: MinecraftChatChainFailure,
    ) : MinecraftChatVerificationResult
}

sealed interface MinecraftChatBatchVerificationResult {
    data class Valid(
        val messages: List<MinecraftSignedMessage>,
    ) : MinecraftChatBatchVerificationResult

    data class Invalid(
        val minecraftChatChainFailure: MinecraftChatChainFailure,
        val failedAt: Int,
    ) : MinecraftChatBatchVerificationResult
}

class MinecraftChatChainExhaustedException : IllegalStateException("The Minecraft chat chain index is exhausted")

/**
 * Serializes allocation and signing of sender-chain indices. Returned messages must still be sent in returned order.
 */
class MinecraftChatChainSigner(
    sender: Uuid,
    sessionId: Uuid,
    private val minecraftChatSignatureSigner: MinecraftChatSignatureSigner,
    initialIndex: Int = 0,
) {
    private val mutex = Mutex()
    private var nextLink: SignedMessageLink? = SignedMessageLink(initialIndex, sender, sessionId)

    constructor(
        sender: Uuid,
        sessionId: Uuid,
        minecraftProfileKeyPair: MinecraftProfileKeyPair,
        initialIndex: Int = 0,
    ) : this(
        sender = sender,
        sessionId = sessionId,
        minecraftChatSignatureSigner = minecraftProfileKeyPair.asChatSignatureSigner(),
        initialIndex = initialIndex,
    )

    suspend fun sign(signedMessageBody: SignedMessageBody): MinecraftSignedMessage = mutex.withLock {
        signAllLocked(listOf(signedMessageBody)).single()
    }

    /** Signs one contiguous batch under a single lock, committing its indices only after every signature succeeds. */
    suspend fun signAll(bodies: List<SignedMessageBody>): List<MinecraftSignedMessage> = mutex.withLock {
        signAllLocked(bodies)
    }

    suspend fun nextLink(): SignedMessageLink? = mutex.withLock { nextLink }

    private suspend fun signAllLocked(bodies: List<SignedMessageBody>): List<MinecraftSignedMessage> {
        var candidateLink = nextLink
        val messages = bodies.map { signedMessageBody ->
            val signedMessageLink = candidateLink ?: throw MinecraftChatChainExhaustedException()
            val minecraftSignedMessage = MinecraftSignedMessage(
                signedMessageLink = signedMessageLink,
                signedMessageBody = signedMessageBody,
                signature = minecraftChatSignatureSigner.sign(signedMessageLink, signedMessageBody),
            )
            candidateLink = signedMessageLink.advance()
            minecraftSignedMessage
        }
        nextLink = candidateLink
        return messages
    }
}

/**
 * Verifies packets whose signed chain index is implicit. Invalid input does not mutate state; callers may discard this
 * verifier after failure when they want the official server's permanently-broken-chain policy.
 */
class MinecraftServerboundChatChainVerifier(
    sender: Uuid,
    sessionId: Uuid,
    private val minecraftChatSignatureVerifier: MinecraftChatSignatureVerifier,
    initialIndex: Int = 0,
    initialTimestampEpochMillis: Long? = null,
) {
    private val mutex = Mutex()
    private var nextLink: SignedMessageLink? = SignedMessageLink(initialIndex, sender, sessionId)
    private var lastTimestampEpochMillis: Long? = initialTimestampEpochMillis

    constructor(
        sender: Uuid,
        sessionId: Uuid,
        minecraftProfilePublicKey: MinecraftProfilePublicKey,
        initialIndex: Int = 0,
        initialTimestampEpochMillis: Long? = null,
    ) : this(
        sender = sender,
        sessionId = sessionId,
        minecraftChatSignatureVerifier = minecraftProfilePublicKey.asChatSignatureVerifier(),
        initialIndex = initialIndex,
        initialTimestampEpochMillis = initialTimestampEpochMillis,
    )

    suspend fun verifyNext(
        signedMessageBody: SignedMessageBody,
        signature: ByteString?,
    ): MinecraftChatVerificationResult = when (
        val minecraftChatBatchVerificationResult =
            verifyAll(listOf(MinecraftChatSignatureInput(signedMessageBody, signature)))
    ) {
        is MinecraftChatBatchVerificationResult.Valid -> MinecraftChatVerificationResult.Valid(
            minecraftChatBatchVerificationResult.messages.single()
        )

        is MinecraftChatBatchVerificationResult.Invalid -> MinecraftChatVerificationResult.Invalid(
            minecraftChatBatchVerificationResult.minecraftChatChainFailure
        )
    }

    /** Verifies and advances an entire contiguous batch atomically. */
    suspend fun verifyAll(inputs: List<MinecraftChatSignatureInput>): MinecraftChatBatchVerificationResult =
        mutex.withLock {
            var candidateLink = nextLink
            var candidateTimestamp = lastTimestampEpochMillis
            val verified = ArrayList<MinecraftSignedMessage>(inputs.size)
            inputs.forEachIndexed { index, minecraftChatSignatureInput ->
                val signature =
                    minecraftChatSignatureInput.signature
                        ?: return@withLock MinecraftChatBatchVerificationResult.Invalid(
                            minecraftChatChainFailure = MinecraftChatChainFailure.MISSING_SIGNATURE,
                            failedAt = index,
                        )
                if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
                    return@withLock MinecraftChatBatchVerificationResult.Invalid(
                        minecraftChatChainFailure = MinecraftChatChainFailure.INVALID_SIGNATURE,
                        failedAt = index,
                    )
                }
                val signedMessageLink = candidateLink ?: return@withLock MinecraftChatBatchVerificationResult.Invalid(
                    minecraftChatChainFailure = MinecraftChatChainFailure.CHAIN_EXHAUSTED,
                    failedAt = index,
                )
                if (candidateTimestamp != null && minecraftChatSignatureInput.signedMessageBody.timestampEpochMillis < candidateTimestamp) {
                    return@withLock MinecraftChatBatchVerificationResult.Invalid(
                        minecraftChatChainFailure = MinecraftChatChainFailure.OUT_OF_ORDER_TIMESTAMP,
                        failedAt = index,
                    )
                }
                if (!minecraftChatSignatureVerifier.verify(
                        signedMessageLink,
                        minecraftChatSignatureInput.signedMessageBody,
                        signature
                    )
                ) {
                    return@withLock MinecraftChatBatchVerificationResult.Invalid(
                        minecraftChatChainFailure = MinecraftChatChainFailure.INVALID_SIGNATURE,
                        failedAt = index,
                    )
                }
                verified += MinecraftSignedMessage(
                    signedMessageLink,
                    minecraftChatSignatureInput.signedMessageBody,
                    signature
                )
                candidateLink = signedMessageLink.advance()
                candidateTimestamp = minecraftChatSignatureInput.signedMessageBody.timestampEpochMillis
            }
            nextLink = candidateLink
            lastTimestampEpochMillis = candidateTimestamp
            MinecraftChatBatchVerificationResult.Valid(verified)
        }

    suspend fun nextLink(): SignedMessageLink? = mutex.withLock { nextLink }

    suspend fun lastTimestampEpochMillis(): Long? = mutex.withLock { lastTimestampEpochMillis }
}

/** Verifies the explicit per-sender index on clientbound player-chat packets; skipped indices are valid. */
class MinecraftClientboundChatChainVerifier(
    private val sender: Uuid,
    private val sessionId: Uuid,
    private val minecraftChatSignatureVerifier: MinecraftChatSignatureVerifier,
) {
    private val mutex = Mutex()
    private var lastMessage: MinecraftSignedMessage? = null

    constructor(
        sender: Uuid,
        sessionId: Uuid,
        minecraftProfilePublicKey: MinecraftProfilePublicKey,
    ) : this(sender, sessionId, minecraftProfilePublicKey.asChatSignatureVerifier())

    suspend fun verify(
        signedMessageLink: SignedMessageLink,
        signedMessageBody: SignedMessageBody,
        signature: ByteString?,
    ): MinecraftChatVerificationResult = mutex.withLock {
        if (signature == null) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.MISSING_SIGNATURE)
        }
        if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.INVALID_SIGNATURE)
        }
        if (signedMessageLink.sender != sender || signedMessageLink.sessionId != sessionId) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.UNEXPECTED_LINK)
        }
        val minecraftSignedMessage = MinecraftSignedMessage(signedMessageLink, signedMessageBody, signature)
        if (!minecraftChatSignatureVerifier.verify(signedMessageLink, signedMessageBody, signature)) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.INVALID_SIGNATURE)
        }
        val previous = lastMessage
        if (minecraftSignedMessage != previous && previous != null && !signedMessageLink.isDescendantOf(previous.signedMessageLink)) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.OUT_OF_ORDER_INDEX)
        }
        lastMessage = minecraftSignedMessage
        MinecraftChatVerificationResult.Valid(minecraftSignedMessage)
    }

    suspend fun verify(
        index: Int,
        packetSender: Uuid,
        signedMessageBody: SignedMessageBody,
        signature: ByteString?,
    ): MinecraftChatVerificationResult {
        if (index < 0) {
            return MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.OUT_OF_ORDER_INDEX)
        }
        return verify(SignedMessageLink(index, packetSender, sessionId), signedMessageBody, signature)
    }

    suspend fun lastMessage(): MinecraftSignedMessage? = mutex.withLock { lastMessage }
}

fun MinecraftProfileKeyPair.asChatSignatureSigner(): MinecraftChatSignatureSigner =
    MinecraftChatSignatureSigner { signedMessageLink, signedMessageBody ->
        signChatMessage(signedMessageLink, signedMessageBody)
    }

fun MinecraftProfilePublicKey.asChatSignatureVerifier(): MinecraftChatSignatureVerifier =
    MinecraftChatSignatureVerifier { signedMessageLink, signedMessageBody, signature ->
        verifyChatMessage(signedMessageLink, signedMessageBody, signature)
    }
