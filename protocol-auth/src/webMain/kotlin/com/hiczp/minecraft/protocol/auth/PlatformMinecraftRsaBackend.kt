@file:OptIn(
    ExperimentalUnsignedTypes::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

package com.hiczp.minecraft.protocol.auth

import kotlinx.coroutines.yield
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toUByteArray
import org.khronos.webgl.toUint8Array

internal actual object PlatformMinecraftRsaBackend : MinecraftRsaBackend {
    actual override suspend fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair {
        require(keySizeBits >= 1_024)
        requireStrongWebRandom()
        val keyPair = generateForgeRsaKeyPair(keySizeBits)
        val publicKey = forgeAsn1.toDer(
            forgePki.publicKeyToSubjectPublicKeyInfo(
                keyPair.publicKey,
            ),
        ).getBytes().decodeForgeBytes()
        return MinecraftRsaKeyPair(
            publicKey = publicKey,
            privateKey = ForgeRsaPrivateKey(keyPair.privateKey),
        )
    }

    actual override fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        requireStrongWebRandom()
        val publicKey = forgePki.publicKeyFromAsn1(
            forgeAsn1.fromDer(encodedPublicKey.encodeForgeBytes()),
        )
        return publicKey.encrypt(
            plaintext.encodeForgeBytes(),
            RSAES_PKCS1_V1_5,
        ).decodeForgeBytes()
    }

    actual override fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray {
        requireStrongWebRandom()
        require(privateKey is ForgeRsaPrivateKey) {
            "The RSA private key was not created by this platform backend"
        }
        return privateKey.key.decrypt(
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
}

private suspend fun generateForgeRsaKeyPair(
    keySizeBits: Int,
): ForgeRsaKeyPair {
    forgeOptions.usePureJavaScript = true
    val state = forgePki.rsa.createKeyPairGenerationState(
        keySizeBits,
        RSA_PUBLIC_EXPONENT,
    )
    while (!forgePki.rsa.stepKeyPairGenerationState(state, KEY_GENERATION_SLICE_MILLIS)) {
        // node-forge's callback API implicitly starts prime.worker.js in browsers. Driving its documented incremental
        // state API keeps the published artifact self-contained and yields between bounded main-thread slices.
        yield()
    }
    return state.keys
}

private fun requireStrongWebRandom() {
    // cryptography-random fails instead of falling back when globalThis.crypto.getRandomValues is unavailable.
    secureRandomBytes(1).fill(0)
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
    val key: ForgePrivateKey,
) : MinecraftRsaPrivateKey

internal external interface ForgeModule : JsAny {
    val options: ForgeOptions
    val pki: ForgePki
    val asn1: ForgeAsn1
    val util: ForgeUtil
}

internal external interface ForgeOptions : JsAny {
    var usePureJavaScript: Boolean
}

internal external interface ForgePki : JsAny {
    val rsa: ForgeRsa

    fun publicKeyToSubjectPublicKeyInfo(publicKey: ForgePublicKey): ForgeAsn1Object

    fun publicKeyFromAsn1(value: ForgeAsn1Object): ForgePublicKey

    fun privateKeyFromAsn1(value: ForgeAsn1Object): ForgePrivateKey
}

internal external interface ForgeRsa : JsAny {
    fun createKeyPairGenerationState(
        keySizeBits: Int,
        publicExponent: Int,
    ): ForgeRsaKeyPairGenerationState

    fun stepKeyPairGenerationState(
        state: ForgeRsaKeyPairGenerationState,
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
}

internal external interface ForgePrivateKey : JsAny {
    fun decrypt(data: String, scheme: String): String
}

internal external interface ForgeAsn1 : JsAny {
    fun toDer(value: ForgeAsn1Object): ForgeByteBuffer

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
    fun encode(bytes: Uint8Array): String

    fun decode(value: String): Uint8Array
}

internal expect val forgeOptions: ForgeOptions
internal expect val forgePki: ForgePki
internal expect val forgeAsn1: ForgeAsn1
internal expect val forgeUtil: ForgeUtil

private const val RSAES_PKCS1_V1_5 = "RSAES-PKCS1-V1_5"
private const val RSA_PUBLIC_EXPONENT = 65_537
private const val KEY_GENERATION_SLICE_MILLIS = 10
