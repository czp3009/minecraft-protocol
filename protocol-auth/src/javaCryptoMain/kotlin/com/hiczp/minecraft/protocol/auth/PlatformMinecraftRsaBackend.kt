package com.hiczp.minecraft.protocol.auth

import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

internal actual object PlatformMinecraftRsaBackend : MinecraftRsaBackend {
    private val secureRandom = SecureRandom()

    actual override suspend fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair {
        require(keySizeBits >= 1_024)
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(keySizeBits, secureRandom)
        val keyPair = keyPairGenerator.generateKeyPair()
        return MinecraftRsaKeyPair(
            publicKey = keyPair.public.encoded,
            minecraftRsaPrivateKey = JavaRsaPrivateKey(keyPair.private),
        )
    }

    actual override fun rsaEncrypt(
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

    actual override fun rsaDecrypt(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray {
        require(minecraftRsaPrivateKey is JavaRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, minecraftRsaPrivateKey.privateKey)
            doFinal(ciphertext)
        }
    }

    actual override fun decodePrivateKey(
        encodedPrivateKey: ByteArray,
    ): MinecraftRsaPrivateKey = JavaRsaPrivateKey(
        KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(encodedPrivateKey),
        ),
    )

    actual override fun decodePublicKey(
        encodedPublicKey: ByteArray,
        minecraftRsaSignatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey = JavaRsaPublicKey(
        publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(encodedPublicKey),
        ),
        minecraftRsaSignatureAlgorithm = minecraftRsaSignatureAlgorithm,
    )

    actual override fun rsaSha256Sign(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray {
        require(minecraftRsaPrivateKey is JavaRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return Signature.getInstance(SHA256_WITH_RSA).run {
            initSign(minecraftRsaPrivateKey.privateKey, secureRandom)
            update(payload)
            sign()
        }
    }

    actual override fun rsaVerify(
        minecraftRsaPublicKey: MinecraftRsaPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(minecraftRsaPublicKey is JavaRsaPublicKey) {
            "The RSA public key was not created by this platform backend"
        }
        return try {
            Signature.getInstance(minecraftRsaPublicKey.minecraftRsaSignatureAlgorithm.jcaName).run {
                initVerify(minecraftRsaPublicKey.publicKey)
                update(payload)
                verify(signature)
            }
        } catch (_: SignatureException) {
            false
        }
    }

    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val SHA256_WITH_RSA = "SHA256withRSA"
}

private class JavaRsaPrivateKey(
    val privateKey: PrivateKey,
) : MinecraftRsaPrivateKey

private class JavaRsaPublicKey(
    val publicKey: PublicKey,
    val minecraftRsaSignatureAlgorithm: MinecraftRsaSignatureAlgorithm,
) : MinecraftRsaPublicKey

private val MinecraftRsaSignatureAlgorithm.jcaName: String
    get() = when (this) {
        MinecraftRsaSignatureAlgorithm.SHA1 -> "SHA1withRSA"
        MinecraftRsaSignatureAlgorithm.SHA256 -> "SHA256withRSA"
    }
