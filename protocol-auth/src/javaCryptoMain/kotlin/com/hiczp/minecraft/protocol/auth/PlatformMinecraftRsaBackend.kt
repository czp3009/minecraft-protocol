package com.hiczp.minecraft.protocol.auth

import java.security.*
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

    actual override fun decodePublicKey(
        encodedPublicKey: ByteArray,
        signatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey = JavaRsaPublicKey(
        key = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(encodedPublicKey.copyOf()),
        ),
        signatureAlgorithm = signatureAlgorithm,
    )

    actual override fun rsaSha256Sign(
        privateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray {
        require(privateKey is JavaRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return Signature.getInstance(SHA256_WITH_RSA).run {
            initSign(privateKey.key, secureRandom)
            update(payload.copyOf())
            sign()
        }
    }

    actual override fun rsaVerify(
        publicKey: MinecraftRsaPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(publicKey is JavaRsaPublicKey) {
            "The RSA public key was not created by this platform backend"
        }
        return try {
            Signature.getInstance(publicKey.signatureAlgorithm.jcaName).run {
                initVerify(publicKey.key)
                update(payload.copyOf())
                verify(signature.copyOf())
            }
        } catch (_: SignatureException) {
            false
        }
    }

    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val SHA256_WITH_RSA = "SHA256withRSA"
}

private class JavaRsaPrivateKey(
    val key: PrivateKey,
) : MinecraftRsaPrivateKey

private class JavaRsaPublicKey(
    val key: PublicKey,
    val signatureAlgorithm: MinecraftRsaSignatureAlgorithm,
) : MinecraftRsaPublicKey

private val MinecraftRsaSignatureAlgorithm.jcaName: String
    get() = when (this) {
        MinecraftRsaSignatureAlgorithm.SHA1 -> "SHA1withRSA"
        MinecraftRsaSignatureAlgorithm.SHA256 -> "SHA256withRSA"
    }
