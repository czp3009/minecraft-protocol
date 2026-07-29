package com.hiczp.minecraft.protocol.auth

internal object Md5 {
    fun digest(input: ByteArray): ByteArray {
        val padded = pad(input, littleEndianLength = true)
        var first = 0x67452301
        var second = 0xEFCDAB89.toInt()
        var third = 0x98BADCFE.toInt()
        var fourth = 0x10325476

        for (offset in padded.indices step 64) {
            val words = IntArray(16) { index ->
                val start = offset + index * 4
                (padded[start].toInt() and 0xFF) or
                        ((padded[start + 1].toInt() and 0xFF) shl 8) or
                        ((padded[start + 2].toInt() and 0xFF) shl 16) or
                        ((padded[start + 3].toInt() and 0xFF) shl 24)
            }
            var a = first
            var b = second
            var c = third
            var d = fourth
            for (round in 0 until 64) {
                val function: Int
                val wordIndex: Int
                when (round) {
                    in 0..15 -> {
                        function = (b and c) or (b.inv() and d)
                        wordIndex = round
                    }

                    in 16..31 -> {
                        function = (d and b) or (d.inv() and c)
                        wordIndex = (5 * round + 1) % 16
                    }

                    in 32..47 -> {
                        function = b xor c xor d
                        wordIndex = (3 * round + 5) % 16
                    }

                    else -> {
                        function = c xor (b or d.inv())
                        wordIndex = (7 * round) % 16
                    }
                }
                val previousD = d
                d = c
                c = b
                b += rotateLeft(
                    a + function + CONSTANTS[round] + words[wordIndex],
                    SHIFTS[round],
                )
                a = previousD
            }
            first += a
            second += b
            third += c
            fourth += d
        }

        return ByteArray(16).also { output ->
            intArrayOf(first, second, third, fourth).forEachIndexed { index, value ->
                val offset = index * 4
                output[offset] = value.toByte()
                output[offset + 1] = (value ushr 8).toByte()
                output[offset + 2] = (value ushr 16).toByte()
                output[offset + 3] = (value ushr 24).toByte()
            }
        }
    }

    private val SHIFTS = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val CONSTANTS = intArrayOf(
        0xD76AA478.toInt(), 0xE8C7B756.toInt(), 0x242070DB, 0xC1BDCEEE.toInt(),
        0xF57C0FAF.toInt(), 0x4787C62A, 0xA8304613.toInt(), 0xFD469501.toInt(),
        0x698098D8, 0x8B44F7AF.toInt(), 0xFFFF5BB1.toInt(), 0x895CD7BE.toInt(),
        0x6B901122, 0xFD987193.toInt(), 0xA679438E.toInt(), 0x49B40821,
        0xF61E2562.toInt(), 0xC040B340.toInt(), 0x265E5A51, 0xE9B6C7AA.toInt(),
        0xD62F105D.toInt(), 0x02441453, 0xD8A1E681.toInt(), 0xE7D3FBC8.toInt(),
        0x21E1CDE6, 0xC33707D6.toInt(), 0xF4D50D87.toInt(), 0x455A14ED,
        0xA9E3E905.toInt(), 0xFCEFA3F8.toInt(), 0x676F02D9, 0x8D2A4C8A.toInt(),
        0xFFFA3942.toInt(), 0x8771F681.toInt(), 0x6D9D6122, 0xFDE5380C.toInt(),
        0xA4BEEA44.toInt(), 0x4BDECFA9, 0xF6BB4B60.toInt(), 0xBEBFBC70.toInt(),
        0x289B7EC6, 0xEAA127FA.toInt(), 0xD4EF3085.toInt(), 0x04881D05,
        0xD9D4D039.toInt(), 0xE6DB99E5.toInt(), 0x1FA27CF8, 0xC4AC5665.toInt(),
        0xF4292244.toInt(), 0x432AFF97, 0xAB9423A7.toInt(), 0xFC93A039.toInt(),
        0x655B59C3, 0x8F0CCC92.toInt(), 0xFFEFF47D.toInt(), 0x85845DD1.toInt(),
        0x6FA87E4F, 0xFE2CE6E0.toInt(), 0xA3014314.toInt(), 0x4E0811A1,
        0xF7537E82.toInt(), 0xBD3AF235.toInt(), 0x2AD7D2BB, 0xEB86D391.toInt(),
    )
}

internal object Sha1 {
    fun digest(input: ByteArray): ByteArray {
        val padded = pad(input, littleEndianLength = false)
        var first = 0x67452301
        var second = 0xEFCDAB89.toInt()
        var third = 0x98BADCFE.toInt()
        var fourth = 0x10325476
        var fifth = 0xC3D2E1F0.toInt()

        for (offset in padded.indices step 64) {
            val words = IntArray(80)
            for (index in 0 until 16) {
                val start = offset + index * 4
                words[index] =
                    ((padded[start].toInt() and 0xFF) shl 24) or
                            ((padded[start + 1].toInt() and 0xFF) shl 16) or
                            ((padded[start + 2].toInt() and 0xFF) shl 8) or
                            (padded[start + 3].toInt() and 0xFF)
            }
            for (index in 16 until 80) {
                words[index] = rotateLeft(
                    words[index - 3] xor words[index - 8] xor
                            words[index - 14] xor words[index - 16],
                    1,
                )
            }

            var a = first
            var b = second
            var c = third
            var d = fourth
            var e = fifth
            for (round in 0 until 80) {
                val function: Int
                val constant: Int
                when (round) {
                    in 0..19 -> {
                        function = (b and c) or (b.inv() and d)
                        constant = 0x5A827999
                    }

                    in 20..39 -> {
                        function = b xor c xor d
                        constant = 0x6ED9EBA1
                    }

                    in 40..59 -> {
                        function = (b and c) or (b and d) or (c and d)
                        constant = 0x8F1BBCDC.toInt()
                    }

                    else -> {
                        function = b xor c xor d
                        constant = 0xCA62C1D6.toInt()
                    }
                }
                val temporary =
                    rotateLeft(a, 5) + function + e + constant + words[round]
                e = d
                d = c
                c = rotateLeft(b, 30)
                b = a
                a = temporary
            }
            first += a
            second += b
            third += c
            fourth += d
            fifth += e
        }

        return ByteArray(20).also { output ->
            intArrayOf(first, second, third, fourth, fifth)
                .forEachIndexed { index, value ->
                    val offset = index * 4
                    output[offset] = (value ushr 24).toByte()
                    output[offset + 1] = (value ushr 16).toByte()
                    output[offset + 2] = (value ushr 8).toByte()
                    output[offset + 3] = value.toByte()
                }
        }
    }
}

private fun pad(
    input: ByteArray,
    littleEndianLength: Boolean,
): ByteArray {
    val paddingLength = (56 - (input.size + 1) % 64 + 64) % 64
    val output = ByteArray(input.size + 1 + paddingLength + 8)
    input.copyInto(output)
    output[input.size] = 0x80.toByte()
    val bitLength = input.size.toLong() * 8
    for (index in 0 until 8) {
        val shift =
            if (littleEndianLength) index * 8 else (7 - index) * 8
        output[output.size - 8 + index] = (bitLength ushr shift).toByte()
    }
    return output
}

private fun rotateLeft(value: Int, distance: Int): Int =
    (value shl distance) or (value ushr (32 - distance))
