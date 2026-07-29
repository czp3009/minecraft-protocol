package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Immutable, content-equality wrapper around protocol bytes. */
@Serializable(with = ByteStringSerializer::class)
class ByteString(bytes: ByteArray) {
    private val storage: ByteArray = bytes.copyOf()

    val size: Int
        get() = storage.size

    operator fun get(index: Int): Byte = storage[index]

    fun toByteArray(): ByteArray = storage.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ByteString && storage.contentEquals(other.storage)

    override fun hashCode(): Int = storage.contentHashCode()

    override fun toString(): String = storage.contentToString()
}

internal object ByteStringSerializer : KSerializer<ByteString> {
    private val delegate: KSerializer<ByteArray> = ByteArraySerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ByteString) {
        encoder.encodeSerializableValue(delegate, value.toByteArray())
    }

    override fun deserialize(decoder: Decoder): ByteString =
        ByteString(decoder.decodeSerializableValue(delegate))
}
