package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** The logical server status exchanged by both protocol endpoints. */
@Serializable
data class ServerStatus(
    val description: JsonTextComponent = JsonTextComponent("\"\""),
    val players: Players? = null,
    val version: Version? = null,
    val favicon: Favicon? = null,
    val enforcesSecureChat: Boolean = false,
) {
    @Serializable
    data class Players(
        val max: Int,
        val online: Int,
        val sample: List<NameAndId> = emptyList(),
    )

    @Serializable
    data class NameAndId(
        val id: Uuid,
        val name: String,
    )

    @Serializable
    data class Version(
        val name: String,
        val protocol: Int,
    )

    @Serializable(with = ServerStatusFaviconSerializer::class)
    data class Favicon(
        val iconBytes: ByteString,
    )
}

internal object ServerStatusFaviconSerializer : KSerializer<ServerStatus.Favicon> {
    override val descriptor = PrimitiveSerialDescriptor(
        "minecraft.ServerStatus.Favicon",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: ServerStatus.Favicon) {
        encoder.encodeString("$DATA_URL_PREFIX${Base64.Default.encode(value.iconBytes.toByteArray())}")
    }

    override fun deserialize(decoder: Decoder): ServerStatus.Favicon {
        val encoded = decoder.decodeString()
        if (!encoded.startsWith(DATA_URL_PREFIX)) {
            throw SerializationException("Unknown server-status favicon format")
        }
        val payload = encoded.substring(DATA_URL_PREFIX.length).filterNot { character ->
            character == '\n' || character == '\r'
        }
        val iconBytes = try {
            Base64.Default.decode(payload)
        } catch (failure: IllegalArgumentException) {
            throw SerializationException("Malformed server-status favicon", failure)
        }
        return ServerStatus.Favicon(ByteString(iconBytes))
    }

    private const val DATA_URL_PREFIX: String = "data:image/png;base64,"
}
