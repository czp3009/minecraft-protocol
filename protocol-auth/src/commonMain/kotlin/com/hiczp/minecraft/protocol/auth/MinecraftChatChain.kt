package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.PackedMessageSignature
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

fun interface MinecraftChatSignatureSigner {
    suspend fun sign(
        link: SignedMessageLink,
        body: SignedMessageBody,
    ): ByteString
}

fun interface MinecraftChatSignatureVerifier {
    suspend fun verify(
        link: SignedMessageLink,
        body: SignedMessageBody,
        signature: ByteString,
    ): Boolean
}

data class MinecraftSignedMessage(
    val link: SignedMessageLink,
    val body: SignedMessageBody,
    val signature: ByteString,
) {
    init {
        require(signature.size == PackedMessageSignature.SIGNATURE_BYTES) {
            "A signed chat message must have a ${PackedMessageSignature.SIGNATURE_BYTES}-byte signature"
        }
    }
}

data class MinecraftChatSignatureInput(
    val body: SignedMessageBody,
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
        val message: MinecraftSignedMessage,
    ) : MinecraftChatVerificationResult

    data class Invalid(
        val failure: MinecraftChatChainFailure,
    ) : MinecraftChatVerificationResult
}

sealed interface MinecraftChatBatchVerificationResult {
    data class Valid(
        val messages: List<MinecraftSignedMessage>,
    ) : MinecraftChatBatchVerificationResult

    data class Invalid(
        val failure: MinecraftChatChainFailure,
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
    private val signatureSigner: MinecraftChatSignatureSigner,
    initialIndex: Int = 0,
) {
    private val mutex = Mutex()
    private var nextLink: SignedMessageLink? = SignedMessageLink(initialIndex, sender, sessionId)

    constructor(
        sender: Uuid,
        sessionId: Uuid,
        keyPair: MinecraftProfileKeyPair,
        initialIndex: Int = 0,
    ) : this(
        sender = sender,
        sessionId = sessionId,
        signatureSigner = keyPair.asChatSignatureSigner(),
        initialIndex = initialIndex,
    )

    suspend fun sign(body: SignedMessageBody): MinecraftSignedMessage = mutex.withLock {
        signAllLocked(listOf(body)).single()
    }

    /** Signs one contiguous batch under a single lock, committing its indices only after every signature succeeds. */
    suspend fun signAll(bodies: List<SignedMessageBody>): List<MinecraftSignedMessage> = mutex.withLock {
        signAllLocked(bodies)
    }

    suspend fun nextLink(): SignedMessageLink? = mutex.withLock { nextLink }

    private suspend fun signAllLocked(bodies: List<SignedMessageBody>): List<MinecraftSignedMessage> {
        var candidateLink = nextLink
        val messages = bodies.map { body ->
            val link = candidateLink ?: throw MinecraftChatChainExhaustedException()
            val message = MinecraftSignedMessage(
                link = link,
                body = body,
                signature = signatureSigner.sign(link, body),
            )
            candidateLink = link.advance()
            message
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
    private val signatureVerifier: MinecraftChatSignatureVerifier,
    initialIndex: Int = 0,
    initialTimestampEpochMillis: Long? = null,
) {
    private val mutex = Mutex()
    private var nextLink: SignedMessageLink? = SignedMessageLink(initialIndex, sender, sessionId)
    private var lastTimestampEpochMillis: Long? = initialTimestampEpochMillis

    constructor(
        sender: Uuid,
        sessionId: Uuid,
        publicKey: MinecraftProfilePublicKey,
        initialIndex: Int = 0,
        initialTimestampEpochMillis: Long? = null,
    ) : this(
        sender = sender,
        sessionId = sessionId,
        signatureVerifier = publicKey.asChatSignatureVerifier(),
        initialIndex = initialIndex,
        initialTimestampEpochMillis = initialTimestampEpochMillis,
    )

    suspend fun verifyNext(
        body: SignedMessageBody,
        signature: ByteString?,
    ): MinecraftChatVerificationResult = when (
        val result = verifyAll(listOf(MinecraftChatSignatureInput(body, signature)))
    ) {
        is MinecraftChatBatchVerificationResult.Valid -> MinecraftChatVerificationResult.Valid(result.messages.single())
        is MinecraftChatBatchVerificationResult.Invalid -> MinecraftChatVerificationResult.Invalid(result.failure)
    }

    /** Verifies and advances an entire contiguous batch atomically. */
    suspend fun verifyAll(inputs: List<MinecraftChatSignatureInput>): MinecraftChatBatchVerificationResult =
        mutex.withLock {
            var candidateLink = nextLink
            var candidateTimestamp = lastTimestampEpochMillis
            val verified = ArrayList<MinecraftSignedMessage>(inputs.size)
            inputs.forEachIndexed { index, input ->
                val signature = input.signature ?: return@withLock MinecraftChatBatchVerificationResult.Invalid(
                    failure = MinecraftChatChainFailure.MISSING_SIGNATURE,
                    failedAt = index,
                )
                if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
                    return@withLock MinecraftChatBatchVerificationResult.Invalid(
                        failure = MinecraftChatChainFailure.INVALID_SIGNATURE,
                        failedAt = index,
                    )
                }
                val link = candidateLink ?: return@withLock MinecraftChatBatchVerificationResult.Invalid(
                    failure = MinecraftChatChainFailure.CHAIN_EXHAUSTED,
                    failedAt = index,
                )
                if (candidateTimestamp != null && input.body.timestampEpochMillis < candidateTimestamp) {
                    return@withLock MinecraftChatBatchVerificationResult.Invalid(
                        failure = MinecraftChatChainFailure.OUT_OF_ORDER_TIMESTAMP,
                        failedAt = index,
                    )
                }
                if (!signatureVerifier.verify(link, input.body, signature)) {
                    return@withLock MinecraftChatBatchVerificationResult.Invalid(
                        failure = MinecraftChatChainFailure.INVALID_SIGNATURE,
                        failedAt = index,
                    )
                }
                verified += MinecraftSignedMessage(link, input.body, signature)
                candidateLink = link.advance()
                candidateTimestamp = input.body.timestampEpochMillis
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
    private val signatureVerifier: MinecraftChatSignatureVerifier,
) {
    private val mutex = Mutex()
    private var lastMessage: MinecraftSignedMessage? = null

    constructor(
        sender: Uuid,
        sessionId: Uuid,
        publicKey: MinecraftProfilePublicKey,
    ) : this(sender, sessionId, publicKey.asChatSignatureVerifier())

    suspend fun verify(
        link: SignedMessageLink,
        body: SignedMessageBody,
        signature: ByteString?,
    ): MinecraftChatVerificationResult = mutex.withLock {
        if (signature == null) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.MISSING_SIGNATURE)
        }
        if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.INVALID_SIGNATURE)
        }
        if (link.sender != sender || link.sessionId != sessionId) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.UNEXPECTED_LINK)
        }
        val message = MinecraftSignedMessage(link, body, signature)
        if (!signatureVerifier.verify(link, body, signature)) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.INVALID_SIGNATURE)
        }
        val previous = lastMessage
        if (message != previous && previous != null && !link.isDescendantOf(previous.link)) {
            return@withLock MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.OUT_OF_ORDER_INDEX)
        }
        lastMessage = message
        MinecraftChatVerificationResult.Valid(message)
    }

    suspend fun verify(
        index: Int,
        packetSender: Uuid,
        body: SignedMessageBody,
        signature: ByteString?,
    ): MinecraftChatVerificationResult {
        if (index < 0) {
            return MinecraftChatVerificationResult.Invalid(MinecraftChatChainFailure.OUT_OF_ORDER_INDEX)
        }
        return verify(SignedMessageLink(index, packetSender, sessionId), body, signature)
    }

    suspend fun lastMessage(): MinecraftSignedMessage? = mutex.withLock { lastMessage }
}

fun MinecraftProfileKeyPair.asChatSignatureSigner(): MinecraftChatSignatureSigner =
    MinecraftChatSignatureSigner { link, body -> signChatMessage(link, body) }

fun MinecraftProfilePublicKey.asChatSignatureVerifier(): MinecraftChatSignatureVerifier =
    MinecraftChatSignatureVerifier { link, body, signature -> verifyChatMessage(link, body, signature) }
