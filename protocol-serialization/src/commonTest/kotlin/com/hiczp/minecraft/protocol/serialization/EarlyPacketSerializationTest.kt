package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.ConfigurationCustomClickActionPacket
import com.hiczp.minecraft.protocol.model.packet.HandshakeNextState
import com.hiczp.minecraft.protocol.model.packet.HandshakePacket
import com.hiczp.minecraft.protocol.model.packet.LegacyServerListPingPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EarlyPacketSerializationTest {
    @Test
    fun `handshake matches the selected protocol golden payload`() {
        val packet = HandshakePacket(
            protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
            serverAddress = "localhost",
            serverPort = 25_565,
            nextState = HandshakeNextState.LOGIN,
        )
        val expected = "8806096c6f63616c686f737463dd02".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(HandshakePacket.serializer(), packet),
        )
        assertEquals(
            expected = packet,
            actual = MinecraftProtocolFormat.decodeFromByteArray(HandshakePacket.serializer(), expected),
        )
    }

    @Test
    fun `legacy ping payload is exactly one byte`() {
        val packet = LegacyServerListPingPacket()
        assertContentEquals(
            byteArrayOf(1),
            MinecraftProtocolFormat.encodeToByteArray(
                LegacyServerListPingPacket.serializer(),
                packet,
            ),
        )
        assertEquals(
            expected = packet,
            actual = MinecraftProtocolFormat.decodeFromByteArray(
                LegacyServerListPingPacket.serializer(),
                byteArrayOf(1),
            ),
        )
    }

    @Test
    fun `custom click action matches official length-prefixed optional tag codec`() {
        val absent = ConfigurationCustomClickActionPacket(
            id = Identifier("test"),
            payload = null,
        )
        assertContentEquals(
            "0e6d696e6563726166743a746573740100".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                ConfigurationCustomClickActionPacket.serializer(),
                absent,
            ),
        )

        val present = absent.copy(payload = NbtString("ok"))
        val bytes = MinecraftProtocolFormat.encodeToByteArray(
            ConfigurationCustomClickActionPacket.serializer(),
            present,
        )
        assertEquals(
            present,
            MinecraftProtocolFormat.decodeFromByteArray(
                ConfigurationCustomClickActionPacket.serializer(),
                bytes,
            ),
        )
    }
}
