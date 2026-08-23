package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.ProfilePublicKeyData
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MinecraftChatSignatureTest {
    @Test
    fun matchesIndependentSha256WithRsaChatVector() {
        val keyPair = fixtureKeyPair()
        val link = fixtureLink()
        val body = fixtureBody()
        val payload = MinecraftChatSignatures.signedPayload(link, body)

        assertTrue(payload.size == 582)
        assertTrue(payload.toByteString().sha256().hex() == MinecraftChatCryptoFixtures.CHAT_PAYLOAD_SHA256)
        val signature = keyPair.signChatMessage(link, body)
        assertContentEquals(MinecraftChatCryptoFixtures.chatSignature(), signature.toByteArray())
        assertTrue(keyPair.publicKey.verifyChatMessage(link, body, signature))

        val tampered = body.copy(content = "Hi?")
        assertFalse(keyPair.publicKey.verifyChatMessage(link, tampered, signature))
        assertFalse(
            keyPair.publicKey.verifyChatMessage(
                link,
                body,
                ByteString(ByteArray(255)),
            ),
        )
    }

    @Test
    fun matchesIndependentSha1WithRsaProfileCredentialVector() {
        val publicKeyData = fixturePublicKeyData()
        val profileId = Uuid.parse("12345678-1234-5678-9abc-def012345678")
        val payload = MinecraftProfileKeySignatures.signedPayload(profileId, publicKeyData)
        val servicesKey = MinecraftServicesPublicKey(MinecraftChatCryptoFixtures.publicKey())
        val keySet = MinecraftServicesPublicKeySet(
            profilePropertyKeys = emptyList(),
            playerCertificateKeys = listOf(servicesKey),
        )

        assertTrue(payload.toByteString().sha256().hex() == MinecraftChatCryptoFixtures.CREDENTIAL_PAYLOAD_SHA256)
        assertTrue(MinecraftProfileKeySignatures.verify(servicesKey, profileId, publicKeyData))
        assertTrue(keySet.verifyProfilePublicKey(profileId, publicKeyData))
        assertFalse(
            keySet.verifyProfilePublicKey(
                Uuid.parse("12345678-1234-5678-9abc-def012345679"),
                publicKeyData,
            ),
        )
        assertFalse(
            keySet.verifyProfilePublicKey(
                profileId,
                publicKeyData.copy(keySignature = ByteString(byteArrayOf(1))),
            ),
        )
    }

    @Test
    fun exposesCallerDrivenExpiryAndRefreshChecks() {
        val keyPair = fixtureKeyPair()

        assertFalse(keyPair.publicKey.hasExpiredAt(1_800_000_000_123))
        assertTrue(keyPair.publicKey.hasExpiredAt(1_800_000_000_124))
        assertFalse(keyPair.needsRefreshAt(1_700_000_000_000))
        assertTrue(keyPair.needsRefreshAt(1_700_000_000_001))
    }
}

internal fun fixtureKeyPair(): MinecraftProfileKeyPair = MinecraftProfileKeyPair(
    encodedPrivateKey = MinecraftChatCryptoFixtures.privateKey(),
    publicKeyData = fixturePublicKeyData(),
    refreshedAfterEpochMillis = 1_700_000_000_000,
)

internal fun fixturePublicKeyData(): ProfilePublicKeyData = ProfilePublicKeyData(
    expiresAtEpochMillis = 1_800_000_000_123,
    encodedKey = ByteString(MinecraftChatCryptoFixtures.publicKey()),
    keySignature = ByteString(MinecraftChatCryptoFixtures.credentialSignature()),
)

internal fun fixtureLink(): SignedMessageLink = SignedMessageLink(
    index = 3,
    sender = Uuid.parse("00000000-0000-0000-0000-000000000001"),
    sessionId = Uuid.parse("00000000-0000-0000-0000-000000000002"),
)

internal fun fixtureBody(): SignedMessageBody = SignedMessageBody(
    content = "Hi😀",
    timestampEpochMillis = 1_700_000_000_123,
    salt = 0x0102030405060708,
    lastSeen = listOf(
        ByteString(ByteArray(256) { 0x11 }),
        ByteString(ByteArray(256) { 0x22 }),
    ),
)
