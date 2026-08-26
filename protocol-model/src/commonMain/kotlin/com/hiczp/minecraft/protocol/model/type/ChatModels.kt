@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid

/** A 256-byte chat signature or an index into the connection signature cache. */
@Serializable(with = PackedMessageSignatureSerializer::class)
sealed interface PackedMessageSignature {
    @Serializable
    data class Full(val signature: ByteString) : PackedMessageSignature {
        init {
            require(signature.size == SIGNATURE_BYTES) {
                "A full chat signature must contain $SIGNATURE_BYTES bytes"
            }
        }
    }

    @Serializable
    data class Cached(val id: Int) : PackedMessageSignature {
        init {
            require(id >= 0) { "A cached chat signature ID must be non-negative" }
        }
    }

    companion object {
        const val SIGNATURE_BYTES: Int = 256
    }
}

internal object PackedMessageSignatureSerializer :
    KSerializer<PackedMessageSignature> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.PackedMessageSignature",
    ) {
        element<Int>("messageId", annotations = listOf(VarInt()))
        element<ByteString>(
            "signature",
            annotations = listOf(FixedLength(PackedMessageSignature.SIGNATURE_BYTES)),
            isOptional = true,
        )
    }

    override fun serialize(encoder: Encoder, value: PackedMessageSignature) {
        val output = encoder.beginStructure(descriptor)
        when (value) {
            is PackedMessageSignature.Full -> {
                output.encodeIntElement(descriptor, MESSAGE_ID, 0)
                output.encodeSerializableElement(
                    descriptor,
                    SIGNATURE,
                    ByteString.serializer(),
                    value.signature,
                )
            }

            is PackedMessageSignature.Cached -> output.encodeIntElement(
                descriptor,
                MESSAGE_ID,
                value.id + 1,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PackedMessageSignature {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val messageId = input.decodeIntElement(descriptor, MESSAGE_ID)
            val packedMessageSignature = if (messageId == 0) {
                PackedMessageSignature.Full(
                    input.decodeSerializableElement(
                        descriptor,
                        SIGNATURE,
                        ByteString.serializer(),
                    ),
                )
            } else {
                PackedMessageSignature.Cached(messageId - 1)
            }
            input.endStructure(descriptor)
            return packedMessageSignature
        }

        var messageId: Int? = null
        var signature: ByteString? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                MESSAGE_ID -> messageId =
                    input.decodeIntElement(descriptor, MESSAGE_ID)

                SIGNATURE -> signature = input.decodeSerializableElement(
                    descriptor,
                    SIGNATURE,
                    ByteString.serializer(),
                )

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected PackedMessageSignature field $index",
                )
            }
        }
        input.endStructure(descriptor)
        return when (
            val id = messageId
                ?: throw SerializationException("Missing packed message ID")
        ) {
            0 -> PackedMessageSignature.Full(
                signature ?: throw SerializationException(
                    "Missing full message signature",
                ),
            )

            else -> PackedMessageSignature.Cached(id - 1)
        }
    }

    private const val MESSAGE_ID: Int = 0
    private const val SIGNATURE: Int = 1
}

@Serializable
data class ArgumentSignature(
    @MaxLength(16)
    val name: String,
    @FixedLength(256)
    val signature: ByteString,
)

@Serializable
data class LastSeenMessagesUpdate(
    @VarInt
    val offset: Int,
    @FixedLength(3)
    val acknowledged: ByteString,
    val checksum: Byte,
) {
    init {
        require(acknowledged.size == ACKNOWLEDGED_BYTES) {
            "A last-seen acknowledgement must contain $ACKNOWLEDGED_BYTES bytes"
        }
    }

    companion object {
        const val ACKNOWLEDGED_BYTES: Int = 3
    }
}

@Serializable
data class ChatSessionData(
    val sessionId: Uuid,
    val profilePublicKey: ProfilePublicKeyData,
)

@Serializable
data class ProfilePublicKeyData(
    val expiresAtEpochMillis: Long,
    @MaxByteLength(512)
    val encodedKey: ByteString,
    @MaxByteLength(4_096)
    val keySignature: ByteString,
)

@Serializable
data class SignedCommandArguments(
    @MaxCollectionSize(8)
    val entries: List<ArgumentSignature>,
)
