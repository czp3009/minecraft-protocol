package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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

internal object MinecraftArgumentSerializer :
    JsonContentPolymorphicSerializer<MinecraftArgument>(MinecraftArgument::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<MinecraftArgument> = when (element) {
        is JsonPrimitive if element.isString -> MinecraftArgument.Literal.serializer()
        is JsonObject -> MinecraftArgument.Expanded.serializer()
        else -> throw SerializationException("Minecraft argument must be a string or object")
    }
}

internal object MinecraftArgumentValueSerializer :
    JsonContentPolymorphicSerializer<MinecraftArgumentValue>(MinecraftArgumentValue::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<MinecraftArgumentValue> =
        when (element) {
            is JsonPrimitive if element.isString -> MinecraftArgumentValue.Single.serializer()
            is JsonArray -> MinecraftArgumentValue.Multiple.serializer()
            else -> throw SerializationException("Minecraft expanded argument value must be a string or string array")
        }
}
