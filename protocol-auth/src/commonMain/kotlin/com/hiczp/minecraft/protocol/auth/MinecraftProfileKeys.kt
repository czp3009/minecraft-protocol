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
    val data: ProfilePublicKeyData,
) {
    internal val rsaPublicKey = mapCryptographyFailure("Cannot decode the Minecraft profile public key") {
        PlatformMinecraftRsaBackend.decodePublicKey(
            encodedPublicKey = data.encodedKey.toByteArray(),
            signatureAlgorithm = MinecraftRsaSignatureAlgorithm.SHA256,
        )
    }

    val encodedKey: ByteArray
        get() = data.encodedKey.toByteArray()

    fun hasExpiredAt(epochMillis: Long): Boolean = data.expiresAtEpochMillis < epochMillis
}

/** A caller-owned player key pair obtained from Minecraft Services or imported from equivalent DER material. */
class MinecraftProfileKeyPair private constructor(
    internal val rsaPrivateKey: MinecraftRsaPrivateKey,
    val publicKey: MinecraftProfilePublicKey,
    val refreshedAfterEpochMillis: Long,
) {
    constructor(
        encodedPrivateKey: ByteArray,
        publicKeyData: ProfilePublicKeyData,
        refreshedAfterEpochMillis: Long,
    ) : this(
        rsaPrivateKey = mapCryptographyFailure("Cannot decode the Minecraft profile private key") {
            PlatformMinecraftRsaBackend.decodePrivateKey(encodedPrivateKey.copyOf())
        },
        publicKey = MinecraftProfilePublicKey(publicKeyData),
        refreshedAfterEpochMillis = refreshedAfterEpochMillis,
    )

    val publicKeyData: ProfilePublicKeyData
        get() = publicKey.data

    fun needsRefreshAt(epochMillis: Long): Boolean = refreshedAfterEpochMillis < epochMillis
}

/** A parsed Mojang service public key used to validate profile-key credentials. */
class MinecraftServicesPublicKey(
    encodedPublicKey: ByteArray,
) {
    private val encoded = encodedPublicKey.copyOf()
    internal val rsaPublicKey = mapCryptographyFailure("Cannot decode a Minecraft Services public key") {
        PlatformMinecraftRsaBackend.decodePublicKey(
            encodedPublicKey = encoded,
            signatureAlgorithm = MinecraftRsaSignatureAlgorithm.SHA1,
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
        publicKeyData: ProfilePublicKeyData,
    ): Boolean = playerCertificateKeys.any { key ->
        MinecraftProfileKeySignatures.verify(key, profileId, publicKeyData)
    }
}

/** Stateless composition and verification of Mojang's profile-public-key credential. */
object MinecraftProfileKeySignatures {
    fun signedPayload(
        profileId: Uuid,
        publicKeyData: ProfilePublicKeyData,
    ): ByteArray = Buffer().apply {
        write(profileId.toByteArray())
        writeLong(publicKeyData.expiresAtEpochMillis)
        write(publicKeyData.encodedKey.toByteArray())
    }.readByteArray()

    fun verify(
        servicesPublicKey: MinecraftServicesPublicKey,
        profileId: Uuid,
        publicKeyData: ProfilePublicKeyData,
    ): Boolean = mapCryptographyFailure("Cannot verify the Minecraft profile public-key credential") {
        PlatformMinecraftRsaBackend.rsaVerify(
            publicKey = servicesPublicKey.rsaPublicKey,
            payload = signedPayload(profileId, publicKeyData),
            signature = publicKeyData.keySignature.toByteArray(),
        )
    }
}

/** Stateless composition, signing, and verification of a chained player chat message. */
object MinecraftChatSignatures {
    fun signedPayload(
        link: SignedMessageLink,
        body: SignedMessageBody,
    ): ByteArray {
        val content = body.content.encodeToByteArray()
        return Buffer().apply {
            writeInt(SIGNATURE_VERSION)
            write(link.sender.toByteArray())
            write(link.sessionId.toByteArray())
            writeInt(link.index)
            writeLong(body.salt)
            writeLong(Instant.fromEpochMilliseconds(body.timestampEpochMillis).epochSeconds)
            writeInt(content.size)
            write(content)
            writeInt(body.lastSeen.size)
            body.lastSeen.forEach { signature ->
                write(signature.toByteArray())
            }
        }.readByteArray()
    }

    fun sign(
        keyPair: MinecraftProfileKeyPair,
        link: SignedMessageLink,
        body: SignedMessageBody,
    ): ByteString {
        val signature = mapCryptographyFailure("Cannot sign the Minecraft chat message") {
            PlatformMinecraftRsaBackend.rsaSha256Sign(
                privateKey = keyPair.rsaPrivateKey,
                payload = signedPayload(link, body),
            )
        }
        if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
            throw MinecraftCryptographyException("A Minecraft profile private key produced an invalid signature size")
        }
        return ByteString(signature)
    }

    fun verify(
        publicKey: MinecraftProfilePublicKey,
        link: SignedMessageLink,
        body: SignedMessageBody,
        signature: ByteString,
    ): Boolean {
        if (signature.size != PackedMessageSignature.SIGNATURE_BYTES) {
            return false
        }
        return mapCryptographyFailure("Cannot verify the Minecraft chat signature") {
            PlatformMinecraftRsaBackend.rsaVerify(
                publicKey = publicKey.rsaPublicKey,
                payload = signedPayload(link, body),
                signature = signature.toByteArray(),
            )
        }
    }

    private const val SIGNATURE_VERSION: Int = 1
}

fun MinecraftProfileKeyPair.signChatMessage(
    link: SignedMessageLink,
    body: SignedMessageBody,
): ByteString = MinecraftChatSignatures.sign(this, link, body)

fun MinecraftProfilePublicKey.verifyChatMessage(
    link: SignedMessageLink,
    body: SignedMessageBody,
    signature: ByteString,
): Boolean = MinecraftChatSignatures.verify(this, link, body, signature)

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
            publicKeyData = ProfilePublicKeyData(
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
