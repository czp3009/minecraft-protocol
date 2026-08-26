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
        val minecraftProfileKeyPair = fixtureKeyPair()
        val signedMessageLink = fixtureLink()
        val signedMessageBody = fixtureBody()
        val payload = MinecraftChatSignatures.signedPayload(signedMessageLink, signedMessageBody)

        assertTrue(payload.size == 582)
        assertTrue(payload.toByteString().sha256().hex() == MinecraftChatCryptoFixtures.CHAT_PAYLOAD_SHA256)
        val signature = minecraftProfileKeyPair.signChatMessage(signedMessageLink, signedMessageBody)
        assertContentEquals(MinecraftChatCryptoFixtures.chatSignature(), signature.toByteArray())
        assertTrue(minecraftProfileKeyPair.minecraftProfilePublicKey.verifyChatMessage(signedMessageLink, signedMessageBody, signature))

        val tampered = signedMessageBody.copy(content = "Hi?")
        assertFalse(minecraftProfileKeyPair.minecraftProfilePublicKey.verifyChatMessage(signedMessageLink, tampered, signature))
        assertFalse(
            minecraftProfileKeyPair.minecraftProfilePublicKey.verifyChatMessage(
                signedMessageLink,
                signedMessageBody,
                ByteString(ByteArray(255)),
            ),
        )
    }

    @Test
    fun matchesIndependentSha1WithRsaProfileCredentialVector() {
        val profilePublicKeyData = fixturePublicKeyData()
        val profileId = Uuid.parse("12345678-1234-5678-9abc-def012345678")
        val payload = MinecraftProfileKeySignatures.signedPayload(profileId, profilePublicKeyData)
        val minecraftServicesPublicKey = MinecraftServicesPublicKey(MinecraftChatCryptoFixtures.publicKey())
        val minecraftServicesPublicKeySet = MinecraftServicesPublicKeySet(
            profilePropertyKeys = emptyList(),
            playerCertificateKeys = listOf(minecraftServicesPublicKey),
        )

        assertTrue(payload.toByteString().sha256().hex() == MinecraftChatCryptoFixtures.CREDENTIAL_PAYLOAD_SHA256)
        assertTrue(MinecraftProfileKeySignatures.verify(minecraftServicesPublicKey, profileId, profilePublicKeyData))
        assertTrue(minecraftServicesPublicKeySet.verifyProfilePublicKey(profileId, profilePublicKeyData))
        assertFalse(
            minecraftServicesPublicKeySet.verifyProfilePublicKey(
                Uuid.parse("12345678-1234-5678-9abc-def012345679"),
                profilePublicKeyData,
            ),
        )
        assertFalse(
            minecraftServicesPublicKeySet.verifyProfilePublicKey(
                profileId,
                profilePublicKeyData.copy(keySignature = ByteString(byteArrayOf(1))),
            ),
        )
    }

    @Test
    fun exposesCallerDrivenExpiryAndRefreshChecks() {
        val minecraftProfileKeyPair = fixtureKeyPair()

        assertFalse(minecraftProfileKeyPair.minecraftProfilePublicKey.hasExpiredAt(1_800_000_000_123))
        assertTrue(minecraftProfileKeyPair.minecraftProfilePublicKey.hasExpiredAt(1_800_000_000_124))
        assertFalse(minecraftProfileKeyPair.needsRefreshAt(1_700_000_000_000))
        assertTrue(minecraftProfileKeyPair.needsRefreshAt(1_700_000_000_001))
    }
}

internal fun fixtureKeyPair(): MinecraftProfileKeyPair = MinecraftProfileKeyPair(
    encodedPrivateKey = MinecraftChatCryptoFixtures.privateKey(),
    profilePublicKeyData = fixturePublicKeyData(),
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
