package com.hiczp.minecraft.protocol.auth

internal interface MinecraftRsaPrivateKey
internal interface MinecraftRsaPublicKey

internal enum class MinecraftRsaSignatureAlgorithm {
    SHA1,
    SHA256,
}

internal class MinecraftRsaKeyPair(
    publicKey: ByteArray,
    val minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
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

    fun decodePublicKey(
        encodedPublicKey: ByteArray,
        minecraftRsaSignatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey

    fun rsaDecrypt(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray

    fun rsaSha256Sign(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray

    fun rsaVerify(
        minecraftRsaPublicKey: MinecraftRsaPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean
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

    override fun decodePublicKey(
        encodedPublicKey: ByteArray,
        minecraftRsaSignatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey

    override fun rsaDecrypt(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray

    override fun rsaSha256Sign(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray

    override fun rsaVerify(
        minecraftRsaPublicKey: MinecraftRsaPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean
}
