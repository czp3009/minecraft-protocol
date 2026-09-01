package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.jvm.JvmInline

@Serializable
data class MinecraftArguments(
    @SerialName("default-user-jvm")
    val defaultUserJvm: List<MinecraftArgument>,
    val game: List<MinecraftArgument>,
    val jvm: List<MinecraftArgument>,
)

@Serializable(with = MinecraftArgumentSerializer::class)
sealed interface MinecraftArgument {
    @JvmInline
    @Serializable
    value class Literal(
        val value: String,
    ) : MinecraftArgument

    @Serializable
    data class Expanded(
        val rules: List<MinecraftRule> = emptyList(),
        val value: MinecraftArgumentValue,
    ) : MinecraftArgument
}

@Serializable(with = MinecraftArgumentValueSerializer::class)
sealed interface MinecraftArgumentValue {
    @JvmInline
    @Serializable
    value class Single(
        val value: String,
    ) : MinecraftArgumentValue

    @JvmInline
    @Serializable
    value class Multiple(
        val values: List<String>,
    ) : MinecraftArgumentValue
}

object MinecraftArgumentSerializer : KSerializer<MinecraftArgument> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.hiczp.minecraft.distribution.metadata.MinecraftArgument")

    override fun deserialize(decoder: Decoder): MinecraftArgument {
        val jsonDecoder = decoder.requireJsonDecoder("Minecraft arguments can only be decoded from JSON")
        return when (val jsonElement = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!jsonElement.isString) {
                    throw SerializationException("Minecraft argument literal must be a string")
                }
                jsonDecoder.json.decodeFromJsonElement(MinecraftArgument.Literal.serializer(), jsonElement)
            }

            is JsonObject ->
                jsonDecoder.json.decodeFromJsonElement(MinecraftArgument.Expanded.serializer(), jsonElement)

            else -> throw SerializationException("Minecraft argument must be a string or object")
        }
    }

    override fun serialize(encoder: Encoder, value: MinecraftArgument) {
        val jsonEncoder = encoder.requireJsonEncoder("Minecraft arguments can only be encoded as JSON")
        val jsonElement = when (value) {
            is MinecraftArgument.Literal ->
                jsonEncoder.json.encodeToJsonElement(MinecraftArgument.Literal.serializer(), value)

            is MinecraftArgument.Expanded ->
                jsonEncoder.json.encodeToJsonElement(MinecraftArgument.Expanded.serializer(), value)
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }
}

object MinecraftArgumentValueSerializer : KSerializer<MinecraftArgumentValue> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.hiczp.minecraft.distribution.metadata.MinecraftArgumentValue")

    override fun deserialize(decoder: Decoder): MinecraftArgumentValue {
        val jsonDecoder = decoder.requireJsonDecoder("Minecraft argument values can only be decoded from JSON")
        return when (val jsonElement = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!jsonElement.isString) {
                    throw SerializationException("Minecraft expanded argument value must be a string or string array")
                }
                jsonDecoder.json.decodeFromJsonElement(MinecraftArgumentValue.Single.serializer(), jsonElement)
            }

            is JsonArray ->
                jsonDecoder.json.decodeFromJsonElement(MinecraftArgumentValue.Multiple.serializer(), jsonElement)

            else -> throw SerializationException("Minecraft expanded argument value must be a string or string array")
        }
    }

    override fun serialize(encoder: Encoder, value: MinecraftArgumentValue) {
        val jsonEncoder = encoder.requireJsonEncoder("Minecraft argument values can only be encoded as JSON")
        val jsonElement = when (value) {
            is MinecraftArgumentValue.Single ->
                jsonEncoder.json.encodeToJsonElement(MinecraftArgumentValue.Single.serializer(), value)

            is MinecraftArgumentValue.Multiple ->
                jsonEncoder.json.encodeToJsonElement(MinecraftArgumentValue.Multiple.serializer(), value)
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }
}

private fun Decoder.requireJsonDecoder(message: String): JsonDecoder = this as? JsonDecoder
    ?: throw SerializationException(message)

private fun Encoder.requireJsonEncoder(message: String): JsonEncoder = this as? JsonEncoder
    ?: throw SerializationException(message)
