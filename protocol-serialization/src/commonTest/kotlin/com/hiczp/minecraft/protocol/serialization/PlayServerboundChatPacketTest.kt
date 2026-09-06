package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.ServerboundChatCommandSignedPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundChatPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundChatSessionUpdatePacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer

class PlayServerboundChatPacketTest {
    @Test
    fun `last-seen acknowledgement width belongs to the physical codec`() {
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                LastSeenMessagesUpdate.serializer(),
                LastSeenMessagesUpdate(0, ByteString(ByteArray(2)), 0),
            )
        }
    }

    @Test
    fun `chat message retains instant signature optional and fixed bitset`() {
        assertPacketBytes(
            ServerboundChatPacket(
                message = "x",
                timeStamp = 1,
                salt = 2,
                signature = null,
                lastSeenMessages = LastSeenMessagesUpdate(
                    offset = 300,
                    acknowledged = ByteString(
                        byteArrayOf(1, 2, 3),
                    ),
                    checksum = -1,
                ),
            ),
            ServerboundChatPacket.serializer(),
            "01780000000000000001000000000000000200ac02010203ff",
        )
    }

    @Test
    fun `signed command uses bounded argument list and last-seen update`() {
        assertPacketBytes(
            ServerboundChatCommandSignedPacket(
                command = "x",
                timeStamp = 1,
                salt = 2,
                argumentSignatures = SignedCommandArguments(emptyList()),
                lastSeenMessages = LastSeenMessagesUpdate(
                    offset = 0,
                    acknowledged = ByteString(ByteArray(3)),
                    checksum = 0,
                ),
            ),
            ServerboundChatCommandSignedPacket.serializer(),
            "017800000000000000010000000000000002000000000000",
        )
    }

    @Test
    fun `session update uses UUID instant and bounded key arrays`() {
        assertPacketBytes(
            ServerboundChatSessionUpdatePacket(
                ChatSessionData(
                    sessionId = Uuid.fromLongs(1, 2),
                    profilePublicKey = ProfilePublicKeyData(
                        expiresAtEpochMillis = 3,
                        encodedKey = ByteString(
                            byteArrayOf(0xAA.toByte()),
                        ),
                        keySignature = ByteString(
                            byteArrayOf(0xBB.toByte(), 0xCC.toByte()),
                        ),
                    ),
                ),
            ),
            ServerboundChatSessionUpdatePacket.serializer(),
            "00000000000000010000000000000002000000000000000301aa02bbcc",
        )
    }

    private fun <T> assertPacketBytes(
        packet: T,
        kSerializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(kSerializer, packet),
        )
        assertEquals(
            packet,
            MinecraftPacketPayloadFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
