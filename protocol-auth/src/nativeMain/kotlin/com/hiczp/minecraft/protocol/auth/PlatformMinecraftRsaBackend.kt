@file:OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package com.hiczp.minecraft.protocol.auth

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256

internal actual object PlatformMinecraftRsaBackend : MinecraftRsaBackend {
    private val rsa: RSA.PKCS1
        get() = CryptographyProvider.Default.get(RSA.PKCS1)

    actual override suspend fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair {
        require(keySizeBits >= 1_024)
        val keyPair = rsa.keyPairGenerator(
            keySize = keySizeBits.bits,
            digest = SHA256,
        ).generateKey()
        return MinecraftRsaKeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(
                RSA.PublicKey.Format.DER,
            ),
            privateKey = NativeRsaPrivateKey(keyPair.privateKey),
        )
    }

    actual override fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val publicKey = rsa.publicKeyDecoder(SHA256).decodeFromByteArrayBlocking(
            RSA.PublicKey.Format.DER,
            encodedPublicKey.copyOf(),
        )
        return publicKey.encryptor().encryptBlocking(plaintext.copyOf())
    }

    actual override fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray {
        require(privateKey is NativeRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return privateKey.key.decryptor().decryptBlocking(ciphertext.copyOf())
    }

    actual override fun decodePrivateKey(
        encodedPrivateKey: ByteArray,
    ): MinecraftRsaPrivateKey = NativeRsaPrivateKey(
        rsa.privateKeyDecoder(SHA256).decodeFromByteArrayBlocking(
            RSA.PrivateKey.Format.DER,
            encodedPrivateKey.copyOf(),
        ),
    )
}

private class NativeRsaPrivateKey(
    val key: RSA.PKCS1.PrivateKey,
) : MinecraftRsaPrivateKey
