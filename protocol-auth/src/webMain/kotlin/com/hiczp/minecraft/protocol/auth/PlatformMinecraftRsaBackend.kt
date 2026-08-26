@file:OptIn(
    ExperimentalUnsignedTypes::class,
    ExperimentalWasmJsInterop::class,
)

package com.hiczp.minecraft.protocol.auth

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.yield
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toUByteArray
import org.khronos.webgl.toUint8Array

internal actual object PlatformMinecraftRsaBackend : MinecraftRsaBackend {
    actual override suspend fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair {
        require(keySizeBits >= 1_024)
        requireStrongWebRandom()
        val forgeRsaKeyPair = generateForgeRsaKeyPair(keySizeBits)
        val publicKey = forgeAsn1.toDer(
            forgePki.publicKeyToSubjectPublicKeyInfo(
                forgeRsaKeyPair.publicKey,
            ),
        ).getBytes().decodeForgeBytes()
        return MinecraftRsaKeyPair(
            publicKey = publicKey,
            minecraftRsaPrivateKey = ForgeRsaPrivateKey(forgeRsaKeyPair.privateKey),
        )
    }

    actual override fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        requireStrongWebRandom()
        val forgePublicKey = forgePki.publicKeyFromAsn1(
            forgeAsn1.fromDer(encodedPublicKey.encodeForgeBytes()),
        )
        return forgePublicKey.encrypt(
            plaintext.encodeForgeBytes(),
            RSAES_PKCS1_V1_5,
        ).decodeForgeBytes()
    }

    actual override fun rsaDecrypt(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray {
        requireStrongWebRandom()
        require(minecraftRsaPrivateKey is ForgeRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return minecraftRsaPrivateKey.forgePrivateKey.decrypt(
            ciphertext.encodeForgeBytes(),
            RSAES_PKCS1_V1_5,
        ).decodeForgeBytes()
    }

    actual override fun decodePrivateKey(
        encodedPrivateKey: ByteArray,
    ): MinecraftRsaPrivateKey = ForgeRsaPrivateKey(
        forgePki.privateKeyFromAsn1(
            forgeAsn1.fromDer(encodedPrivateKey.encodeForgeBytes()),
        ),
    )

    actual override fun decodePublicKey(
        encodedPublicKey: ByteArray,
        minecraftRsaSignatureAlgorithm: MinecraftRsaSignatureAlgorithm,
    ): MinecraftRsaPublicKey = ForgeRsaPublicKey(
        forgePublicKey = forgePki.publicKeyFromAsn1(
            forgeAsn1.fromDer(encodedPublicKey.encodeForgeBytes()),
        ),
        minecraftRsaSignatureAlgorithm = minecraftRsaSignatureAlgorithm,
    )

    actual override fun rsaSha256Sign(
        minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
        payload: ByteArray,
    ): ByteArray {
        requireStrongWebRandom()
        require(minecraftRsaPrivateKey is ForgeRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        val forgeMessageDigest = forgeMd.sha256.create().apply {
            update(payload.encodeForgeBytes())
        }
        return minecraftRsaPrivateKey.forgePrivateKey.sign(forgeMessageDigest).decodeForgeBytes()
    }

    actual override fun rsaVerify(
        minecraftRsaPublicKey: MinecraftRsaPublicKey,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(minecraftRsaPublicKey is ForgeRsaPublicKey) {
            "The RSA public key was not created by this platform backend"
        }
        val digest = minecraftRsaPublicKey.minecraftRsaSignatureAlgorithm.createForgeDigest().apply {
            update(payload.encodeForgeBytes())
        }.digest().getBytes()
        return try {
            minecraftRsaPublicKey.forgePublicKey.verify(digest, signature.encodeForgeBytes())
        } catch (_: Throwable) {
            false
        }
    }
}

private suspend fun generateForgeRsaKeyPair(
    keySizeBits: Int,
): ForgeRsaKeyPair {
    forgeOptions.usePureJavaScript = true
    val forgeRsaKeyPairGenerationState = forgePki.rsa.createKeyPairGenerationState(
        keySizeBits,
        RSA_PUBLIC_EXPONENT,
    )
    while (!forgePki.rsa.stepKeyPairGenerationState(forgeRsaKeyPairGenerationState, KEY_GENERATION_SLICE_MILLIS)) {
        // node-forge's callback API implicitly starts prime.worker.js in browsers. Driving its documented incremental
        // state API keeps the published artifact self-contained and yields between bounded main-thread slices.
        yield()
    }
    return forgeRsaKeyPairGenerationState.keys
}

private fun requireStrongWebRandom() {
    // cryptography-random fails instead of falling back when globalThis.crypto.getRandomValues is unavailable.
    CryptographyRandom.nextBytes(1).fill(0)
    forgeOptions.usePureJavaScript = true
}

private fun ByteArray.encodeForgeBytes(): String =
    forgeUtil.binary.raw.encode(
        asUByteArray().toUint8Array(),
    )

private fun String.decodeForgeBytes(): ByteArray =
    forgeUtil.binary.raw.decode(this)
        .toUByteArray()
        .asByteArray()

private class ForgeRsaPrivateKey(
    val forgePrivateKey: ForgePrivateKey,
) : MinecraftRsaPrivateKey

private class ForgeRsaPublicKey(
    val forgePublicKey: ForgePublicKey,
    val minecraftRsaSignatureAlgorithm: MinecraftRsaSignatureAlgorithm,
) : MinecraftRsaPublicKey

private fun MinecraftRsaSignatureAlgorithm.createForgeDigest(): ForgeMessageDigest = when (this) {
    MinecraftRsaSignatureAlgorithm.SHA1 -> forgeMd.sha1.create()
    MinecraftRsaSignatureAlgorithm.SHA256 -> forgeMd.sha256.create()
}

internal external interface ForgeModule : JsAny {
    val options: ForgeOptions
    val pki: ForgePki
    val asn1: ForgeAsn1
    val util: ForgeUtil
    val md: ForgeMessageDigests
}

internal external interface ForgeOptions : JsAny {
    var usePureJavaScript: Boolean
}

internal external interface ForgePki : JsAny {
    val rsa: ForgeRsa

    fun publicKeyToSubjectPublicKeyInfo(forgePublicKey: ForgePublicKey): ForgeAsn1Object

    fun publicKeyFromAsn1(forgeAsn1Object: ForgeAsn1Object): ForgePublicKey

    fun privateKeyFromAsn1(forgeAsn1Object: ForgeAsn1Object): ForgePrivateKey
}

internal external interface ForgeRsa : JsAny {
    fun createKeyPairGenerationState(
        keySizeBits: Int,
        publicExponent: Int,
    ): ForgeRsaKeyPairGenerationState

    fun stepKeyPairGenerationState(
        forgeRsaKeyPairGenerationState: ForgeRsaKeyPairGenerationState,
        milliseconds: Int,
    ): Boolean
}

internal external interface ForgeRsaKeyPairGenerationState : JsAny {
    val keys: ForgeRsaKeyPair
}

internal external interface ForgeRsaKeyPair : JsAny {
    val publicKey: ForgePublicKey
    val privateKey: ForgePrivateKey
}

internal external interface ForgePublicKey : JsAny {
    fun encrypt(data: String, scheme: String): String

    fun verify(digest: String, signature: String): Boolean
}

internal external interface ForgePrivateKey : JsAny {
    fun decrypt(data: String, scheme: String): String

    fun sign(forgeMessageDigest: ForgeMessageDigest): String
}

internal external interface ForgeMessageDigests : JsAny {
    val sha1: ForgeMessageDigestFactory
    val sha256: ForgeMessageDigestFactory
}

internal external interface ForgeMessageDigestFactory : JsAny {
    fun create(): ForgeMessageDigest
}

internal external interface ForgeMessageDigest : JsAny {
    fun update(bytes: String): ForgeMessageDigest

    fun digest(): ForgeByteBuffer
}

internal external interface ForgeAsn1 : JsAny {
    fun toDer(forgeAsn1Object: ForgeAsn1Object): ForgeByteBuffer

    fun fromDer(value: String): ForgeAsn1Object
}

internal external interface ForgeAsn1Object : JsAny

internal external interface ForgeByteBuffer : JsAny {
    fun getBytes(): String
}

internal external interface ForgeUtil : JsAny {
    val binary: ForgeBinary
}

internal external interface ForgeBinary : JsAny {
    val raw: ForgeBinaryRaw
}

internal external interface ForgeBinaryRaw : JsAny {
    fun encode(uint8Array: Uint8Array): String

    fun decode(value: String): Uint8Array
}

internal expect val forgeOptions: ForgeOptions
internal expect val forgePki: ForgePki
internal expect val forgeAsn1: ForgeAsn1
internal expect val forgeUtil: ForgeUtil
internal expect val forgeMd: ForgeMessageDigests

private const val RSAES_PKCS1_V1_5 = "RSAES-PKCS1-V1_5"
private const val RSA_PUBLIC_EXPONENT = 65_537
private const val KEY_GENERATION_SLICE_MILLIS = 10
