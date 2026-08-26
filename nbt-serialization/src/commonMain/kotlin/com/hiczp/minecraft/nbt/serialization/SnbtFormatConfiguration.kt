package com.hiczp.minecraft.nbt.serialization

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/** Resolves one Java-style Unicode character name used by an SNBT `\N{name}` escape. */
fun interface SnbtUnicodeNameResolver {
    /** Returns the Unicode code point for [name], or `null` when the name is unknown. */
    fun resolve(name: String): Int?
}

/**
 * Kotlin mapping and textual-output policy for [SnbtFormat].
 *
 * The Kotlin Multiplatform standard library has no Unicode character-name
 * database. Numeric `\x`, `\u`, and `\U` escapes work without configuration;
 * callers that need the official `\N{name}` extension can supply
 * [snbtUnicodeNameResolver]. The writer never emits name escapes.
 */
data class SnbtFormatConfiguration(
    val serializersModule: SerializersModule = EmptySerializersModule(),
    val encodeDefaults: Boolean = false,
    val ignoreUnknownKeys: Boolean = false,
    val strictBooleans: Boolean = true,
    val sortCompoundKeys: Boolean = true,
    val snbtUnicodeNameResolver: SnbtUnicodeNameResolver? = null,
)

internal fun SnbtFormatConfiguration.treeConfiguration(): NbtFormatConfiguration =
    NbtFormatConfiguration(
        serializersModule = serializersModule,
        encodeDefaults = encodeDefaults,
        ignoreUnknownKeys = ignoreUnknownKeys,
        strictBooleans = strictBooleans,
    )
