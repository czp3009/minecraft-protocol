package com.hiczp.minecraft.protocol.auth

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.*

class MinecraftLoginKeyExchangeContractTest {
    @Test
    fun createsVanillaChallengeWithDefensiveContextValues() = runTest {
        val keyPair = MinecraftServerKeyPair.generate()
        val encodedPublicKey = keyPair.encodedPublicKey
        val challenge = keyPair.createChallenge(
            shouldAuthenticate = false,
        )

        assertTrue(encodedPublicKey.size > 128)
        encodedPublicKey.fill(0)
        assertFalse(keyPair.encodedPublicKey.all { it == 0.toByte() })
        assertEquals("", challenge.serverId)
        assertFalse(challenge.shouldAuthenticate)
        assertEquals(4, challenge.verifyToken.size)
        assertContentEquals(
            keyPair.encodedPublicKey,
            challenge.encodedPublicKey,
        )

        val request = challenge.toEncryptionRequestPacket()
        assertEquals(challenge.serverId, request.serverId)
        assertContentEquals(challenge.encodedPublicKey, request.publicKey.toByteArray())
        assertContentEquals(challenge.verifyToken, request.verifyToken.toByteArray())
        assertEquals(challenge.shouldAuthenticate, request.shouldAuthenticate)
    }

    @Test
    fun answersAndAcceptsAPlatformEncryptedChallenge() = runTest {
        val keyPair = MinecraftServerKeyPair.generate()
        val challenge = keyPair.createChallenge()

        val answer = MinecraftClientKeyExchange.respond(
            serverId = challenge.serverId,
            encodedPublicKey = challenge.encodedPublicKey,
            verifyToken = challenge.verifyToken,
        )
        val accepted = challenge.accept(
            encryptedSharedSecret = answer.encryptedSharedSecret,
            encryptedVerifyToken = answer.encryptedVerifyToken,
        )

        assertEquals(16, accepted.sharedSecret.size)
        assertContentEquals(answer.sharedSecret, accepted.sharedSecret)
        assertEquals(answer.serverHash, accepted.serverHash)
        val exported = answer.sharedSecret.copyOf()
        val expectedSecret = exported.copyOf()
        exported.fill(0)
        assertContentEquals(expectedSecret, answer.sharedSecret)

        val packetAnswer = MinecraftClientKeyExchange.respond(
            challenge.toEncryptionRequestPacket(),
        )
        val packetAccepted = challenge.accept(packetAnswer.toEncryptionResponsePacket())
        assertContentEquals(packetAnswer.sharedSecret, packetAccepted.sharedSecret)
    }

    @Test
    fun keyExchangeResultsUseByteArrayContentEquality() {
        val hash = MinecraftServerHash("server-hash")
        val clientResult = MinecraftClientKeyExchangeResult(
            encryptedSharedSecret = byteArrayOf(1, 2),
            encryptedVerifyToken = byteArrayOf(3, 4),
            sharedSecret = byteArrayOf(5, 6),
            serverHash = hash,
        )
        val equalClientResult = clientResult.copy(
            encryptedSharedSecret = clientResult.encryptedSharedSecret.copyOf(),
            encryptedVerifyToken = clientResult.encryptedVerifyToken.copyOf(),
            sharedSecret = clientResult.sharedSecret.copyOf(),
        )
        assertEquals(clientResult, equalClientResult)
        assertEquals(clientResult.hashCode(), equalClientResult.hashCode())
        assertNotEquals(
            clientResult,
            equalClientResult.copy(sharedSecret = byteArrayOf(5, 7)),
        )

        val serverResult = MinecraftServerKeyExchangeResult(
            sharedSecret = byteArrayOf(7, 8),
            serverHash = hash,
        )
        val equalServerResult = serverResult.copy(
            sharedSecret = serverResult.sharedSecret.copyOf(),
        )
        assertEquals(serverResult, equalServerResult)
        assertEquals(serverResult.hashCode(), equalServerResult.hashCode())
        assertNotEquals(
            serverResult,
            equalServerResult.copy(sharedSecret = byteArrayOf(7, 9)),
        )
    }

    @Test
    fun importsAnEncodedServerKeyPairThroughThePublicConstructor() {
        val keyPair = MinecraftServerKeyPair(
            encodedPublicKey = Base64.Default.decode(FIXTURE_PUBLIC_KEY),
            encodedPrivateKey = Base64.Default.decode(FIXTURE_PRIVATE_KEY),
        )
        val challenge = keyPair.createChallenge()
        val answer = MinecraftClientKeyExchange.respond(
            serverId = challenge.serverId,
            encodedPublicKey = challenge.encodedPublicKey,
            verifyToken = challenge.verifyToken,
        )

        assertContentEquals(
            answer.sharedSecret,
            challenge.accept(
                encryptedSharedSecret = answer.encryptedSharedSecret,
                encryptedVerifyToken = answer.encryptedVerifyToken,
            ).sharedSecret,
        )
    }

    @Test
    fun rejectsWrongTokenAndNonAesSharedSecretLengths() = runTest {
        val keyPair = MinecraftServerKeyPair.generate()
        val challenge = keyPair.createChallenge()
        val otherChallenge = keyPair.createChallenge()
        val answer = MinecraftClientKeyExchange.respond(
            serverId = otherChallenge.serverId,
            encodedPublicKey = otherChallenge.encodedPublicKey,
            verifyToken = otherChallenge.verifyToken,
        )

        assertFailsWith<MinecraftKeyExchangeException> {
            challenge.accept(
                encryptedSharedSecret = answer.encryptedSharedSecret,
                encryptedVerifyToken = answer.encryptedVerifyToken,
            )
        }

        val encryptedShortSecret = PlatformMinecraftRsaBackend.rsaEncrypt(
            keyPair.encodedPublicKey,
            ByteArray(15),
        )
        val encryptedVerifyToken = PlatformMinecraftRsaBackend.rsaEncrypt(
            keyPair.encodedPublicKey,
            challenge.verifyToken,
        )
        assertFailsWith<MinecraftKeyExchangeException> {
            challenge.accept(encryptedShortSecret, encryptedVerifyToken)
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
        val hash = MinecraftServerHash.compute(
            serverId = "",
            sharedSecret = ByteArray(16) { it.toByte() },
            encodedPublicKey = byteArrayOf(0x30, 0x01, 0x00),
        )

        assertEquals("-76855881f71fd8cdb670af2928d9b62ef1043d49", hash.value)
    }

    @Test
    fun validatesPublicKeyExchangeInputBoundaries() = runTest {
        val keyPair = MinecraftServerKeyPair.generate()
        assertFailsWith<IllegalArgumentException> {
            keyPair.createChallenge(serverId = "x".repeat(21))
        }
        assertEquals(
            "x".repeat(20),
            keyPair.createChallenge(serverId = "x".repeat(20)).serverId,
        )
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
            MinecraftClientKeyExchange.respond(
                serverId = "",
                encodedPublicKey = byteArrayOf(0x30, 0),
                verifyToken = byteArrayOf(1, 2, 3, 4),
            )
        }
        assertNotNull(malformedKey.cause)

        val keyPair = MinecraftServerKeyPair.generate()
        val challenge = keyPair.createChallenge()
        assertFailsWith<MinecraftKeyExchangeException> {
            challenge.accept(ByteArray(1), ByteArray(1))
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
