package com.hiczp.minecraft.nbt.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/** Binary root framing used by generic [NbtFormat] operations. */
enum class NbtRootEncoding {
    /** A tag ID followed immediately by its payload, matching `readAnyTag`. */
    ANY,

    /** Writes an empty root name; reads and discards the encoded root name. */
    UNNAMED,

    /** A tag ID, [NbtFormatConfiguration.rootName], then its payload. */
    NAMED,
}

/**
 * Kotlin mapping policy and resource limits for [NbtFormat].
 *
 * NBT has no null tag. Null compound properties are omitted; a missing
 * required nullable property decodes as null, while optional properties keep
 * their Kotlin default. Null roots, list elements, and map values are rejected.
 * Polymorphic serializers are deliberately unsupported because no stable
 * discriminator contract has been selected.
 */
data class NbtFormatConfiguration(
    val serializersModule: SerializersModule = EmptySerializersModule(),
    val rootEncoding: NbtRootEncoding = NbtRootEncoding.ANY,
    val rootName: String = "",
    val encodeDefaults: Boolean = false,
    val ignoreUnknownKeys: Boolean = false,
    val strictBooleans: Boolean = true,
    val maximumDepth: Int = 512,
    val maximumCollectionSize: Int = 1_048_576,
    val maximumByteArraySize: Int = 16 * 1_048_576,
    val maximumStringBytes: Int = 65_535,
    val maximumEncodedBytes: Long = 64L * 1_048_576,
) {
    init {
        require(maximumDepth >= 0)
        require(maximumCollectionSize >= 0)
        require(maximumByteArraySize >= 0)
        require(maximumStringBytes in 0..65_535)
        require(maximumEncodedBytes >= 0)
    }
}

/** Base NBT failure in the standard kotlinx serialization hierarchy. */
open class NbtSerializationException(
    message: String,
    cause: Throwable? = null,
) : SerializationException(message, cause)

/** NBT encoding failure. */
class NbtEncodingException(
    message: String,
    cause: Throwable? = null,
) : NbtSerializationException(message, cause)

/** Malformed or unsupported NBT input. */
class NbtDecodingException(
    message: String,
    cause: Throwable? = null,
) : NbtSerializationException(message, cause)

/** Configured NBT resource limit was exceeded. */
class NbtLimitException(
    message: String,
    cause: Throwable? = null,
) : NbtSerializationException(message, cause)
