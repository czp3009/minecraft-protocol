package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.EncryptionRequestPacket
import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.*

class MinecraftEncryptionContractTest {
    @Test
    fun createsVanillaChallengeWithDefensiveContextValues() = runTest {
        val context = MinecraftEncryption.createServerContext()
        val encodedPublicKey = context.encodedPublicKey
        val challenge = MinecraftEncryption.createServerChallenge(
            context = context,
            shouldAuthenticate = false,
        )

        assertTrue(encodedPublicKey.size > 128)
        encodedPublicKey.fill(0)
        assertFalse(context.encodedPublicKey.all { it == 0.toByte() })
        assertEquals("", challenge.request.serverId)
        assertFalse(challenge.request.shouldAuthenticate)
        assertEquals(4, challenge.request.verifyToken.size)
        assertContentEquals(
            context.encodedPublicKey,
            challenge.request.publicKey.toByteArray(),
        )

        val exportedToken = challenge.verifyToken()
        val expectedToken = exportedToken.copyOf()
        exportedToken.fill(0)
        assertContentEquals(expectedToken, challenge.verifyToken())
    }

    @Test
    fun answersAndAcceptsAPlatformEncryptedChallenge() = runTest {
        val context = MinecraftEncryption.createServerContext()
        val challenge = MinecraftEncryption.createServerChallenge(context)

        val answer = MinecraftEncryption.answerServerChallenge(challenge.request)
        val accepted = MinecraftEncryption.acceptClientResponse(
            challenge,
            answer.response,
        )

        assertEquals(16, accepted.size)
        assertContentEquals(answer.sharedSecret, accepted)
        val exported = answer.sharedSecret
        val expectedSecret = exported.copyOf()
        exported.fill(0)
        assertContentEquals(expectedSecret, answer.sharedSecret)
    }

    @Test
    fun rejectsWrongTokenAndNonAesSharedSecretLengths() = runTest {
        val context = MinecraftEncryption.createServerContext()
        val challenge = MinecraftEncryption.createServerChallenge(context)
        val otherChallenge = MinecraftEncryption.createServerChallenge(context)
        val answer = MinecraftEncryption.answerServerChallenge(otherChallenge.request)

        assertFailsWith<MinecraftAuthenticationException> {
            MinecraftEncryption.acceptClientResponse(challenge, answer.response)
        }

        val shortSecretResponse = EncryptionResponsePacket(
            sharedSecret = ByteString(
                PlatformMinecraftRsaBackend.rsaEncrypt(
                    context.encodedPublicKey,
                    ByteArray(15),
                ),
            ),
            verifyToken = ByteString(
                PlatformMinecraftRsaBackend.rsaEncrypt(
                    context.encodedPublicKey,
                    challenge.verifyToken(),
                ),
            ),
        )
        assertFailsWith<MinecraftAuthenticationException> {
            MinecraftEncryption.acceptClientResponse(
                challenge,
                shortSecretResponse,
            )
        }
    }

    @Test
    fun serverHashRejectsTextOutsideTheVanillaEncoding() {
        assertFailsWith<IllegalArgumentException> {
            minecraftServerHash("\u0100", byteArrayOf(), byteArrayOf())
        }
    }

    @Test
    fun interoperatesWithAnIndependentOpenSslPkcs1Fixture() {
        val publicKey = Base64.Default.decode(FIXTURE_PUBLIC_KEY)
        val privateKey = PlatformMinecraftRsaBackend.decodePrivateKey(
            Base64.Default.decode(FIXTURE_PRIVATE_KEY),
        )
        val ciphertext = Base64.Default.decode(FIXTURE_CIPHERTEXT)
        val plaintext = byteArrayOf(0, 0x80.toByte(), 0xFF.toByte()) +
                "Minecraft".encodeToByteArray()

        assertContentEquals(
            plaintext,
            PlatformMinecraftRsaBackend.rsaDecrypt(privateKey, ciphertext),
        )
        val providerCiphertext = PlatformMinecraftRsaBackend.rsaEncrypt(
            publicKey,
            plaintext,
        )
        assertEquals(128, providerCiphertext.size)
        assertContentEquals(
            plaintext,
            PlatformMinecraftRsaBackend.rsaDecrypt(
                privateKey,
                providerCiphertext,
            ),
        )
    }

    @Test
    fun enforcesTheRsa1024Pkcs1PlaintextBoundary() {
        val publicKey = Base64.Default.decode(FIXTURE_PUBLIC_KEY)

        assertEquals(
            128,
            PlatformMinecraftRsaBackend.rsaEncrypt(
                publicKey,
                ByteArray(117),
            ).size,
        )
        assertFails {
            PlatformMinecraftRsaBackend.rsaEncrypt(
                publicKey,
                ByteArray(118),
            )
        }
    }

    @Test
    fun normalizesMalformedKeyAndRejectsMalformedCiphertext() = runTest {
        val malformedKey = assertFailsWith<MinecraftCryptographyException> {
            MinecraftEncryption.answerServerChallenge(
                EncryptionRequestPacket(
                    serverId = "",
                    publicKey = ByteString(byteArrayOf(0x30, 0)),
                    verifyToken = ByteString(byteArrayOf(1, 2, 3, 4)),
                    shouldAuthenticate = true,
                ),
            )
        }
        assertNotNull(malformedKey.cause)

        val context = MinecraftEncryption.createServerContext()
        val challenge = MinecraftEncryption.createServerChallenge(context)
        assertFailsWith<MinecraftAuthenticationException> {
            MinecraftEncryption.acceptClientResponse(
                challenge,
                EncryptionResponsePacket(
                    sharedSecret = ByteString(ByteArray(1)),
                    verifyToken = ByteString(ByteArray(1)),
                ),
            )
        }
    }

    private companion object {
        const val FIXTURE_PRIVATE_KEY =
            "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAOHwdp3eclQClZ4daiU1noAe72M3+8MSGdL3K9Db1f0n9C3UPS89CexrtolmGwpHux4pjf+QeZORzT+BMxuMSf6lRjut/CcvU1LKyUywWoJ23mRnwlHm6QPImE9u0p3sMi2VCe18Y178EvdoE/M1wDlyhJmowNtJ9bEJVcjdE1LRAgMBAAECgYEAgxaJO788hhGZzUszsrMRazSHoAFzSRLPeN9/xIZH+cGcoppphWbcwxcbqUxck/JaVn21rXmdkEkf1KCZjnou00DFEz4z+Sr6fcngZSkMJRxt8cOPCKq202Y05voFxTSLS8AR9F3stwzHrWLaOrgPFO8rDE7dFa62C+Oobzc376ECQQD4uh9vG3dD797EaX3b3qD4YfbXNxU1AkeiIBX27weVXUnOjteBM0HaeT2j5hHH1BaG6O8fJsMCtReCBQ9J2/eLAkEA6IvCmWw+moTXeervyHbUkEsajh1i+ma4gfoaBVlix2WqVk3rs4vAUbTpJY/enPwLqg9IHwCPqXgc1FxksWJKkwJATNGnPbic6EWgZscyEQM8chpHk4a2rQ2MND12qzJ+BBqw3fPuCUBceW5ypDk9ipstbfNpTxS4rBBkN0r6wtQGKwJAKPN7uHLkb2eXXoPt5/ptIl/ndEFejcQLF/CIJosAJycTIRGlwT+KBZl7OT8lr7V/BFqek78QjYJ2aTtADDDH+wJAO+TKcqVCYHIBM4AePSNCHKMll2xDVZ4P8eoEIXRdLj79lyWLPH/gCZcaZneXSo+9FN/7lSyZAbTuJmCtlyx93g=="
        const val FIXTURE_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDh8Had3nJUApWeHWolNZ6AHu9jN/vDEhnS9yvQ29X9J/Qt1D0vPQnsa7aJZhsKR7seKY3/kHmTkc0/gTMbjEn+pUY7rfwnL1NSyslMsFqCdt5kZ8JR5ukDyJhPbtKd7DItlQntfGNe/BL3aBPzNcA5coSZqMDbSfWxCVXI3RNS0QIDAQAB"
        const val FIXTURE_CIPHERTEXT =
            "tzKZNYj7bhSeh52K7fqS/cY0k6nRBJfAnRPp3Oss1l9HptHhoMkcbJxejrjySZ5RpIVmU0h6/OUMNClWCKYzqn9vOcpSy6+/lCxv98lKQZCRRIuFn4YsVbk+B9jaDEa+/AkT23G5shj57sl9mnVzT6g7K/xsiXzsSmwBK8XmAAE="
    }
}
