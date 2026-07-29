package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import kotlin.test.*

class MinecraftEncryptionContractTest {
    @Test
    fun createsAChallengeWithDefensiveKeyAndTokenValues() {
        val cryptography = IdentityCryptography()
        val originalKey = byteArrayOf(1, 2, 3)
        val keyPair = MinecraftRsaKeyPair(originalKey, TestPrivateKey)

        val challenge = MinecraftEncryption.createServerChallenge(
            cryptography = cryptography,
            keyPair = keyPair,
            shouldAuthenticate = false,
            serverId = "server",
            verifyTokenSize = 8,
        )

        originalKey[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), keyPair.publicKey)
        val exportedKey = keyPair.publicKey
        exportedKey[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), keyPair.publicKey)
        assertEquals("server", challenge.request.serverId)
        assertFalse(challenge.request.shouldAuthenticate)
        assertEquals(8, challenge.request.verifyToken.size)
        assertContentEquals(ByteArray(8) { it.toByte() }, challenge.verifyToken())

        val token = challenge.verifyToken()
        token[0] = 99
        assertContentEquals(ByteArray(8) { it.toByte() }, challenge.verifyToken())
    }

    @Test
    fun challengeAndServerHashRejectInvalidTextAndSizes() {
        val cryptography = IdentityCryptography()
        assertFailsWith<IllegalArgumentException> {
            MinecraftEncryption.createServerChallenge(
                cryptography,
                serverId = "x".repeat(21),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftEncryption.createServerChallenge(
                cryptography,
                verifyTokenSize = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            minecraftServerHash("\u0100", byteArrayOf(), byteArrayOf())
        }
    }

    @Test
    fun answersAndAcceptsAnIdentityEncryptedChallenge() {
        val cryptography = IdentityCryptography()
        val challenge = MinecraftEncryption.createServerChallenge(cryptography)

        val answer = MinecraftEncryption.answerServerChallenge(
            challenge.request,
            cryptography,
        )
        val accepted = MinecraftEncryption.acceptClientResponse(
            challenge,
            answer.response,
            cryptography,
        )

        assertEquals(16, accepted.size)
        assertContentEquals(answer.sharedSecret, accepted)
        val exported = answer.sharedSecret
        exported[0] = 99
        assertContentEquals(ByteArray(16) { it.toByte() }, answer.sharedSecret)
    }

    @Test
    fun rejectsWrongTokenAndNonAesSharedSecretLengths() {
        val cryptography = IdentityCryptography()
        val challenge = MinecraftEncryption.createServerChallenge(cryptography)
        assertFailsWith<MinecraftAuthenticationException> {
            MinecraftEncryption.acceptClientResponse(
                challenge,
                EncryptionResponsePacket(
                    sharedSecret = ByteString(ByteArray(16)),
                    verifyToken = ByteString(byteArrayOf(9, 9, 9, 9)),
                ),
                cryptography,
            )
        }
        assertFailsWith<MinecraftAuthenticationException> {
            MinecraftEncryption.acceptClientResponse(
                challenge,
                EncryptionResponsePacket(
                    sharedSecret = ByteString(ByteArray(15)),
                    verifyToken = challenge.request.verifyToken,
                ),
                cryptography,
            )
        }
    }

    private object TestPrivateKey : MinecraftRsaPrivateKey

    private class IdentityCryptography : MinecraftCryptography {
        override fun secureRandomBytes(size: Int): ByteArray =
            ByteArray(size) { it.toByte() }

        override fun generateRsaKeyPair(keySizeBits: Int): MinecraftRsaKeyPair =
            MinecraftRsaKeyPair(byteArrayOf(1, 2, 3), TestPrivateKey)

        override fun rsaEncrypt(
            encodedPublicKey: ByteArray,
            plaintext: ByteArray,
        ): ByteArray = plaintext.copyOf()

        override fun rsaDecrypt(
            privateKey: MinecraftRsaPrivateKey,
            ciphertext: ByteArray,
        ): ByteArray = ciphertext.copyOf()
    }
}
