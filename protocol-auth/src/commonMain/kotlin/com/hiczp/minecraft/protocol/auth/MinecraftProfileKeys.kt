package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.PackedMessageSignature
import com.hiczp.minecraft.protocol.model.type.ProfilePublicKeyData
import okio.Buffer
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A parsed RSA profile public key. Parsing does not imply that its Mojang credential was trusted. */
class MinecraftProfilePublicKey(
    val profilePublicKeyData: ProfilePublicKeyData,
) {
    internal val minecraftRsaPublicKey = mapCryptographyFailure("Cannot decode the Minecraft profile public key") {
        PlatformMinecraftRsaBackend.decodePublicKey(
            encodedPublicKey = profilePublicKeyData.encodedKey.toByteArray(),
            minecraftRsaSignatureAlgorithm = MinecraftRsaSignatureAlgorithm.SHA256,
        )
    }

    val encodedKey: ByteArray
        get() = profilePublicKeyData.encodedKey.toByteArray()

    fun hasExpiredAt(epochMillis: Long): Boolean = profilePublicKeyData.expiresAtEpochMillis < epochMillis
}

/** A caller-owned player key pair obtained from Minecraft Services or imported from equivalent DER material. */
class MinecraftProfileKeyPair private constructor(
    internal val minecraftRsaPrivateKey: MinecraftRsaPrivateKey,
    val minecraftProfilePublicKey: MinecraftProfilePublicKey,
    val refreshedAfterEpochMillis: Long,
) {
    constructor(
        encodedPrivateKey: ByteArray,
        profilePublicKeyData: ProfilePublicKeyData,
        refreshedAfterEpochMillis: Long,
    ) : this(
        minecraftRsaPrivateKey = mapCryptographyFailure("Cannot decode the Minecraft profile private key") {
            PlatformMinecraftRsaBackend.decodePrivateKey(encodedPrivateKey.copyOf())
        },
        minecraftProfilePublicKey = MinecraftProfilePublicKey(profilePublicKeyData),
        refreshedAfterEpochMillis = refreshedAfterEpochMillis,
    )

    val profilePublicKeyData: ProfilePublicKeyData
        get() = minecraftProfilePublicKey.profilePublicKeyData

    fun needsRefreshAt(epochMillis: Long): Boolean = refreshedAfterEpochMillis < epochMillis
}

/** A parsed Mojang service public key used to validate profile-key credentials. */
class MinecraftServicesPublicKey(
    encodedPublicKey: ByteArray,
) {
    private val encoded = encodedPublicKey.copyOf()
    internal val minecraftRsaPublicKey = mapCryptographyFailure("Cannot decode a Minecraft Services public key") {
        PlatformMinecraftRsaBackend.decodePublicKey(
            encodedPublicKey = encoded,
            minecraftRsaSignatureAlgorithm = MinecraftRsaSignatureAlgorithm.SHA1,
        )
    }

    val encodedPublicKey: ByteArray
        get() = encoded.copyOf()
}

class MinecraftServicesPublicKeySet(
    profilePropertyKeys: List<MinecraftServicesPublicKey>,
    playerCertificateKeys: List<MinecraftServicesPublicKey>,
) {
    val profilePropertyKeys: List<MinecraftServicesPublicKey> = profilePropertyKeys.toList()
    val playerCertificateKeys: List<MinecraftServicesPublicKey> = playerCertificateKeys.toList()

    fun verifyProfilePublicKey(
        profileId: Uuid,
        profilePublicKeyData: ProfilePublicKeyData,
    ): Boolean = playerCertificateKeys.any { key ->
        MinecraftProfileKeySignatures.verify(key, profileId, profilePublicKeyData)
    }
}

/** Stateless composition and verification of Mojang's profile-public-key credential. */
object MinecraftProfileKeySignatures {
    fun signedPayload(
        profileId: Uuid,
        profilePublicKeyData: ProfilePublicKeyData,
    ): ByteArray = Buffer().apply {
        write(profileId.toByteArray())
        writeLong(profilePublicKeyData.expiresAtEpochMillis)
        write(profilePublicKeyData.encodedKey.toByteArray())
    }.readByteArray()

    fun verify(
        minecraftServicesPublicKey: MinecraftServicesPublicKey,
        profileId: Uuid,
        profilePublicKeyData: ProfilePublicKeyData,
    ): Boolean = mapCryptographyFailure("Cannot verify the Minecraft profile public-key credential") {
        PlatformMinecraftRsaBackend.rsaVerify(
            minecraftRsaPublicKey = minecraftServicesPublicKey.minecraftRsaPublicKey,
            payload = signedPayload(profileId, profilePublicKeyData),
            signature = profilePublicKeyData.keySignature.toByteArray(),
        )
    }
}

/** Stateless composition, signing, and verification of a chained player chat message. */
object MinecraftChatSignatures {
    fun signedPayload(
        signedMessageLink: SignedMessageLink,
        signedMessageBody: SignedMessageBody,
    ): ByteArray {
        val content = signedMessageBody.content.encodeToByteArray()
        return Buffer().apply {
            writeInt(SIGNATURE_VERSION)
            write(signedMessageLink.sender.toByteArray())
            write(signedMessageLink.sessionId.toByteArray())
            writeInt(signedMessageLink.index)
            writeLong(signedMessageBody.salt)
            writeLong(Instant.fromEpochMilliseconds(signedMessageBody.timestampEpochMillis).epochSeconds)
            writeInt(content.size)
            write(content)
            writeInt(signedMessageBody.lastSeen.size)
            signedMessageBody.lastSeen.forEach { signature ->
                write(signature.toByteArray())
            }
        }.readByteArray()
    }

    fun sign(
        minecraftProfileKeyPair: MinecraftProfileKeyPair,
        signedMessageLink: SignedMessageLink,
        signedMessageBody: SignedMessageBody,
    ): ByteString {
        val signature = mapCryptographyFailure("Cannot sign the Minecraft chat message") {
            PlatformMinecraftRsaBackend.rsaSha256Sign(
                minecraftRsaPrivateKey = minecraftProfileKeyPair.minecraftRsaPrivateKey,
                payload = signedPayload(signedMessageLink, signedMessageBody),
            )
        }
        if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
            throw MinecraftCryptographyException("A Minecraft profile private key produced an invalid signature size")
        }
        return ByteString(signature)
    }

    fun verify(
        minecraftProfilePublicKey: MinecraftProfilePublicKey,
        signedMessageLink: SignedMessageLink,
        signedMessageBody: SignedMessageBody,
        signature: ByteString,
    ): Boolean {
        if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
            return false
        }
        return mapCryptographyFailure("Cannot verify the Minecraft chat signature") {
            PlatformMinecraftRsaBackend.rsaVerify(
                minecraftRsaPublicKey = minecraftProfilePublicKey.minecraftRsaPublicKey,
                payload = signedPayload(signedMessageLink, signedMessageBody),
                signature = signature.toByteArray(),
            )
        }
    }

    private const val SIGNATURE_VERSION: Int = 1
}

fun MinecraftProfileKeyPair.signChatMessage(
    signedMessageLink: SignedMessageLink,
    signedMessageBody: SignedMessageBody,
): ByteString = MinecraftChatSignatures.sign(this, signedMessageLink, signedMessageBody)

fun MinecraftProfilePublicKey.verifyChatMessage(
    signedMessageLink: SignedMessageLink,
    signedMessageBody: SignedMessageBody,
    signature: ByteString,
): Boolean = MinecraftChatSignatures.verify(this, signedMessageLink, signedMessageBody, signature)

fun MinecraftProfileKeyPairResponse.toMinecraftProfileKeyPair(): MinecraftProfileKeyPair {
    val encodedPrivateKey = decodeMinecraftPem(
        value = keyPair.privateKey,
        header = RSA_PRIVATE_KEY_HEADER,
        footer = RSA_PRIVATE_KEY_FOOTER,
    )
    return try {
        val encodedPublicKey = decodeMinecraftPem(
            value = keyPair.publicKey,
            header = RSA_PUBLIC_KEY_HEADER,
            footer = RSA_PUBLIC_KEY_FOOTER,
        )
        MinecraftProfileKeyPair(
            encodedPrivateKey = encodedPrivateKey,
            profilePublicKeyData = ProfilePublicKeyData(
                expiresAtEpochMillis = Instant.parse(expiresAt).toEpochMilliseconds(),
                encodedKey = ByteString(encodedPublicKey),
                keySignature = ByteString(Base64.Default.decode(publicKeySignatureV2)),
            ),
            refreshedAfterEpochMillis = Instant.parse(refreshedAfter).toEpochMilliseconds(),
        )
    } finally {
        encodedPrivateKey.fill(0)
    }
}

fun MinecraftServicesPublicKeysResponse.toMinecraftServicesPublicKeySet(): MinecraftServicesPublicKeySet =
    MinecraftServicesPublicKeySet(
        profilePropertyKeys = profilePropertyKeys.orEmpty().map { key ->
            MinecraftServicesPublicKey(Base64.Default.decode(key.publicKey))
        },
        playerCertificateKeys = playerCertificateKeys.orEmpty().map { key ->
            MinecraftServicesPublicKey(Base64.Default.decode(key.publicKey))
        },
    )

private fun decodeMinecraftPem(
    value: String,
    header: String,
    footer: String,
): ByteArray {
    val encoded = if (header in value) value.substringAfter(header).substringBefore(footer) else value
    return Base64.Mime.decode(encoded)
}

private const val RSA_PRIVATE_KEY_HEADER = "-----BEGIN RSA PRIVATE KEY-----"
private const val RSA_PRIVATE_KEY_FOOTER = "-----END RSA PRIVATE KEY-----"
private const val RSA_PUBLIC_KEY_HEADER = "-----BEGIN RSA PUBLIC KEY-----"
private const val RSA_PUBLIC_KEY_FOOTER = "-----END RSA PUBLIC KEY-----"
