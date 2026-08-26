package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.EncryptionRequestPacket
import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import dev.whyoleg.cryptography.bigint.decodeToBigInt
import dev.whyoleg.cryptography.random.CryptographyRandom
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.cancellation.CancellationException

data class MinecraftServerHash(
    val value: String,
) {
    companion object {
        fun compute(
            serverId: String,
            sharedSecret: ByteArray,
            encodedPublicKey: ByteArray,
        ): MinecraftServerHash {
            val serverIdBytes = ByteArray(serverId.length) { index ->
                val value = serverId[index].code
                require(value <= 0xFF) {
                    "Server ID must be encodable as ISO-8859-1"
                }
                value.toByte()
            }
            val digest = Buffer().apply {
                write(serverIdBytes)
                write(sharedSecret)
                write(encodedPublicKey)
            }.readByteString().sha1().toByteArray()
            val signed = digest.decodeToBigInt()
            val magnitude = signed.absoluteValue
                .magnitudeToByteArray()
                .toHexString()
                .trimStart('0')
                .ifEmpty { "0" }
            return MinecraftServerHash(
                if (signed.sign < 0) "-$magnitude" else magnitude,
            )
        }
    }
}

data class MinecraftClientKeyExchangeResult(
    val encryptedSharedSecret: ByteArray,
    val encryptedVerifyToken: ByteArray,
    val sharedSecret: ByteArray,
    val minecraftServerHash: MinecraftServerHash,
) {
    override fun equals(other: Any?): Boolean =
        other is MinecraftClientKeyExchangeResult &&
                encryptedSharedSecret.contentEquals(other.encryptedSharedSecret) &&
                encryptedVerifyToken.contentEquals(other.encryptedVerifyToken) &&
                sharedSecret.contentEquals(other.sharedSecret) &&
                minecraftServerHash == other.minecraftServerHash

    override fun hashCode(): Int {
        var result = encryptedSharedSecret.contentHashCode()
        result = 31 * result + encryptedVerifyToken.contentHashCode()
        result = 31 * result + sharedSecret.contentHashCode()
        result = 31 * result + minecraftServerHash.hashCode()
        return result
    }
}

data class MinecraftServerKeyExchangeResult(
    val sharedSecret: ByteArray,
    val minecraftServerHash: MinecraftServerHash,
) {
    override fun equals(other: Any?): Boolean =
        other is MinecraftServerKeyExchangeResult &&
                sharedSecret.contentEquals(other.sharedSecret) &&
                minecraftServerHash == other.minecraftServerHash

    override fun hashCode(): Int {
        var result = sharedSecret.contentHashCode()
        result = 31 * result + minecraftServerHash.hashCode()
        return result
    }
}

class MinecraftServerKeyPair private constructor(
    internal val minecraftRsaKeyPair: MinecraftRsaKeyPair,
) {
    /**
     * Creates a key pair from a DER-encoded X.509 SubjectPublicKeyInfo public key and PKCS#8 private key.
     */
    constructor(
        encodedPublicKey: ByteArray,
        encodedPrivateKey: ByteArray,
    ) : this(
        mapCryptographyFailure("Cannot decode the Minecraft RSA key pair") {
            MinecraftRsaKeyPair(
                publicKey = encodedPublicKey,
                minecraftRsaPrivateKey = PlatformMinecraftRsaBackend.decodePrivateKey(encodedPrivateKey),
            )
        },
    )

    val encodedPublicKey: ByteArray
        get() = minecraftRsaKeyPair.publicKey

    fun createChallenge(
        serverId: String = "",
        shouldAuthenticate: Boolean = true,
    ): MinecraftServerChallenge {
        require(serverId.length <= MAX_SERVER_ID_LENGTH) {
            "Minecraft server ID cannot exceed $MAX_SERVER_ID_LENGTH characters"
        }
        val verifyToken = secureRandomBytes(VANILLA_VERIFY_TOKEN_BYTES)
        return MinecraftServerChallenge(
            serverId = serverId,
            shouldAuthenticate = shouldAuthenticate,
            minecraftServerKeyPair = this,
            expectedVerifyToken = verifyToken,
        )
    }

    companion object {
        suspend fun generate(): MinecraftServerKeyPair =
            mapCryptographyFailure(
                "Cannot generate the Minecraft RSA key pair",
            ) {
                MinecraftServerKeyPair(
                    PlatformMinecraftRsaBackend.generateRsaKeyPair(),
                )
            }
    }
}

class MinecraftServerChallenge internal constructor(
    val serverId: String,
    val shouldAuthenticate: Boolean,
    private val minecraftServerKeyPair: MinecraftServerKeyPair,
    expectedVerifyToken: ByteArray,
) {
    private val expectedVerifyToken = expectedVerifyToken.copyOf()

    val encodedPublicKey: ByteArray
        get() = minecraftServerKeyPair.encodedPublicKey

    val verifyToken: ByteArray
        get() = expectedVerifyToken.copyOf()

    fun accept(
        encryptedSharedSecret: ByteArray,
        encryptedVerifyToken: ByteArray,
    ): MinecraftServerKeyExchangeResult {
        val verifyToken = mapKeyExchangeFailure(
            "Cannot decrypt the Minecraft verify token",
        ) {
            PlatformMinecraftRsaBackend.rsaDecrypt(
                minecraftServerKeyPair.minecraftRsaKeyPair.minecraftRsaPrivateKey,
                encryptedVerifyToken,
            )
        }
        try {
            if (
                !verifyToken.toByteString().equals(
                    expectedVerifyToken.toByteString(),
                    constantTime = true,
                )
            ) {
                throw MinecraftKeyExchangeException(
                    "Encryption Response verify token does not match",
                )
            }
        } finally {
            verifyToken.fill(0)
        }
        val decryptedSecret = mapKeyExchangeFailure(
            "Cannot decrypt the Minecraft shared secret",
        ) {
            PlatformMinecraftRsaBackend.rsaDecrypt(
                minecraftServerKeyPair.minecraftRsaKeyPair.minecraftRsaPrivateKey,
                encryptedSharedSecret,
            )
        }
        try {
            if (decryptedSecret.size != MINECRAFT_SHARED_SECRET_BYTES) {
                throw MinecraftKeyExchangeException(
                    "Minecraft shared secret must contain $MINECRAFT_SHARED_SECRET_BYTES bytes",
                )
            }
            return MinecraftServerKeyExchangeResult(
                sharedSecret = decryptedSecret.copyOf(),
                minecraftServerHash = MinecraftServerHash.compute(
                    serverId = serverId,
                    sharedSecret = decryptedSecret,
                    encodedPublicKey = encodedPublicKey,
                ),
            )
        } finally {
            decryptedSecret.fill(0)
        }
    }
}

object MinecraftClientKeyExchange {
    fun respond(
        serverId: String,
        encodedPublicKey: ByteArray,
        verifyToken: ByteArray,
    ): MinecraftClientKeyExchangeResult {
        val rawSecret = secureRandomBytes(MINECRAFT_SHARED_SECRET_BYTES)
        try {
            val encryptedSharedSecret = mapCryptographyFailure(
                "Cannot answer the Minecraft Encryption Request",
            ) {
                PlatformMinecraftRsaBackend.rsaEncrypt(
                    encodedPublicKey,
                    rawSecret,
                )
            }
            val encryptedVerifyToken = mapCryptographyFailure(
                "Cannot answer the Minecraft Encryption Request",
            ) {
                PlatformMinecraftRsaBackend.rsaEncrypt(
                    encodedPublicKey,
                    verifyToken,
                )
            }
            return MinecraftClientKeyExchangeResult(
                encryptedSharedSecret = encryptedSharedSecret,
                encryptedVerifyToken = encryptedVerifyToken,
                sharedSecret = rawSecret.copyOf(),
                minecraftServerHash = MinecraftServerHash.compute(
                    serverId = serverId,
                    sharedSecret = rawSecret,
                    encodedPublicKey = encodedPublicKey,
                ),
            )
        } finally {
            rawSecret.fill(0)
        }
    }
}

fun MinecraftClientKeyExchange.respond(
    encryptionRequestPacket: EncryptionRequestPacket,
): MinecraftClientKeyExchangeResult = respond(
    serverId = encryptionRequestPacket.serverId,
    encodedPublicKey = encryptionRequestPacket.publicKey.toByteArray(),
    verifyToken = encryptionRequestPacket.verifyToken.toByteArray(),
)

fun MinecraftClientKeyExchangeResult.toEncryptionResponsePacket(): EncryptionResponsePacket =
    EncryptionResponsePacket(
        sharedSecret = ByteString(encryptedSharedSecret),
        verifyToken = ByteString(encryptedVerifyToken),
    )

fun MinecraftServerChallenge.toEncryptionRequestPacket(): EncryptionRequestPacket =
    EncryptionRequestPacket(
        serverId = serverId,
        publicKey = ByteString(encodedPublicKey),
        verifyToken = ByteString(verifyToken),
        shouldAuthenticate = shouldAuthenticate,
    )

fun MinecraftServerChallenge.accept(
    encryptionResponsePacket: EncryptionResponsePacket,
): MinecraftServerKeyExchangeResult = accept(
    encryptedSharedSecret = encryptionResponsePacket.sharedSecret.toByteArray(),
    encryptedVerifyToken = encryptionResponsePacket.verifyToken.toByteArray(),
)

class MinecraftCryptographyException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

open class MinecraftKeyExchangeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun secureRandomBytes(size: Int): ByteArray =
    mapCryptographyFailure(
        "Cannot obtain cryptographically secure random bytes",
    ) {
        CryptographyRandom.nextBytes(size)
    }

internal inline fun <T> mapCryptographyFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: MinecraftCryptographyException) {
    throw failure
} catch (failure: Throwable) {
    throw MinecraftCryptographyException(message, failure)
}

private inline fun <T> mapKeyExchangeFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: MinecraftKeyExchangeException) {
    throw failure
} catch (failure: Throwable) {
    throw MinecraftKeyExchangeException(message, failure)
}

private const val VANILLA_VERIFY_TOKEN_BYTES = 4
private const val MINECRAFT_SHARED_SECRET_BYTES = 16
private const val MAX_SERVER_ID_LENGTH = 20
