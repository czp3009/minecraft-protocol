package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.PackedMessageSignature
import kotlin.uuid.Uuid

/** The sender/session-local position covered by a signed chat message. This value is not sent as a wire record. */
data class SignedMessageLink(
    val index: Int,
    val sender: Uuid,
    val sessionId: Uuid,
) {
    init {
        require(index >= 0) { "A signed-message index must be non-negative" }
    }

    fun advance(): SignedMessageLink? = if (index == Int.MAX_VALUE) null else copy(index = index + 1)

    fun isDescendantOf(ancestor: SignedMessageLink): Boolean =
        index > ancestor.index && sender == ancestor.sender && sessionId == ancestor.sessionId
}

/** The reconstructed message body covered by a player chat signature, not its packed network representation. */
data class SignedMessageBody(
    val content: String,
    val timestampEpochMillis: Long,
    val salt: Long,
    val lastSeen: List<ByteString>,
) {
    init {
        require(lastSeen.size <= MAX_LAST_SEEN_MESSAGES) {
            "A signed message cannot reference more than $MAX_LAST_SEEN_MESSAGES last-seen messages"
        }
        require(lastSeen.all { it.size == PackedMessageSignature.SIGNATURE_BYTES }) {
            "Every last-seen message signature must contain ${PackedMessageSignature.SIGNATURE_BYTES} bytes"
        }
    }

    companion object {
        const val MAX_LAST_SEEN_MESSAGES: Int = 20
    }
}

/** A Brigadier-derived argument whose exact parsed value is covered by a command signature. */
data class SignableCommandArgument(
    val name: String,
    val value: String,
) {
    init {
        require(name.length <= MAX_NAME_LENGTH) {
            "A signable command argument name cannot exceed $MAX_NAME_LENGTH characters"
        }
    }

    companion object {
        const val MAX_NAME_LENGTH: Int = 16
    }
}
