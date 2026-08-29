@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.wire

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.type.Vector3d
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Physical wire hints consumed by MinecraftProtocolFormat. Other formats may honor or
 * ignore them according to their own contracts. Logical field presence is
 * expressed by the model serializer instead.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class VarInt

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class VarLong

/**
 * Encode a nullable non-negative Int as VarInt(value + 1), with zero denoting
 * absence. This is distinct from Boolean-prefixed protocol optionals.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class OptionalVarInt

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class UnsignedByte

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class UnsignedShort

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Angle

/**
 * Encode a [Vector3d] with vanilla's
 * six-byte low-precision vector representation plus its optional scale VarInt.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class LowPrecisionVector

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MaxLength(val characters: Int)

/** Encode the annotated logical value as one JSON protocol string. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class JsonEncoded

/** A byte array whose length is known from surrounding protocol context. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class FixedLength(val bytes: Int)

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MaxByteLength(val bytes: Int)

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MaxCollectionSize(val elements: Int)

/**
 * Wrap the encoded value in a VarInt byte-length prefix and reject payloads
 * larger than [maxBytes].
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ByteLengthPrefixed(val maxBytes: Int = Int.MAX_VALUE)

/** A byte array occupying all bytes left in the current packet payload. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class RemainingBytes

/**
 * A chunk-section collection whose element count comes from the active
 * dimension rather than from the packet bytes.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ChunkSectionCount

/**
 * A collection with no length prefix. Its element count must be known by the
 * containing serializer.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Unprefixed

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class VarIntElements

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class VarLongElements

/**
 * Encode a nullable NBT value as the NBT TAG_End sentinel instead of the
 * ordinary Boolean-prefixed nullable representation.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class NbtEndOptional

/**
 * Encode a nullable value without the ordinary Boolean presence marker. The
 * signed byte [value] denotes null; every other byte starts the ordinary
 * encoding of the non-null value.
 *
 * This is used by byte-backed enums such as the previous game mode, where
 * vanilla reserves -1 for absence.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class NullSentinelByte(val value: Int = -1)

/**
 * Declares that a protocol field uses no-name any-tag network NBT. The
 * Minecraft format validates that the annotated serializer represents an
 * [NbtTag]; NBT tag serializers use the same wire form without this marker.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class NetworkNbt

/**
 * Encode a paletted container with the block-state or biome strategy selected
 * by [kind]. The bits-per-entry discriminator and packed data length are part
 * of the Minecraft format rather than the ordinary kotlinx collection shape.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Paletted(val kind: PaletteKind)

enum class PaletteKind {
    BLOCK_STATES,
    BIOMES,
}

/**
 * An enum encoded with the supplied integral values rather than its ordinal.
 * Prefer a dedicated logical serializer for sparse or conditional unions.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EnumEncoding(val kind: EnumEncodingKind)

/** Wrap an out-of-range numeric enum ID with positive modulo, as vanilla does. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class WrappedEnum

/** Clamp an out-of-range numeric enum ID to the nearest valid ordinal. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ClampEnum

/** Map every out-of-range numeric enum ID to ordinal zero, as selected codecs do. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ZeroFallbackEnum

enum class EnumEncodingKind {
    BYTE,
    UNSIGNED_BYTE,
    VAR_INT,
    INT,
}
