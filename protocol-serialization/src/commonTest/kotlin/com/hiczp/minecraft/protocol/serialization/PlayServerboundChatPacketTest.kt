package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.ChatMessagePacket
import com.hiczp.minecraft.protocol.model.packet.PlayerSessionPacket
import com.hiczp.minecraft.protocol.model.packet.SignedChatCommandPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlayServerboundChatPacketTest {
    @Test
    fun `chat message retains instant signature optional and fixed bitset`() {
        assertPacketBytes(
            ChatMessagePacket(
                message = "x",
                timestampEpochMillis = 1,
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
            ChatMessagePacket.serializer(),
            "0178" +
                    "0000000000000001" +
                    "0000000000000002" +
                    "00ac02010203ff",
        )
    }

    @Test
    fun `signed command uses bounded argument list and last-seen update`() {
        assertPacketBytes(
            SignedChatCommandPacket(
                command = "x",
                timestampEpochMillis = 1,
                salt = 2,
                arguments = SignedCommandArguments(emptyList()),
                lastSeenMessages = LastSeenMessagesUpdate(
                    offset = 0,
                    acknowledged = ByteString(ByteArray(3)),
                    checksum = 0,
                ),
            ),
            SignedChatCommandPacket.serializer(),
            "0178" +
                    "0000000000000001" +
                    "0000000000000002" +
                    "000000000000",
        )
    }

    @Test
    fun `session update uses UUID instant and bounded key arrays`() {
        assertPacketBytes(
            PlayerSessionPacket(
                ChatSessionData(
                    sessionId = Uuid(1, 2),
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
            PlayerSessionPacket.serializer(),
            "00000000000000010000000000000002" +
                    "0000000000000003" +
                    "01aa02bbcc",
        )
    }

    private fun <T> assertPacketBytes(
        packet: T,
        serializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexBytes()
        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(serializer, packet),
        )
        assertEquals(
            packet,
            MinecraftFormat.decodeFromByteArray(serializer, expected),
        )
    }
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
