package com.hiczp.minecraft.protocol.auth

internal interface MinecraftRsaPrivateKey

internal class MinecraftRsaKeyPair(
    publicKey: ByteArray,
    val privateKey: MinecraftRsaPrivateKey,
) {
    private val encodedPublicKey = publicKey.copyOf()

    val publicKey: ByteArray
        get() = encodedPublicKey.copyOf()
}

internal interface MinecraftRsaBackend {
    suspend fun generateRsaKeyPair(keySizeBits: Int = 1_024): MinecraftRsaKeyPair

    fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    /** PKCS#8 decoder kept internal for provider interoperability fixtures. */
    fun decodePrivateKey(
        encodedPrivateKey: ByteArray,
    ): MinecraftRsaPrivateKey

    fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray
}

internal expect object PlatformMinecraftRsaBackend : MinecraftRsaBackend {
    override suspend fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair

    override fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    override fun decodePrivateKey(
        encodedPrivateKey: ByteArray,
    ): MinecraftRsaPrivateKey

    override fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray
}
