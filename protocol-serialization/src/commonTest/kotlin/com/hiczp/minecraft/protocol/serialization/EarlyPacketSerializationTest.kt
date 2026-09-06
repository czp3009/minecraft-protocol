package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.ClientIntentionPacket
import com.hiczp.minecraft.protocol.model.packet.LegacyServerListPingPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundCustomClickActionPacket
import com.hiczp.minecraft.protocol.model.type.ClientIntent
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EarlyPacketSerializationTest {
    @Test
    fun `handshake intentions use explicit IDs and reject the reserved zero`() {
        val template = ClientIntentionPacket(0, "", 0, ClientIntent.STATUS)
        ClientIntent.entries.forEach { clientIntent ->
            val packet = template.copy(intention = clientIntent)
            val bytes = MinecraftPacketPayloadFormat.encodeToByteArray(packet)
            assertEquals(clientIntent.id.toByte(), bytes.last())
            assertEquals(packet, MinecraftPacketPayloadFormat.decodeFromByteArray<ClientIntentionPacket>(bytes))
        }
        assertFailsWith<SerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientIntentionPacket>(byteArrayOf(0, 0, 0, 0, 0))
        }
    }

    @Test
    fun `handshake matches the selected protocol golden payload`() {
        val clientIntentionPacket = ClientIntentionPacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            hostName = "localhost",
            port = 25_565,
            intention = ClientIntent.LOGIN,
        )
        val expected = "8806096c6f63616c686f737463dd02".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(clientIntentionPacket),
        )
        assertEquals(
            expected = clientIntentionPacket,
            actual = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientIntentionPacket>(expected),
        )
    }

    @Test
    fun `legacy ping payload is exactly one byte`() {
        val legacyServerListPingPacket = LegacyServerListPingPacket()
        assertContentEquals(
            byteArrayOf(1),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                legacyServerListPingPacket,
            ),
        )
        assertEquals(
            expected = legacyServerListPingPacket,
            actual = MinecraftPacketPayloadFormat.decodeFromByteArray<LegacyServerListPingPacket>(
                byteArrayOf(1),
            ),
        )
    }

    @Test
    fun `custom click action matches official length-prefixed optional tag codec`() {
        val absent = ServerboundCustomClickActionPacket(
            id = Identifier("test"),
            payload = null,
        )
        assertContentEquals(
            "0e6d696e6563726166743a746573740100".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                absent,
            ),
        )

        val present = absent.copy(payload = NbtString("ok"))
        val byteArray = MinecraftPacketPayloadFormat.encodeToByteArray(
            present,
        )
        assertEquals(
            present,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundCustomClickActionPacket>(
                byteArray,
            ),
        )
    }
}
