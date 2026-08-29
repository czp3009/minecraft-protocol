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
        operator fun invoke(value: String): Identifier = parse(value)

        fun parse(value: String): Identifier {
            val separator = value.indexOf(':')
            return if (separator < 0) {
                invoke("minecraft", value)
            } else {
                invoke(value.substring(0, separator), value.substring(separator + 1))
            }
        }

        operator fun invoke(namespace: String, path: String): Identifier {
            require(namespace.matches(namespacePattern)) {
                "Invalid identifier namespace: $namespace"
            }
            require(path.matches(valuePattern)) {
                "Invalid identifier path: $path"
            }
            return Identifier("$namespace:$path")
        }
    }
}
