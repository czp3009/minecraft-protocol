package com.hiczp.minecraft.protocol.transport

interface MinecraftStreamCipher {
    fun process(input: ByteArray): ByteArray
}

/** Stateful AES-128/CFB8 transform used by Minecraft's online-mode stream. */
internal object AesCfb8Cipher {
    fun encryptor(
        key: ByteArray,
        initializationVector: ByteArray = key,
    ): MinecraftStreamCipher =
        create(
            key,
            initializationVector,
            decrypting = false,
        )

    fun decryptor(
        key: ByteArray,
        initializationVector: ByteArray = key,
    ): MinecraftStreamCipher =
        create(
            key,
            initializationVector,
            decrypting = true,
        )

    private fun create(
        key: ByteArray,
        initializationVector: ByteArray,
        decrypting: Boolean,
    ): MinecraftStreamCipher {
        require(key.size == AES_BLOCK_BYTES) {
            "AES-128 key must contain 16 bytes"
        }
        require(initializationVector.size == AES_BLOCK_BYTES) {
            "AES/CFB8 initialization vector must contain 16 bytes"
        }
        return platformAesCfb8Cipher(
            key.copyOf(),
            initializationVector.copyOf(),
            decrypting,
        )
    }

    private const val AES_BLOCK_BYTES = 16
}

internal expect fun platformAesCfb8Cipher(
    key: ByteArray,
    initializationVector: ByteArray,
    decrypting: Boolean,
): MinecraftStreamCipher
