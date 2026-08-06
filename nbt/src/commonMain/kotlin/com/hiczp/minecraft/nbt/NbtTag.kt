package com.hiczp.minecraft.nbt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Format-neutral representation of the complete Java Edition NBT value
 * algebra.
 *
 * Containers and primitive arrays take immutable snapshots of constructor
 * inputs. Container and array accessors return defensive copies, so a tag
 * cannot change after construction.
 */
@Serializable(with = NbtTagTreeSerializer::class)
sealed interface NbtTag

/** Logical serializer handoff for formats that encode an NBT tree directly. */
interface NbtTagEncoder {
    fun encodeNbtTag(value: NbtTag)
}

/** Logical deserializer handoff for formats that decode an NBT tree directly. */
interface NbtTagDecoder {
    fun decodeNbtTag(): NbtTag
}

/**
 * Marker and implementation base for serializers backed by [NbtTagEncoder]
 * and [NbtTagDecoder]. It intentionally exposes no binary or stream API.
 */
abstract class NbtTagSerializer<T : NbtTag>(
    serialName: String,
) : KSerializer<T> {
    final override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(serialName)

    final override fun serialize(encoder: Encoder, value: T) {
        val tagEncoder = encoder as? NbtTagEncoder
            ?: throw SerializationException(
                "${encoder::class.simpleName ?: "Encoder"} cannot encode raw NBT tags",
            )
        tagEncoder.encodeNbtTag(value)
    }

    final override fun deserialize(decoder: Decoder): T {
        val tagDecoder = decoder as? NbtTagDecoder
            ?: throw SerializationException(
                "${decoder::class.simpleName ?: "Decoder"} cannot decode raw NBT tags",
            )
        return deserializeTag(tagDecoder.decodeNbtTag())
    }

    fun deserializeTag(tag: NbtTag): T = cast(tag)

    protected abstract fun cast(tag: NbtTag): T

    protected fun wrongType(expected: String, tag: NbtTag): Nothing =
        throw SerializationException(
            "Expected $expected, got ${tag::class.simpleName ?: "an unknown NBT tag"}",
        )
}

@Serializable(with = NbtEndSerializer::class)
data object NbtEnd : NbtTag

@Serializable(with = NbtByteSerializer::class)
data class NbtByte(val value: Byte) : NbtTag

@Serializable(with = NbtShortSerializer::class)
data class NbtShort(val value: Short) : NbtTag

@Serializable(with = NbtIntSerializer::class)
data class NbtInt(val value: Int) : NbtTag

@Serializable(with = NbtLongSerializer::class)
data class NbtLong(val value: Long) : NbtTag

@Serializable(with = NbtFloatSerializer::class)
data class NbtFloat(val value: Float) : NbtTag

@Serializable(with = NbtDoubleSerializer::class)
data class NbtDouble(val value: Double) : NbtTag

@Serializable(with = NbtByteArraySerializer::class)
class NbtByteArray(value: ByteArray) : NbtTag {
    private val snapshot = value.copyOf()

    val value: ByteArray
        get() = snapshot.copyOf()

    override fun equals(other: Any?): Boolean =
        other is NbtByteArray && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "NbtByteArray(${snapshot.contentToString()})"
}

@Serializable(with = NbtStringSerializer::class)
data class NbtString(val value: String) : NbtTag

/**
 * A logical NBT list.
 *
 * Minecraft 26.2 permits mixed logical element types and wraps them into a
 * homogeneous compound list on the binary wire. END still has no list-value
 * representation and is rejected here.
 */
@Serializable(with = NbtListSerializer::class)
class NbtList(value: List<NbtTag>) : NbtTag {
    private val snapshot: List<NbtTag> = value.toList()

    val value: List<NbtTag>
        get() = snapshot.toList()

    init {
        require(snapshot.none { it === NbtEnd }) {
            "NBT lists cannot contain END tags"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NbtList && snapshot == other.snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = "NbtList($snapshot)"
}

@Serializable(with = NbtCompoundSerializer::class)
class NbtCompound(value: Map<String, NbtTag>) : NbtTag {
    private val snapshot: Map<String, NbtTag> = LinkedHashMap(value)

    val value: Map<String, NbtTag>
        get() = LinkedHashMap(snapshot)

    init {
        require(snapshot.values.none { it === NbtEnd }) {
            "NBT compounds cannot contain END tags"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NbtCompound && snapshot == other.snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = "NbtCompound($snapshot)"
}

@Serializable(with = NbtIntArraySerializer::class)
class NbtIntArray(value: IntArray) : NbtTag {
    private val snapshot = value.copyOf()

    val value: IntArray
        get() = snapshot.copyOf()

    override fun equals(other: Any?): Boolean =
        other is NbtIntArray && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "NbtIntArray(${snapshot.contentToString()})"
}

@Serializable(with = NbtLongArraySerializer::class)
class NbtLongArray(value: LongArray) : NbtTag {
    private val snapshot = value.copyOf()

    val value: LongArray
        get() = snapshot.copyOf()

    override fun equals(other: Any?): Boolean =
        other is NbtLongArray && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "NbtLongArray(${snapshot.contentToString()})"
}

object NbtTagTreeSerializer : NbtTagSerializer<NbtTag>("com.hiczp.minecraft.nbt.NbtTag") {
    override fun cast(tag: NbtTag): NbtTag = tag
}

object NbtEndSerializer : NbtTagSerializer<NbtEnd>("com.hiczp.minecraft.nbt.NbtEnd") {
    override fun cast(tag: NbtTag): NbtEnd =
        tag as? NbtEnd ?: wrongType("TAG_End", tag)
}

object NbtByteSerializer : NbtTagSerializer<NbtByte>("com.hiczp.minecraft.nbt.NbtByte") {
    override fun cast(tag: NbtTag): NbtByte =
        tag as? NbtByte ?: wrongType("TAG_Byte", tag)
}

object NbtShortSerializer : NbtTagSerializer<NbtShort>("com.hiczp.minecraft.nbt.NbtShort") {
    override fun cast(tag: NbtTag): NbtShort =
        tag as? NbtShort ?: wrongType("TAG_Short", tag)
}

object NbtIntSerializer : NbtTagSerializer<NbtInt>("com.hiczp.minecraft.nbt.NbtInt") {
    override fun cast(tag: NbtTag): NbtInt =
        tag as? NbtInt ?: wrongType("TAG_Int", tag)
}

object NbtLongSerializer : NbtTagSerializer<NbtLong>("com.hiczp.minecraft.nbt.NbtLong") {
    override fun cast(tag: NbtTag): NbtLong =
        tag as? NbtLong ?: wrongType("TAG_Long", tag)
}

object NbtFloatSerializer : NbtTagSerializer<NbtFloat>("com.hiczp.minecraft.nbt.NbtFloat") {
    override fun cast(tag: NbtTag): NbtFloat =
        tag as? NbtFloat ?: wrongType("TAG_Float", tag)
}

object NbtDoubleSerializer : NbtTagSerializer<NbtDouble>("com.hiczp.minecraft.nbt.NbtDouble") {
    override fun cast(tag: NbtTag): NbtDouble =
        tag as? NbtDouble ?: wrongType("TAG_Double", tag)
}

object NbtByteArraySerializer : NbtTagSerializer<NbtByteArray>("com.hiczp.minecraft.nbt.NbtByteArray") {
    override fun cast(tag: NbtTag): NbtByteArray =
        tag as? NbtByteArray ?: wrongType("TAG_Byte_Array", tag)
}

object NbtStringSerializer : NbtTagSerializer<NbtString>("com.hiczp.minecraft.nbt.NbtString") {
    override fun cast(tag: NbtTag): NbtString =
        tag as? NbtString ?: wrongType("TAG_String", tag)
}

object NbtListSerializer : NbtTagSerializer<NbtList>("com.hiczp.minecraft.nbt.NbtList") {
    override fun cast(tag: NbtTag): NbtList =
        tag as? NbtList ?: wrongType("TAG_List", tag)
}

object NbtCompoundSerializer : NbtTagSerializer<NbtCompound>("com.hiczp.minecraft.nbt.NbtCompound") {
    override fun cast(tag: NbtTag): NbtCompound =
        tag as? NbtCompound ?: wrongType("TAG_Compound", tag)
}

object NbtIntArraySerializer : NbtTagSerializer<NbtIntArray>("com.hiczp.minecraft.nbt.NbtIntArray") {
    override fun cast(tag: NbtTag): NbtIntArray =
        tag as? NbtIntArray ?: wrongType("TAG_Int_Array", tag)
}

object NbtLongArraySerializer : NbtTagSerializer<NbtLongArray>("com.hiczp.minecraft.nbt.NbtLongArray") {
    override fun cast(tag: NbtTag): NbtLongArray =
        tag as? NbtLongArray ?: wrongType("TAG_Long_Array", tag)
}
