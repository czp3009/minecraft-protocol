package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.EncryptionResponsePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmMinecraftCryptographyTest {
    @Test
    fun completesTheVanillaRsaChallenge() {
        val challenge = MinecraftEncryption.createServerChallenge(
            JvmMinecraftCryptography,
        )
        val client = MinecraftEncryption.answerServerChallenge(
            challenge.request,
            JvmMinecraftCryptography,
        )
        val serverSecret = MinecraftEncryption.acceptClientResponse(
            challenge,
            client.response,
            JvmMinecraftCryptography,
        )

        assertContentEquals(client.sharedSecret, serverSecret)
        assertTrue(
            minecraftServerHash(
                challenge.request.serverId,
                serverSecret,
                challenge.request.publicKey.toByteArray(),
            ).isNotEmpty(),
        )
    }

    @Test
    fun rejectsAResponseWithTheWrongVerifyToken() {
        val challenge = MinecraftEncryption.createServerChallenge(
            JvmMinecraftCryptography,
        )
        val client = MinecraftEncryption.answerServerChallenge(
            challenge.request,
            JvmMinecraftCryptography,
        )
        val wrongToken = JvmMinecraftCryptography.rsaEncrypt(
            challenge.request.publicKey.toByteArray(),
            byteArrayOf(0, 0, 0, 0),
        )

        assertFailsWith<MinecraftAuthenticationException> {
            MinecraftEncryption.acceptClientResponse(
                challenge,
                EncryptionResponsePacket(
                    sharedSecret = client.response.sharedSecret,
                    verifyToken = ByteString(wrongToken),
                ),
                JvmMinecraftCryptography,
            )
        }
    }

    @Test
    fun validatesJvmKeyGenerationRandomAndPrivateKeyOwnership() {
        assertFailsWith<IllegalArgumentException> {
            JvmMinecraftCryptography.secureRandomBytes(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            JvmMinecraftCryptography.generateRsaKeyPair(512)
        }
        assertFailsWith<IllegalArgumentException> {
            JvmMinecraftCryptography.rsaDecrypt(
                object : MinecraftRsaPrivateKey {},
                byteArrayOf(),
            )
        }
    }
}
