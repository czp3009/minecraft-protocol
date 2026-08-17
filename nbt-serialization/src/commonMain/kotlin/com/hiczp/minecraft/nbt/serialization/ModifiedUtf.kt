package com.hiczp.minecraft.nbt.serialization

internal fun modifiedUtfLength(value: String): Int {
    var byteLength = 0
    for (character in value) {
        val code = character.code
        byteLength += when {
            code in 1..0x7F -> 1
            code <= 0x7FF -> 2
            else -> 3
        }
        if (byteLength > 65_535) {
            throw NbtEncodingException(
                "NBT string exceeds the modified-UTF unsigned-short limit",
            )
        }
    }
    return byteLength
}
