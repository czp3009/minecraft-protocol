package com.hiczp.minecraft.protocol.auth

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object JvmMinecraftCryptography : MinecraftCryptography {
    private val secureRandom = SecureRandom()

    override fun secureRandomBytes(size: Int): ByteArray {
        require(size >= 0)
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    override fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair {
        require(keySizeBits >= 1_024)
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(keySizeBits, secureRandom)
        val pair = generator.generateKeyPair()
        return MinecraftRsaKeyPair(
            publicKey = pair.public.encoded,
            privateKey = JvmRsaPrivateKey(pair.private),
        )
    }

    override fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(encodedPublicKey))
        return Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, publicKey, secureRandom)
            doFinal(plaintext)
        }
    }

    override fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray {
        require(privateKey is JvmRsaPrivateKey) {
            "JvmMinecraftCryptography requires its own private-key handle"
        }
        return Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, privateKey.key)
            doFinal(ciphertext)
        }
    }

    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
}

private class JvmRsaPrivateKey(
    val key: PrivateKey,
) : MinecraftRsaPrivateKey
