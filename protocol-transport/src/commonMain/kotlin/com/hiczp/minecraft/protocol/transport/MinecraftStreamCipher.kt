package com.hiczp.minecraft.protocol.transport

interface MinecraftStreamCipher {
    fun process(input: ByteArray): ByteArray
}

/**
 * Stateful AES-128/CFB8 transform used by Minecraft's online-mode stream.
 *
 * CFB decryption uses the AES encryption primitive as required by the mode.
 */
internal class AesCfb8Cipher private constructor(
    key: ByteArray,
    initializationVector: ByteArray,
    private val decrypting: Boolean,
) : MinecraftStreamCipher {
    private val aes = Aes128(key)
    private val feedback = initializationVector.copyOf()

    init {
        require(initializationVector.size == BLOCK_SIZE) {
            "AES/CFB8 initialization vector must contain 16 bytes"
        }
    }

    override fun process(input: ByteArray): ByteArray {
        val output = ByteArray(input.size)
        input.forEachIndexed { index, inputByte ->
            val keyByte = aes.encrypt(feedback)[0]
            val outputByte =
                (inputByte.toInt() xor keyByte.toInt()).toByte()
            output[index] = outputByte
            feedback.copyInto(
                destination = feedback,
                destinationOffset = 0,
                startIndex = 1,
                endIndex = feedback.size,
            )
            feedback[feedback.lastIndex] =
                if (decrypting) inputByte else outputByte
        }
        return output
    }

    companion object {
        private const val BLOCK_SIZE = 16

        fun encryptor(
            key: ByteArray,
            initializationVector: ByteArray = key,
        ): AesCfb8Cipher =
            AesCfb8Cipher(key, initializationVector, decrypting = false)

        fun decryptor(
            key: ByteArray,
            initializationVector: ByteArray = key,
        ): AesCfb8Cipher =
            AesCfb8Cipher(key, initializationVector, decrypting = true)
    }
}

private class Aes128(key: ByteArray) {
    private val roundKeys: ByteArray

    init {
        require(key.size == BLOCK_SIZE) { "AES-128 key must contain 16 bytes" }
        roundKeys = expandKey(key)
    }

    fun encrypt(input: ByteArray): ByteArray {
        require(input.size == BLOCK_SIZE)
        var state = input.copyOf()
        addRoundKey(state, 0)
        for (round in 1 until ROUND_COUNT) {
            subBytes(state)
            state = shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state)
        state = shiftRows(state)
        addRoundKey(state, ROUND_COUNT)
        return state
    }

    private fun addRoundKey(state: ByteArray, round: Int) {
        val offset = round * BLOCK_SIZE
        for (index in state.indices) {
            state[index] =
                (state[index].toInt() xor roundKeys[offset + index].toInt()).toByte()
        }
    }

    private fun subBytes(state: ByteArray) {
        state.indices.forEach { index ->
            state[index] = S_BOX[state[index].toInt() and 0xFF].toByte()
        }
    }

    private fun shiftRows(state: ByteArray): ByteArray {
        val shifted = ByteArray(BLOCK_SIZE)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                shifted[column * 4 + row] =
                    state[((column + row) % 4) * 4 + row]
            }
        }
        return shifted
    }

    private fun mixColumns(state: ByteArray) {
        for (column in 0 until 4) {
            val offset = column * 4
            val first = state[offset].toInt() and 0xFF
            val second = state[offset + 1].toInt() and 0xFF
            val third = state[offset + 2].toInt() and 0xFF
            val fourth = state[offset + 3].toInt() and 0xFF
            state[offset] =
                (multiplyByTwo(first) xor multiplyByThree(second) xor third xor fourth)
                    .toByte()
            state[offset + 1] =
                (first xor multiplyByTwo(second) xor multiplyByThree(third) xor fourth)
                    .toByte()
            state[offset + 2] =
                (first xor second xor multiplyByTwo(third) xor multiplyByThree(fourth))
                    .toByte()
            state[offset + 3] =
                (multiplyByThree(first) xor second xor third xor multiplyByTwo(fourth))
                    .toByte()
        }
    }

    private fun expandKey(key: ByteArray): ByteArray {
        val expanded = ByteArray(BLOCK_SIZE * (ROUND_COUNT + 1))
        key.copyInto(expanded)
        val temporary = ByteArray(4)
        var generated = BLOCK_SIZE
        var roundConstant = 1
        while (generated < expanded.size) {
            expanded.copyInto(
                destination = temporary,
                startIndex = generated - 4,
                endIndex = generated,
            )
            if (generated % BLOCK_SIZE == 0) {
                val first = temporary[0]
                temporary[0] = S_BOX[temporary[1].toInt() and 0xFF].toByte()
                temporary[1] = S_BOX[temporary[2].toInt() and 0xFF].toByte()
                temporary[2] = S_BOX[temporary[3].toInt() and 0xFF].toByte()
                temporary[3] = S_BOX[first.toInt() and 0xFF].toByte()
                temporary[0] =
                    (temporary[0].toInt() xor roundConstant).toByte()
                roundConstant = multiplyByTwo(roundConstant)
            }
            for (index in temporary.indices) {
                expanded[generated] =
                    (expanded[generated - BLOCK_SIZE].toInt() xor
                            temporary[index].toInt()).toByte()
                generated++
            }
        }
        return expanded
    }

    companion object {
        private const val BLOCK_SIZE = 16
        private const val ROUND_COUNT = 10

        private fun multiplyByTwo(value: Int): Int =
            ((value shl 1) xor if (value and 0x80 != 0) 0x11B else 0) and 0xFF

        private fun multiplyByThree(value: Int): Int =
            multiplyByTwo(value) xor value

        private val S_BOX = intArrayOf(
            0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5,
            0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
            0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0,
            0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
            0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC,
            0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
            0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A,
            0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
            0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0,
            0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
            0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B,
            0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
            0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85,
            0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
            0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5,
            0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
            0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17,
            0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
            0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88,
            0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
            0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C,
            0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
            0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9,
            0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
            0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6,
            0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
            0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E,
            0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
            0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94,
            0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
            0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68,
            0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
        )
    }
}
