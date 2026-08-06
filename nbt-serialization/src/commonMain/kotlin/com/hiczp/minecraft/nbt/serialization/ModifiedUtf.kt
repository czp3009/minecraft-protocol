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
            throw NbtLimitException(
                "NBT string exceeds the modified-UTF unsigned-short limit",
            )
        }
    }
    return byteLength
}

internal fun encodeModifiedUtf(value: String): ByteArray {
    val result = ByteArray(modifiedUtfLength(value))
    var index = 0
    for (character in value) {
        val code = character.code
        when {
            code in 1..0x7F -> {
                result[index++] = code.toByte()
            }

            code <= 0x7FF -> {
                result[index++] = (0xC0 or (code shr 6)).toByte()
                result[index++] = (0x80 or (code and 0x3F)).toByte()
            }

            else -> {
                result[index++] = (0xE0 or (code shr 12)).toByte()
                result[index++] =
                    (0x80 or ((code shr 6) and 0x3F)).toByte()
                result[index++] = (0x80 or (code and 0x3F)).toByte()
            }
        }
    }
    return result
}

internal fun decodeModifiedUtf(bytes: ByteArray): String {
    val characters = CharArray(bytes.size)
    var byteIndex = 0
    var characterIndex = 0

    while (byteIndex < bytes.size) {
        val first = bytes[byteIndex].toInt() and 0xFF
        when {
            first <= 0x7F -> {
                characters[characterIndex++] = first.toChar()
                byteIndex++
            }

            first shr 4 == 0xC || first shr 4 == 0xD -> {
                if (byteIndex + 1 >= bytes.size) malformedModifiedUtf(byteIndex)
                val second = bytes[byteIndex + 1].toInt() and 0xFF
                if (second and 0xC0 != 0x80) malformedModifiedUtf(byteIndex)
                characters[characterIndex++] =
                    (((first and 0x1F) shl 6) or (second and 0x3F)).toChar()
                byteIndex += 2
            }

            first shr 4 == 0xE -> {
                if (byteIndex + 2 >= bytes.size) malformedModifiedUtf(byteIndex)
                val second = bytes[byteIndex + 1].toInt() and 0xFF
                val third = bytes[byteIndex + 2].toInt() and 0xFF
                if (second and 0xC0 != 0x80 || third and 0xC0 != 0x80) {
                    malformedModifiedUtf(byteIndex)
                }
                characters[characterIndex++] =
                    (((first and 0x0F) shl 12) or
                            ((second and 0x3F) shl 6) or
                            (third and 0x3F)).toChar()
                byteIndex += 3
            }

            else -> malformedModifiedUtf(byteIndex)
        }
    }
    return characters.concatToString(endIndex = characterIndex)
}

private fun malformedModifiedUtf(index: Int): Nothing =
    throw NbtDecodingException("Malformed modified UTF-8 at byte $index")
