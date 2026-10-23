package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongArraySerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A set of nonnegative bit indices. Trailing zero words are normalized and the supplied array is copied.
 * The logical serializer exposes Long words; the packet format uses the official compact little-endian byte form.
 */
@Serializable(with = BitSetSerializer::class)
class BitSet(words: LongArray) {
    private val storage = words.copyOf(words.indexOfLast { it != 0L } + 1)

    val words: LongArray
        get() = storage.copyOf()

    operator fun get(bit: Int): Boolean {
        require(bit >= 0)
        val word = bit / Long.SIZE_BITS
        return word < storage.size &&
                storage[word] and (1L shl (bit % Long.SIZE_BITS)) != 0L
    }

    override fun equals(other: Any?): Boolean =
        other is BitSet && storage.contentEquals(other.storage)

    override fun hashCode(): Int = storage.contentHashCode()

    override fun toString(): String = storage.contentToString()
}

internal object BitSetSerializer : KSerializer<BitSet> {
    private val delegate = LongArraySerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: BitSet) {
        encoder.encodeSerializableValue(delegate, value.words)
    }

    override fun deserialize(decoder: Decoder): BitSet =
        BitSet(decoder.decodeSerializableValue(delegate))
}
