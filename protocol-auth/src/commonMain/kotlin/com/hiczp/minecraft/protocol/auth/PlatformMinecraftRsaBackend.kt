package com.hiczp.minecraft.protocol.auth

internal interface MinecraftRsaPrivateKey
internal interface MinecraftRsaPublicKey

internal enum class MinecraftRsaSignatureAlgorithm {
    SHA1,
    SHA256,
}

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

    fun decodePublicKey(
        encodedPublicKey: ByteArray,
        signatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey

    fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray

    fun rsaSha256Sign(
        privateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray

    fun rsaVerify(
        publicKey: MinecraftRsaPublicKey,
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
        signatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey

    override fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray

    override fun rsaSha256Sign(
        privateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray

    override fun rsaVerify(
        publicKey: MinecraftRsaPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean
}
