package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Official handshake intentions and their explicit wire IDs. Zero is not a valid intention. */
@Serializable(with = ClientIntentSerializer::class)
enum class ClientIntent(val id: Int) {
    STATUS(1),
    LOGIN(2),
    TRANSFER(3),
}

@Serializable
private data class ClientIntentId(@VarInt val id: Int)

internal object ClientIntentSerializer : KSerializer<ClientIntent> {
    private val serializer = ClientIntentId.serializer()
    override val descriptor = serializer.descriptor

    override fun serialize(encoder: Encoder, value: ClientIntent) {
        encoder.encodeSerializableValue(serializer, ClientIntentId(value.id))
    }

    override fun deserialize(decoder: Decoder): ClientIntent {
        val id = decoder.decodeSerializableValue(serializer).id
        return ClientIntent.entries.firstOrNull { it.id == id }
            ?: throw SerializationException("Invalid client intention ID: $id")
    }
}
