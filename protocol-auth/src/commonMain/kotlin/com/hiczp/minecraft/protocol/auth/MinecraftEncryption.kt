package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.EncryptionRequestPacket
import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import dev.whyoleg.cryptography.bigint.decodeToBigInt
import okio.ByteString.Companion.toByteString

interface MinecraftRsaPrivateKey

class MinecraftRsaKeyPair(
    publicKey: ByteArray,
    val privateKey: MinecraftRsaPrivateKey,
) {
    private val encodedPublicKey = publicKey.copyOf()

    val publicKey: ByteArray
        get() = encodedPublicKey.copyOf()
}

interface MinecraftCryptography {
    fun secureRandomBytes(size: Int): ByteArray

    fun generateRsaKeyPair(keySizeBits: Int = 1_024): MinecraftRsaKeyPair

    fun rsaEncrypt(
        encodedPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    fun rsaDecrypt(
        privateKey: MinecraftRsaPrivateKey,
        ciphertext: ByteArray,
    ): ByteArray
}

class MinecraftEncryptionChallenge internal constructor(
    val request: EncryptionRequestPacket,
    internal val keyPair: MinecraftRsaKeyPair,
    verifyToken: ByteArray,
) {
    private val expectedVerifyToken = verifyToken.copyOf()

    internal fun verifyToken(): ByteArray = expectedVerifyToken.copyOf()
}

class MinecraftClientEncryption internal constructor(
    val response: EncryptionResponsePacket,
    sharedSecret: ByteArray,
) {
    private val secret = sharedSecret.copyOf()

    val sharedSecret: ByteArray
        get() = secret.copyOf()
}

object MinecraftEncryption {
    fun createServerChallenge(
        cryptography: MinecraftCryptography,
        keyPair: MinecraftRsaKeyPair = cryptography.generateRsaKeyPair(),
        shouldAuthenticate: Boolean = true,
        serverId: String = "",
        verifyTokenSize: Int = 4,
    ): MinecraftEncryptionChallenge {
        require(serverId.length <= 20)
        require(verifyTokenSize > 0)
        val verifyToken = cryptography.secureRandomBytes(verifyTokenSize)
        return MinecraftEncryptionChallenge(
            request = EncryptionRequestPacket(
                serverId = serverId,
                publicKey = ByteString(keyPair.publicKey),
                verifyToken = ByteString(verifyToken),
                shouldAuthenticate = shouldAuthenticate,
            ),
            keyPair = keyPair,
            verifyToken = verifyToken,
        )
    }

    fun answerServerChallenge(
        request: EncryptionRequestPacket,
        cryptography: MinecraftCryptography,
    ): MinecraftClientEncryption {
        val sharedSecret = cryptography.secureRandomBytes(16)
        val publicKey = request.publicKey.toByteArray()
        return MinecraftClientEncryption(
            response = EncryptionResponsePacket(
                sharedSecret = ByteString(
                    cryptography.rsaEncrypt(publicKey, sharedSecret),
                ),
                verifyToken = ByteString(
                    cryptography.rsaEncrypt(
                        publicKey,
                        request.verifyToken.toByteArray(),
                    ),
                ),
            ),
            sharedSecret = sharedSecret,
        )
    }

    fun acceptClientResponse(
        challenge: MinecraftEncryptionChallenge,
        response: EncryptionResponsePacket,
        cryptography: MinecraftCryptography,
    ): ByteArray {
        val verifyToken = cryptography.rsaDecrypt(
            challenge.keyPair.privateKey,
            response.verifyToken.toByteArray(),
        )
        if (
            !verifyToken.toByteString().equals(
                challenge.verifyToken().toByteString(),
                constantTime = true,
            )
        ) {
            throw MinecraftAuthenticationException(
                "Encryption Response verify token does not match",
            )
        }
        val sharedSecret = cryptography.rsaDecrypt(
            challenge.keyPair.privateKey,
            response.sharedSecret.toByteArray(),
        )
        if (sharedSecret.size != 16) {
            throw MinecraftAuthenticationException(
                "Minecraft shared secret must contain 16 bytes",
            )
        }
        return sharedSecret
    }
}

fun minecraftServerHash(
    serverId: String,
    sharedSecret: ByteArray,
    encodedPublicKey: ByteArray,
): String {
    // Vanilla hashes the legacy server ID as ISO-8859-1, whereas Kotlin's
    // portable encodeToByteArray API is UTF-8. Validate and encode that
    // one-byte wire contract explicitly before delegating SHA-1 to Okio.
    val serverIdBytes = ByteArray(serverId.length) { index ->
        val value = serverId[index].code
        require(value <= 0xFF) {
            "Server ID must be encodable as ISO-8859-1"
        }
        value.toByte()
    }
    val digest = (serverIdBytes + sharedSecret + encodedPublicKey)
        .toByteString()
        .sha1()
        .toByteArray()
    // Minecraft formats the SHA-1 bytes as a signed two's-complement integer,
    // not as the usual unsigned digest hex. Cryptography BigInt owns the
    // signed interpretation; only Minecraft's sign/magnitude text form is
    // applied here.
    val signed = digest.decodeToBigInt()
    val magnitude = signed.absoluteValue
        .magnitudeToByteArray()
        .toHexString()
        .trimStart('0')
        .ifEmpty { "0" }
    return if (signed.sign < 0) "-$magnitude" else magnitude
}

/**
 * Authentication or cryptographic validation failure.
 *
 * This is an [IllegalStateException] so multiplatform callers can handle the standard failure family directly.
 */
open class MinecraftAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** A transient session-service authentication failure. */
class MinecraftAuthenticationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)
