package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlayCommonPacketTest {
    @Test
    fun `container cookie cooldown and chat packets match official primitive codecs`() {
        assertContentEquals(
            "ac02".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundContainerClosePacket(300),
            ),
        )
        assertContentEquals(
            "010002fffd".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundContainerSetDataPacket(1, 2, -3),
            ),
        )
        assertContentEquals(
            "0e6d696e6563726166743a74657374".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundCookieRequestPacket(Identifier("minecraft:test")),
            ),
        )
        assertContentEquals(
            "0f6d696e6563726166743a67726f757014".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundCooldownPacket(Identifier("minecraft:group"), 20),
            ),
        )

        val clientboundCustomChatCompletionsPacket = ClientboundCustomChatCompletionsPacket(
            ClientboundCustomChatCompletionsPacket.Action.SET,
            listOf("one", "two"),
        )
        val suggestionsBytes = "0202036f6e650374776f".hexToByteArray()
        assertContentEquals(
            suggestionsBytes,
            MinecraftPacketPayloadFormat.encodeToByteArray(
                clientboundCustomChatCompletionsPacket,
            ),
        )
        assertEquals(
            clientboundCustomChatCompletionsPacket,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundCustomChatCompletionsPacket>(
                suggestionsBytes,
            ),
        )
    }

    @Test
    fun `custom payload dispatches vanilla brand and preserves unknown bytes`() {
        val brand = ClientboundCustomPayloadPacket(
            CustomPayload.Brand("vanilla"),
        )
        val brandBytes = "0f6d696e6563726166743a6272616e640776616e696c6c61".hexToByteArray()
        assertContentEquals(
            brandBytes,
            MinecraftPacketPayloadFormat.encodeToByteArray(
                brand,
            ),
        )
        assertEquals(
            brand,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundCustomPayloadPacket>(
                brandBytes,
            ),
        )

        val unknown = ClientboundCustomPayloadPacket(
            CustomPayload.Unknown(
                Identifier("example:raw"),
                ByteString(byteArrayOf(1, 2, 3)),
            ),
        )
        val unknownBytes = "0b6578616d706c653a726177010203".hexToByteArray()
        assertContentEquals(
            unknownBytes,
            MinecraftPacketPayloadFormat.encodeToByteArray(
                unknown,
            ),
        )
        assertEquals(
            unknown,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundCustomPayloadPacket>(
                unknownBytes,
            ),
        )

        assertContentEquals(
            "0f6d696e6563726166743a6272616e6403616263".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundCustomPayloadPacket(
                    CustomPayload.Unknown(
                        Identifier("minecraft:brand"),
                        ByteString(byteArrayOf(0x03, 0x61, 0x62, 0x63)),
                    ),
                ),
            ),
        )
    }
}
