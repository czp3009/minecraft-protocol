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
 * inputs. Bulk value accessors return defensive copies; size, indexed access,
 * and iteration read the immutable snapshot without exposing mutable storage.
 */
@Serializable(with = NbtTagTreeSerializer::class)
sealed interface NbtTag

/** Logical serializer handoff for formats that encode an NBT tree directly. */
interface NbtTagEncoder {
    fun encodeNbtTag(nbtTag: NbtTag)
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
    final override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    final override fun serialize(encoder: Encoder, value: T) {
        val nbtTagEncoder = encoder as? NbtTagEncoder
            ?: throw SerializationException(
                "${encoder::class.simpleName ?: "Encoder"} cannot encode raw NBT tags",
            )
        nbtTagEncoder.encodeNbtTag(value)
    }

    final override fun deserialize(decoder: Decoder): T {
        val nbtTagDecoder = decoder as? NbtTagDecoder
            ?: throw SerializationException(
                "${decoder::class.simpleName ?: "Decoder"} cannot decode raw NBT tags",
            )
        return deserializeTag(nbtTagDecoder.decodeNbtTag())
    }

    fun deserializeTag(nbtTag: NbtTag): T = cast(nbtTag)

    protected abstract fun cast(nbtTag: NbtTag): T

    protected fun wrongType(expected: String, nbtTag: NbtTag): Nothing =
        throw SerializationException(
            "Expected $expected, got ${nbtTag::class.simpleName ?: "an unknown NBT tag"}",
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

    /** Number of bytes in this immutable tag. */
    val size: Int
        get() = snapshot.size

    /** Returns a defensive copy of this tag's bytes. */
    val value: ByteArray
        get() = snapshot.copyOf()

    /** Returns the byte at [index] without copying the array. */
    operator fun get(index: Int): Byte = snapshot[index]

    /** Visits the immutable bytes without creating a defensive array copy. */
    fun forEach(action: (Byte) -> Unit) = snapshot.forEach(action)

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

    /** Number of elements in this immutable list. */
    val size: Int
        get() = snapshot.size

    /** Returns a defensive copy of this list. */
    val value: List<NbtTag>
        get() = snapshot.toList()

    /** Returns the element at [index] without copying the list. */
    operator fun get(index: Int): NbtTag = snapshot[index]

    /** Visits the immutable elements without creating a list copy. */
    fun forEach(action: (NbtTag) -> Unit) = snapshot.forEach(action)

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

    /** Number of named tags in this immutable compound. */
    val size: Int
        get() = snapshot.size

    /** Returns a defensive insertion-ordered copy of this compound. */
    val value: Map<String, NbtTag>
        get() = LinkedHashMap(snapshot)

    /** Returns the tag named [name] without copying the compound. */
    operator fun get(name: String): NbtTag? = snapshot[name]

    /** Visits entries in insertion order without creating a map copy. */
    fun forEachEntry(action: (String, NbtTag) -> Unit) {
        snapshot.forEach { (name, nbtTag) -> action(name, nbtTag) }
    }

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

    /** Number of integers in this immutable tag. */
    val size: Int
        get() = snapshot.size

    /** Returns a defensive copy of this tag's integers. */
    val value: IntArray
        get() = snapshot.copyOf()

    /** Returns the integer at [index] without copying the array. */
    operator fun get(index: Int): Int = snapshot[index]

    /** Visits the immutable integers without creating an array copy. */
    fun forEach(action: (Int) -> Unit) = snapshot.forEach(action)

    override fun equals(other: Any?): Boolean =
        other is NbtIntArray && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "NbtIntArray(${snapshot.contentToString()})"
}

@Serializable(with = NbtLongArraySerializer::class)
class NbtLongArray(value: LongArray) : NbtTag {
    private val snapshot = value.copyOf()

    /** Number of longs in this immutable tag. */
    val size: Int
        get() = snapshot.size

    /** Returns a defensive copy of this tag's longs. */
    val value: LongArray
        get() = snapshot.copyOf()

    /** Returns the long at [index] without copying the array. */
    operator fun get(index: Int): Long = snapshot[index]

    /** Visits the immutable longs without creating an array copy. */
    fun forEach(action: (Long) -> Unit) = snapshot.forEach(action)

    override fun equals(other: Any?): Boolean =
        other is NbtLongArray && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "NbtLongArray(${snapshot.contentToString()})"
}

object NbtTagTreeSerializer : NbtTagSerializer<NbtTag>("com.hiczp.minecraft.nbt.NbtTag") {
    override fun cast(nbtTag: NbtTag): NbtTag = nbtTag
}

object NbtEndSerializer : NbtTagSerializer<NbtEnd>("com.hiczp.minecraft.nbt.NbtEnd") {
    override fun cast(nbtTag: NbtTag): NbtEnd =
        nbtTag as? NbtEnd ?: wrongType("TAG_End", nbtTag)
}

object NbtByteSerializer : NbtTagSerializer<NbtByte>("com.hiczp.minecraft.nbt.NbtByte") {
    override fun cast(nbtTag: NbtTag): NbtByte =
        nbtTag as? NbtByte ?: wrongType("TAG_Byte", nbtTag)
}

object NbtShortSerializer : NbtTagSerializer<NbtShort>("com.hiczp.minecraft.nbt.NbtShort") {
    override fun cast(nbtTag: NbtTag): NbtShort =
        nbtTag as? NbtShort ?: wrongType("TAG_Short", nbtTag)
}

object NbtIntSerializer : NbtTagSerializer<NbtInt>("com.hiczp.minecraft.nbt.NbtInt") {
    override fun cast(nbtTag: NbtTag): NbtInt =
        nbtTag as? NbtInt ?: wrongType("TAG_Int", nbtTag)
}

object NbtLongSerializer : NbtTagSerializer<NbtLong>("com.hiczp.minecraft.nbt.NbtLong") {
    override fun cast(nbtTag: NbtTag): NbtLong =
        nbtTag as? NbtLong ?: wrongType("TAG_Long", nbtTag)
}

object NbtFloatSerializer : NbtTagSerializer<NbtFloat>("com.hiczp.minecraft.nbt.NbtFloat") {
    override fun cast(nbtTag: NbtTag): NbtFloat =
        nbtTag as? NbtFloat ?: wrongType("TAG_Float", nbtTag)
}

object NbtDoubleSerializer : NbtTagSerializer<NbtDouble>("com.hiczp.minecraft.nbt.NbtDouble") {
    override fun cast(nbtTag: NbtTag): NbtDouble =
        nbtTag as? NbtDouble ?: wrongType("TAG_Double", nbtTag)
}

object NbtByteArraySerializer : NbtTagSerializer<NbtByteArray>("com.hiczp.minecraft.nbt.NbtByteArray") {
    override fun cast(nbtTag: NbtTag): NbtByteArray =
        nbtTag as? NbtByteArray ?: wrongType("TAG_Byte_Array", nbtTag)
}

object NbtStringSerializer : NbtTagSerializer<NbtString>("com.hiczp.minecraft.nbt.NbtString") {
    override fun cast(nbtTag: NbtTag): NbtString =
        nbtTag as? NbtString ?: wrongType("TAG_String", nbtTag)
}

object NbtListSerializer : NbtTagSerializer<NbtList>("com.hiczp.minecraft.nbt.NbtList") {
    override fun cast(nbtTag: NbtTag): NbtList =
        nbtTag as? NbtList ?: wrongType("TAG_List", nbtTag)
}

object NbtCompoundSerializer : NbtTagSerializer<NbtCompound>("com.hiczp.minecraft.nbt.NbtCompound") {
    override fun cast(nbtTag: NbtTag): NbtCompound =
        nbtTag as? NbtCompound ?: wrongType("TAG_Compound", nbtTag)
}

object NbtIntArraySerializer : NbtTagSerializer<NbtIntArray>("com.hiczp.minecraft.nbt.NbtIntArray") {
    override fun cast(nbtTag: NbtTag): NbtIntArray =
        nbtTag as? NbtIntArray ?: wrongType("TAG_Int_Array", nbtTag)
}

object NbtLongArraySerializer : NbtTagSerializer<NbtLongArray>("com.hiczp.minecraft.nbt.NbtLongArray") {
    override fun cast(nbtTag: NbtTag): NbtLongArray =
        nbtTag as? NbtLongArray ?: wrongType("TAG_Long_Array", nbtTag)
}
