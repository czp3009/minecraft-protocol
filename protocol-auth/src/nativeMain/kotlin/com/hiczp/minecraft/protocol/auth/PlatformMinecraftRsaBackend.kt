@file:OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package com.hiczp.minecraft.protocol.auth

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA1
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
            minecraftRsaPrivateKey = NativeRsaPrivateKey(keyPair.privateKey),
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
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray {
        require(minecraftRsaPrivateKey is NativeRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return minecraftRsaPrivateKey.privateKey.decryptor().decryptBlocking(ciphertext.copyOf())
    }

    actual override fun decodePrivateKey(
        encodedPrivateKey: ByteArray,
    ): MinecraftRsaPrivateKey = NativeRsaPrivateKey(
        rsa.privateKeyDecoder(SHA256).decodeFromByteArrayBlocking(
            RSA.PrivateKey.Format.DER,
            encodedPrivateKey.copyOf(),
        ),
    )

    actual override fun decodePublicKey(
        encodedPublicKey: ByteArray,
        minecraftRsaSignatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey = NativeRsaPublicKey(
        rsa.publicKeyDecoder(minecraftRsaSignatureAlgorithm.digest).decodeFromByteArrayBlocking(
            RSA.PublicKey.Format.DER,
            encodedPublicKey.copyOf(),
        ),
    )

    actual override fun rsaSha256Sign(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray {
        require(minecraftRsaPrivateKey is NativeRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return minecraftRsaPrivateKey.privateKey.signatureGenerator().generateSignatureBlocking(payload.copyOf())
    }

    actual override fun rsaVerify(
        minecraftRsaPublicKey: MinecraftRsaPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(minecraftRsaPublicKey is NativeRsaPublicKey) {
            "The RSA public key was not created by this platform backend"
        }
        return minecraftRsaPublicKey.publicKey.signatureVerifier().tryVerifySignatureBlocking(
            payload.copyOf(),
            signature.copyOf(),
        )
    }
}

private class NativeRsaPrivateKey(
    val privateKey: RSA.PKCS1.PrivateKey,
) : MinecraftRsaPrivateKey

private class NativeRsaPublicKey(
    val publicKey: RSA.PKCS1.PublicKey,
) : MinecraftRsaPublicKey

@OptIn(dev.whyoleg.cryptography.DelicateCryptographyApi::class)
private val MinecraftRsaSignatureAlgorithm.digest
    get() = when (this) {
        MinecraftRsaSignatureAlgorithm.SHA1 -> SHA1
        MinecraftRsaSignatureAlgorithm.SHA256 -> SHA256
    }
