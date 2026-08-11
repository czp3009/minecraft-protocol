package com.hiczp.minecraft.protocol.auth

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

internal actual object PlatformMinecraftRsaBackend : MinecraftRsaBackend {
    private val secureRandom = SecureRandom()

    actual override suspend fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair {
        require(keySizeBits >= 1_024)
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(keySizeBits, secureRandom)
        val pair = generator.generateKeyPair()
        return MinecraftRsaKeyPair(
            publicKey = pair.public.encoded,
            privateKey = JavaRsaPrivateKey(pair.private),
        )
    }

    actual override fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(encodedPublicKey.copyOf()))
        return Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, publicKey, secureRandom)
            doFinal(plaintext.copyOf())
        }
    }

    actual override fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray {
        require(privateKey is JavaRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, privateKey.key)
            doFinal(ciphertext.copyOf())
        }
    }

    actual override fun decodePrivateKey(
        encodedPrivateKey: ByteArray,
    ): MinecraftRsaPrivateKey = JavaRsaPrivateKey(
        KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(encodedPrivateKey.copyOf()),
        ),
    )

    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
}

private class JavaRsaPrivateKey(
    val key: PrivateKey,
) : MinecraftRsaPrivateKey
