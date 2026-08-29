package com.hiczp.minecraft.world.format

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal const val DEFAULT_MINECRAFT_NAMESPACE: String = "minecraft"

internal fun validateNamespacedValue(
    namespace: String,
    path: String,
    description: String,
) {
    require(namespace.matches(NAMESPACE_PATTERN)) { "Invalid $description namespace: $namespace" }
    require(path.matches(RESOURCE_PATH_PATTERN)) { "Invalid $description path: $path" }
}

internal fun <T> parseNamespacedValue(
    value: String,
    create: (namespace: String, path: String) -> T,
): T {
    val separator = value.indexOf(':')
    return if (separator < 0) {
        create(DEFAULT_MINECRAFT_NAMESPACE, value)
    } else {
        create(value.substring(0, separator), value.substring(separator + 1))
    }
}

internal abstract class NamespacedValueSerializer<T>(
    private val serialName: String,
) : KSerializer<T> {
    final override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    final override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(value.toString())
    }

    final override fun deserialize(decoder: Decoder): T {
        val value = decoder.decodeString()
        return try {
            parse(value)
        } catch (failure: IllegalArgumentException) {
            throw SerializationException("Invalid $serialName value: $value", failure)
        }
    }

    protected abstract fun parse(value: String): T
}

internal val NAMESPACE_PATTERN: Regex = Regex("[a-z0-9._-]+")
internal val RESOURCE_PATH_PATTERN: Regex = Regex("[a-z0-9._/-]+")
