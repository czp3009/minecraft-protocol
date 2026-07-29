package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.EncryptionRequestPacket
import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString

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
        if (!constantTimeEquals(verifyToken, challenge.verifyToken())) {
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
    val serverIdBytes = ByteArray(serverId.length) { index ->
        val value = serverId[index].code
        require(value <= 0xFF) {
            "Server ID must be encodable as ISO-8859-1"
        }
        value.toByte()
    }
    val digest = Sha1.digest(serverIdBytes + sharedSecret + encodedPublicKey)
    return signedHex(digest)
}

private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
    var difference = first.size xor second.size
    val maximum = maxOf(first.size, second.size)
    for (index in 0 until maximum) {
        val firstByte = if (index < first.size) first[index].toInt() else 0
        val secondByte = if (index < second.size) second[index].toInt() else 0
        difference = difference or (firstByte xor secondByte)
    }
    return difference == 0
}

private fun signedHex(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "0"
    val negative = bytes[0].toInt() and 0x80 != 0
    val magnitude =
        if (negative) {
            val value = bytes.map { it.toInt().inv() and 0xFF }.toIntArray()
            var carry = 1
            for (index in value.lastIndex downTo 0) {
                val sum = value[index] + carry
                value[index] = sum and 0xFF
                carry = sum ushr 8
            }
            value
        } else {
            bytes.map { it.toInt() and 0xFF }.toIntArray()
        }
    val firstNonZero = magnitude.indexOfFirst { it != 0 }
    if (firstNonZero < 0) return "0"
    val hexadecimal = buildString {
        append(magnitude[firstNonZero].toString(16))
        for (index in firstNonZero + 1..magnitude.lastIndex) {
            append(magnitude[index].toString(16).padStart(2, '0'))
        }
    }
    return if (negative) "-$hexadecimal" else hexadecimal
}

open class MinecraftAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class MinecraftAuthenticationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)
