package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*

internal const val TAG_END: Int = 0
internal const val TAG_BYTE: Int = 1
internal const val TAG_SHORT: Int = 2
internal const val TAG_INT: Int = 3
internal const val TAG_LONG: Int = 4
internal const val TAG_FLOAT: Int = 5
internal const val TAG_DOUBLE: Int = 6
internal const val TAG_BYTE_ARRAY: Int = 7
internal const val TAG_STRING: Int = 8
internal const val TAG_LIST: Int = 9
internal const val TAG_COMPOUND: Int = 10
internal const val TAG_INT_ARRAY: Int = 11
internal const val TAG_LONG_ARRAY: Int = 12

internal fun typeOf(nbtTag: NbtTag): Int = when (nbtTag) {
    NbtEnd -> TAG_END
    is NbtByte -> TAG_BYTE
    is NbtShort -> TAG_SHORT
    is NbtInt -> TAG_INT
    is NbtLong -> TAG_LONG
    is NbtFloat -> TAG_FLOAT
    is NbtDouble -> TAG_DOUBLE
    is NbtByteArray -> TAG_BYTE_ARRAY
    is NbtString -> TAG_STRING
    is NbtList -> TAG_LIST
    is NbtCompound -> TAG_COMPOUND
    is NbtIntArray -> TAG_INT_ARRAY
    is NbtLongArray -> TAG_LONG_ARRAY
}

internal fun rawListType(elements: List<NbtTag>): Int {
    var type = TAG_END
    for (element in elements) {
        val next = typeOf(element)
        if (type == TAG_END) {
            type = next
        } else if (type != next) {
            return TAG_COMPOUND
        }
    }
    return type
}

internal fun rawListType(nbtList: NbtList): Int {
    var type = TAG_END
    for (index in 0 until nbtList.size) {
        val element = nbtList[index]
        val next = typeOf(element)
        if (type == TAG_END) {
            type = next
        } else if (type != next) {
            return TAG_COMPOUND
        }
    }
    return type
}

internal fun NbtCompound.isListWrapper(): Boolean =
    size == 1 && this[""] != null

internal fun NbtCompound.unwrapListElement(): NbtTag {
    return if (size == 1) this[""] ?: this else this
}

internal fun validateType(type: Int) {
    if (type !in TAG_END..TAG_LONG_ARRAY) {
        throw NbtBinaryFormatException("Unknown NBT tag type: $type")
    }
}
