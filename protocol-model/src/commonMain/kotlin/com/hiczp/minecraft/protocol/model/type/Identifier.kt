package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private val namespacePattern: Regex = Regex("[a-z0-9._-]+")
private val valuePattern: Regex = Regex("[a-z0-9._/-]+")

/**
 * A namespaced Minecraft resource location. Missing namespaces are normalized
 * to `minecraft`.
 */
@Serializable
@JvmInline
value class Identifier private constructor(val value: String) {
    val namespace: String
        get() = value.substringBefore(':')

    val path: String
        get() = value.substringAfter(':')

    override fun toString(): String = value

    companion object {
        operator fun invoke(value: String): Identifier {
            val normalized = if (':' in value) value else "minecraft:$value"
            val namespace = normalized.substringBefore(':')
            val path = normalized.substringAfter(':')
            require(namespace.matches(namespacePattern)) {
                "Invalid identifier namespace: $namespace"
            }
            require(path.matches(valuePattern)) {
                "Invalid identifier path: $path"
            }
            return Identifier(normalized)
        }
    }
}
