package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.EncryptionRequestPacket
import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import dev.whyoleg.cryptography.bigint.decodeToBigInt
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.toByteString

/** Opaque RSA state generated once for an online server configuration. */
class MinecraftServerEncryptionContext internal constructor(
    internal val keyPair: MinecraftRsaKeyPair,
) {
    /** The X.509 SubjectPublicKeyInfo DER sent in an Encryption Request. */
    val encodedPublicKey: ByteArray
        get() = keyPair.publicKey

    override fun toString(): String =
        "MinecraftServerEncryptionContext(encodedPublicKey=<redacted>)"
}

class MinecraftEncryptionChallenge internal constructor(
    val request: EncryptionRequestPacket,
    internal val context: MinecraftServerEncryptionContext,
    verifyToken: ByteArray,
) {
    private val expectedVerifyToken = verifyToken.copyOf()

    internal fun verifyToken(): ByteArray = expectedVerifyToken.copyOf()

    override fun toString(): String =
        "MinecraftEncryptionChallenge(request=$request, expectedVerifyToken=<redacted>)"
}

class MinecraftClientEncryption internal constructor(
    val response: EncryptionResponsePacket,
    sharedSecret: ByteArray,
) {
    private val secret = sharedSecret.copyOf()

    /** The 16-byte AES key/IV that a socket transport enables after sending [response]. */
    val sharedSecret: ByteArray
        get() = secret.copyOf()

    override fun toString(): String =
        "MinecraftClientEncryption(response=$response, sharedSecret=<redacted>)"
}

/**
 * Cross-platform Minecraft Login cryptography.
 *
 * The platform RSA implementation is selected by the library. Callers never supply a JCA, OpenSSL, Apple, or
 * JavaScript provider. This object intentionally produces the shared secret but does not encrypt a socket stream.
 */
object MinecraftEncryption {
    /** Generates one vanilla-compatible RSA-1024 context for reuse by an online server configuration. */
    suspend fun createServerContext(): MinecraftServerEncryptionContext =
        mapSuspendCryptographyFailure("Cannot generate the Minecraft RSA key pair") {
            MinecraftServerEncryptionContext(
                PlatformMinecraftRsaBackend.generateRsaKeyPair(),
            )
        }

    /** Creates a fresh per-connection challenge from a reusable server [context]. */
    fun createServerChallenge(
        context: MinecraftServerEncryptionContext,
        shouldAuthenticate: Boolean = true,
    ): MinecraftEncryptionChallenge {
        val verifyToken = secureRandomBytes(VANILLA_VERIFY_TOKEN_BYTES)
        return MinecraftEncryptionChallenge(
            request = EncryptionRequestPacket(
                serverId = "",
                publicKey = ByteString(context.encodedPublicKey),
                verifyToken = ByteString(verifyToken),
                shouldAuthenticate = shouldAuthenticate,
            ),
            context = context,
            verifyToken = verifyToken,
        )
    }

    /**
     * Generates a fresh 16-byte shared secret and encrypts it and the original challenge with the server RSA key.
     */
    fun answerServerChallenge(
        request: EncryptionRequestPacket,
    ): MinecraftClientEncryption {
        val sharedSecret = secureRandomBytes(MINECRAFT_SHARED_SECRET_BYTES)
        val publicKey = request.publicKey.toByteArray()
        return try {
            mapCryptographyFailure("Cannot answer the Minecraft Encryption Request") {
                MinecraftClientEncryption(
                    response = EncryptionResponsePacket(
                        sharedSecret = ByteString(
                            PlatformMinecraftRsaBackend.rsaEncrypt(
                                publicKey,
                                sharedSecret,
                            ),
                        ),
                        verifyToken = ByteString(
                            PlatformMinecraftRsaBackend.rsaEncrypt(
                                publicKey,
                                request.verifyToken.toByteArray(),
                            ),
                        ),
                    ),
                    sharedSecret = sharedSecret,
                )
            }
        } catch (failure: Throwable) {
            sharedSecret.fill(0)
            throw failure
        } finally {
            publicKey.fill(0)
        }
    }

    /** Decrypts and validates a client response, returning a defensive copy of its 16-byte shared secret. */
    fun acceptClientResponse(
        challenge: MinecraftEncryptionChallenge,
        response: EncryptionResponsePacket,
    ): ByteArray {
        val verifyToken = mapCryptographyFailure("Cannot decrypt the Minecraft verify token") {
            PlatformMinecraftRsaBackend.rsaDecrypt(
                challenge.context.keyPair.privateKey,
                response.verifyToken.toByteArray(),
            )
        }
        val expectedVerifyToken = challenge.verifyToken()
        try {
            if (
                !verifyToken.toByteString().equals(
                    expectedVerifyToken.toByteString(),
                    constantTime = true,
                )
            ) {
                throw MinecraftAuthenticationException(
                    "Encryption Response verify token does not match",
                )
            }
        } finally {
            verifyToken.fill(0)
            expectedVerifyToken.fill(0)
        }

        val sharedSecret = mapCryptographyFailure("Cannot decrypt the Minecraft shared secret") {
            PlatformMinecraftRsaBackend.rsaDecrypt(
                challenge.context.keyPair.privateKey,
                response.sharedSecret.toByteArray(),
            )
        }
        if (sharedSecret.size != MINECRAFT_SHARED_SECRET_BYTES) {
            sharedSecret.fill(0)
            throw MinecraftAuthenticationException(
                "Minecraft shared secret must contain $MINECRAFT_SHARED_SECRET_BYTES bytes",
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
    // Vanilla hashes the legacy server ID as ISO-8859-1, whereas Kotlin's portable encodeToByteArray API is UTF-8.
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
    // Minecraft renders the digest as Java's signed two's-complement BigInteger, not unsigned SHA-1 hex.
    val signed = digest.decodeToBigInt()
    val magnitude = signed.absoluteValue
        .magnitudeToByteArray()
        .toHexString()
        .trimStart('0')
        .ifEmpty { "0" }
    return if (signed.sign < 0) "-$magnitude" else magnitude
}

internal fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0)
    return mapCryptographyFailure("Cannot obtain cryptographically secure random bytes") {
        CryptographyRandom.nextBytes(size)
    }
}

private suspend inline fun <T> mapSuspendCryptographyFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: MinecraftAuthenticationException) {
    throw failure
} catch (failure: Throwable) {
    throw MinecraftCryptographyException(message, failure)
}

private inline fun <T> mapCryptographyFailure(
    message: String,
    operation: () -> T,
): T = try {
    operation()
} catch (failure: MinecraftAuthenticationException) {
    throw failure
} catch (failure: Throwable) {
    throw MinecraftCryptographyException(message, failure)
}

/** Authentication or cryptographic validation failure. */
open class MinecraftAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** A platform cryptography capability or provider failure. */
class MinecraftCryptographyException(
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)

/** A transient authentication-service failure. */
class MinecraftAuthenticationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : MinecraftAuthenticationException(message, cause)

private const val VANILLA_VERIFY_TOKEN_BYTES = 4
private const val MINECRAFT_SHARED_SECRET_BYTES = 16
