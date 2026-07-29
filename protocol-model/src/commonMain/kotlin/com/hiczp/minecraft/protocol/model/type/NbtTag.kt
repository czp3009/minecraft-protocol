package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Format-neutral representation of the complete NBT value algebra.
 *
 * Named-root handling belongs to the Minecraft binary format; packet NBT uses
 * unnamed network NBT.
 */
@Serializable
sealed interface NbtTag

@Serializable
@SerialName("end")
data object NbtEnd : NbtTag

@Serializable
@SerialName("byte")
data class NbtByte(val value: Byte) : NbtTag

@Serializable
@SerialName("short")
data class NbtShort(val value: Short) : NbtTag

@Serializable
@SerialName("int")
data class NbtInt(val value: Int) : NbtTag

@Serializable
@SerialName("long")
data class NbtLong(val value: Long) : NbtTag

@Serializable
@SerialName("float")
data class NbtFloat(val value: Float) : NbtTag

@Serializable
@SerialName("double")
data class NbtDouble(val value: Double) : NbtTag

@Serializable
@SerialName("byte_array")
class NbtByteArray(val value: ByteArray) : NbtTag {
    override fun equals(other: Any?): Boolean =
        other is NbtByteArray && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
}

@Serializable
@SerialName("string")
data class NbtString(val value: String) : NbtTag

@Serializable
@SerialName("list")
data class NbtList(val value: List<NbtTag>) : NbtTag {
    init {
        require(value.isEmpty() || value.all { it::class == value.first()::class }) {
            "NBT lists must contain one tag type"
        }
        require(value.none { it === NbtEnd }) { "NBT lists cannot contain END tags" }
    }
}

@Serializable
@SerialName("compound")
data class NbtCompound(val value: Map<String, NbtTag>) : NbtTag

@Serializable
@SerialName("int_array")
class NbtIntArray(val value: IntArray) : NbtTag {
    override fun equals(other: Any?): Boolean =
        other is NbtIntArray && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
}

@Serializable
@SerialName("long_array")
class NbtLongArray(val value: LongArray) : NbtTag {
    override fun equals(other: Any?): Boolean =
        other is NbtLongArray && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
}

/** A protocol text component encoded as an unnamed NBT tag. */
@Serializable
data class TextComponent(val value: NbtTag) {
    companion object {
        fun literal(text: String): TextComponent = TextComponent(NbtString(text))
    }
}
