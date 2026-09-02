package com.hiczp.minecraft.protocol.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.*

class MinecraftLoginKeyExchangeContractTest {
    @Test
    fun cryptographyFailureMappingDoesNotWrapCancellation() {
        val cancellationException = CancellationException("cancel cryptography")

        val failure = assertFailsWith<CancellationException> {
            mapCryptographyFailure("synthetic cryptography failure") {
                throw cancellationException
            }
        }

        assertSame(cancellationException, failure)
    }

    @Test
    fun createsVanillaChallengeWithDefensiveContextValues() = runTest {
        val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
        val encodedPublicKey = minecraftServerKeyPair.encodedPublicKey
        val minecraftServerChallenge = minecraftServerKeyPair.createChallenge(
            shouldAuthenticate = false,
        )

        assertTrue(encodedPublicKey.size > 128)
        encodedPublicKey.fill(0)
        assertFalse(minecraftServerKeyPair.encodedPublicKey.all { it == 0.toByte() })
        assertEquals("", minecraftServerChallenge.serverId)
        assertFalse(minecraftServerChallenge.shouldAuthenticate)
        assertEquals(4, minecraftServerChallenge.verifyToken.size)
        assertContentEquals(
            minecraftServerKeyPair.encodedPublicKey,
            minecraftServerChallenge.encodedPublicKey,
        )

        val encryptionRequestPacket = minecraftServerChallenge.toEncryptionRequestPacket()
        assertEquals(minecraftServerChallenge.serverId, encryptionRequestPacket.serverId)
        assertContentEquals(minecraftServerChallenge.encodedPublicKey, encryptionRequestPacket.publicKey.toByteArray())
        assertContentEquals(minecraftServerChallenge.verifyToken, encryptionRequestPacket.verifyToken.toByteArray())
        assertEquals(minecraftServerChallenge.shouldAuthenticate, encryptionRequestPacket.shouldAuthenticate)
    }

    @Test
    fun answersAndAcceptsAPlatformEncryptedChallenge() = runTest {
        val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
        val minecraftServerChallenge = minecraftServerKeyPair.createChallenge()

        val minecraftClientKeyExchangeResult = MinecraftClientKeyExchange.respond(
            serverId = minecraftServerChallenge.serverId,
            encodedPublicKey = minecraftServerChallenge.encodedPublicKey,
            verifyToken = minecraftServerChallenge.verifyToken,
        )
        val accepted = minecraftServerChallenge.accept(
            encryptedSharedSecret = minecraftClientKeyExchangeResult.encryptedSharedSecret,
            encryptedVerifyToken = minecraftClientKeyExchangeResult.encryptedVerifyToken,
        )

        assertEquals(16, accepted.sharedSecret.size)
        assertContentEquals(minecraftClientKeyExchangeResult.sharedSecret, accepted.sharedSecret)
        assertEquals(minecraftClientKeyExchangeResult.minecraftServerHash, accepted.minecraftServerHash)
        val exported = minecraftClientKeyExchangeResult.sharedSecret.copyOf()
        val expectedSecret = exported.copyOf()
        exported.fill(0)
        assertContentEquals(expectedSecret, minecraftClientKeyExchangeResult.sharedSecret)

        val packetAnswer = MinecraftClientKeyExchange.respond(
            minecraftServerChallenge.toEncryptionRequestPacket(),
        )
        val packetAccepted = minecraftServerChallenge.accept(packetAnswer.toEncryptionResponsePacket())
        assertContentEquals(packetAnswer.sharedSecret, packetAccepted.sharedSecret)
    }

    @Test
    fun keyExchangeResultsUseByteArrayContentEquality() {
        val minecraftServerHash = MinecraftServerHash("server-hash")
        val minecraftClientKeyExchangeResult = MinecraftClientKeyExchangeResult(
            encryptedSharedSecret = byteArrayOf(1, 2),
            encryptedVerifyToken = byteArrayOf(3, 4),
            sharedSecret = byteArrayOf(5, 6),
            minecraftServerHash = minecraftServerHash,
        )
        val equalClientResult = minecraftClientKeyExchangeResult.copy(
            encryptedSharedSecret = minecraftClientKeyExchangeResult.encryptedSharedSecret.copyOf(),
            encryptedVerifyToken = minecraftClientKeyExchangeResult.encryptedVerifyToken.copyOf(),
            sharedSecret = minecraftClientKeyExchangeResult.sharedSecret.copyOf(),
        )
        assertEquals(minecraftClientKeyExchangeResult, equalClientResult)
        assertEquals(minecraftClientKeyExchangeResult.hashCode(), equalClientResult.hashCode())
        assertNotEquals(
            minecraftClientKeyExchangeResult,
            equalClientResult.copy(sharedSecret = byteArrayOf(5, 7)),
        )

        val minecraftServerKeyExchangeResult = MinecraftServerKeyExchangeResult(
            sharedSecret = byteArrayOf(7, 8),
            minecraftServerHash = minecraftServerHash,
        )
        val equalServerResult = minecraftServerKeyExchangeResult.copy(
            sharedSecret = minecraftServerKeyExchangeResult.sharedSecret.copyOf(),
        )
        assertEquals(minecraftServerKeyExchangeResult, equalServerResult)
        assertEquals(minecraftServerKeyExchangeResult.hashCode(), equalServerResult.hashCode())
        assertNotEquals(
            minecraftServerKeyExchangeResult,
            equalServerResult.copy(sharedSecret = byteArrayOf(7, 9)),
        )
    }

    @Test
    fun importsAnEncodedServerKeyPairThroughThePublicConstructor() {
        val minecraftServerKeyPair = MinecraftServerKeyPair(
            encodedPublicKey = Base64.Default.decode(FIXTURE_PUBLIC_KEY),
            encodedPrivateKey = Base64.Default.decode(FIXTURE_PRIVATE_KEY),
        )
        val minecraftServerChallenge = minecraftServerKeyPair.createChallenge()
        val minecraftClientKeyExchangeResult = MinecraftClientKeyExchange.respond(
            serverId = minecraftServerChallenge.serverId,
            encodedPublicKey = minecraftServerChallenge.encodedPublicKey,
            verifyToken = minecraftServerChallenge.verifyToken,
        )

        assertContentEquals(
            minecraftClientKeyExchangeResult.sharedSecret,
            minecraftServerChallenge.accept(
                encryptedSharedSecret = minecraftClientKeyExchangeResult.encryptedSharedSecret,
                encryptedVerifyToken = minecraftClientKeyExchangeResult.encryptedVerifyToken,
            ).sharedSecret,
        )
    }

    @Test
    fun rejectsWrongTokenAndNonAesSharedSecretLengths() = runTest {
        val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
        val minecraftServerChallenge = minecraftServerKeyPair.createChallenge()
        val otherChallenge = minecraftServerKeyPair.createChallenge()
        val minecraftClientKeyExchangeResult = MinecraftClientKeyExchange.respond(
            serverId = otherChallenge.serverId,
            encodedPublicKey = otherChallenge.encodedPublicKey,
            verifyToken = otherChallenge.verifyToken,
        )

        assertFailsWith<MinecraftKeyExchangeException> {
            minecraftServerChallenge.accept(
                encryptedSharedSecret = minecraftClientKeyExchangeResult.encryptedSharedSecret,
                encryptedVerifyToken = minecraftClientKeyExchangeResult.encryptedVerifyToken,
            )
        }

        val encryptedShortSecret = PlatformMinecraftRsaBackend.rsaEncrypt(
            minecraftServerKeyPair.encodedPublicKey,
            ByteArray(15),
        )
        val encryptedVerifyToken = PlatformMinecraftRsaBackend.rsaEncrypt(
            minecraftServerKeyPair.encodedPublicKey,
            minecraftServerChallenge.verifyToken,
        )
        assertFailsWith<MinecraftKeyExchangeException> {
            minecraftServerChallenge.accept(encryptedShortSecret, encryptedVerifyToken)
        }
    }

    @Test
    fun serverHashRejectsTextOutsideTheVanillaEncoding() {
        assertFailsWith<IllegalArgumentException> {
            MinecraftServerHash.compute(
                serverId = "\u0100",
                sharedSecret = ByteArray(16),
                encodedPublicKey = ByteArray(0),
            )
        }
    }

    @Test
    fun serverHashMatchesAnIndependentJavaSha1BigIntegerVector() {
        val minecraftServerHash = MinecraftServerHash.compute(
            serverId = "",
            sharedSecret = ByteArray(16) { it.toByte() },
            encodedPublicKey = byteArrayOf(0x30, 0x01, 0x00),
        )

        assertEquals("-76855881f71fd8cdb670af2928d9b62ef1043d49", minecraftServerHash.value)
    }

    @Test
    fun challengeRetainsTheCallerSuppliedServerId() = runTest {
        val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
        assertEquals(
            "x".repeat(21),
            minecraftServerKeyPair.createChallenge(serverId = "x".repeat(21)).serverId,
        )
    }

    @Test
    fun interoperatesWithAnIndependentOpenSslPkcs1Fixture() {
        val publicKey = Base64.Default.decode(FIXTURE_PUBLIC_KEY)
        val minecraftRsaPrivateKey = PlatformMinecraftRsaBackend.decodePrivateKey(
            Base64.Default.decode(FIXTURE_PRIVATE_KEY),
        )
        val ciphertext = Base64.Default.decode(FIXTURE_CIPHERTEXT)
        val plaintext = byteArrayOf(0, 0x80.toByte(), 0xFF.toByte()) +
                "Minecraft".encodeToByteArray()

        assertContentEquals(
            plaintext,
            PlatformMinecraftRsaBackend.rsaDecrypt(minecraftRsaPrivateKey, ciphertext),
        )
        val providerCiphertext = PlatformMinecraftRsaBackend.rsaEncrypt(
            publicKey,
            plaintext,
        )
        assertEquals(128, providerCiphertext.size)
        assertContentEquals(
            plaintext,
            PlatformMinecraftRsaBackend.rsaDecrypt(
                minecraftRsaPrivateKey,
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
            MinecraftClientKeyExchange.respond(
                serverId = "",
                encodedPublicKey = byteArrayOf(0x30, 0),
                verifyToken = byteArrayOf(1, 2, 3, 4),
            )
        }
        assertNotNull(malformedKey.cause)

        val minecraftServerKeyPair = MinecraftServerKeyPair.generate()
        val minecraftServerChallenge = minecraftServerKeyPair.createChallenge()
        assertFailsWith<MinecraftKeyExchangeException> {
            minecraftServerChallenge.accept(ByteArray(1), ByteArray(1))
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
