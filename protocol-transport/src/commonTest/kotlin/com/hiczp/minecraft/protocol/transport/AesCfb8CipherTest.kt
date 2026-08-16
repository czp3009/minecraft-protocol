package com.hiczp.minecraft.protocol.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AesCfb8CipherTest {
    @Test
    fun matchesTheNistAes128Cfb8Vector() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val initializationVector = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = hex(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
        )
        val expectedCiphertext = hex(
            "3b79424c9c0dd436bace9e0ed4586a4f32b9ded50ae3ba69d472e88267fb505270cbad1e257691f7c47c5038297edda32ff26d0ed19174096161ecc14086dd62",
        )

        val ciphertext = AesCfb8Cipher.encryptor(key, initializationVector).process(plaintext)

        assertContentEquals(expectedCiphertext, ciphertext)
        assertContentEquals(
            plaintext,
            AesCfb8Cipher.decryptor(key, initializationVector).process(ciphertext),
        )
    }

    @Test
    fun preservesStateAcrossArbitraryChunks() {
        val secret = ByteArray(16) { it.toByte() }
        val plaintext = ByteArray(257) { (it * 31).toByte() }
        val oneShot = AesCfb8Cipher.encryptor(secret).process(plaintext)
        val chunkedCipher = AesCfb8Cipher.encryptor(secret)
        val chunked = buildList {
            addAll(chunkedCipher.process(plaintext.copyOfRange(0, 1)).asList())
            addAll(chunkedCipher.process(plaintext.copyOfRange(1, 113)).asList())
            addAll(chunkedCipher.process(plaintext.copyOfRange(113, plaintext.size)).asList())
        }.toByteArray()

        assertContentEquals(oneShot, chunked)
    }

    @Test
    fun decryptionPreservesStateAcrossEmptyAndArbitraryChunks() {
        val secret = ByteArray(16) { (it * 13).toByte() }
        val plaintext = ByteArray(513) { (it * 29).toByte() }
        val ciphertext = AesCfb8Cipher.encryptor(secret).process(plaintext)
        val decryptor = AesCfb8Cipher.decryptor(secret)

        assertContentEquals(byteArrayOf(), decryptor.process(byteArrayOf()))
        val decoded = buildList {
            addAll(decryptor.process(ciphertext.copyOfRange(0, 1)).asList())
            addAll(decryptor.process(byteArrayOf()).asList())
            addAll(decryptor.process(ciphertext.copyOfRange(1, 256)).asList())
            addAll(
                decryptor.process(
                    ciphertext.copyOfRange(256, ciphertext.size),
                ).asList(),
            )
        }.toByteArray()

        assertContentEquals(plaintext, decoded)
    }

    @Test
    fun rejectsNonAes128KeysAndInitializationVectors() {
        assertFailsWith<IllegalArgumentException> {
            AesCfb8Cipher.encryptor(ByteArray(15))
        }
        assertFailsWith<IllegalArgumentException> {
            AesCfb8Cipher.decryptor(
                key = ByteArray(16),
                initializationVector = ByteArray(15),
            )
        }
    }
}

private fun hex(value: String): ByteArray =
    ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
