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
            MinecraftProtocolFormat.encodeToByteArray(
                ClientboundCloseContainerPacket(300),
            ),
        )
        assertContentEquals(
            "010002fffd".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                SetContainerPropertyPacket(1, 2, -3),
            ),
        )
        assertContentEquals(
            "0e6d696e6563726166743a74657374".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                PlayCookieRequestPacket(Identifier("minecraft:test")),
            ),
        )
        assertContentEquals(
            "0f6d696e6563726166743a67726f757014".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(
                SetCooldownPacket(Identifier("minecraft:group"), 20),
            ),
        )

        val chatSuggestionsPacket = ChatSuggestionsPacket(
            ChatSuggestionsAction.SET,
            listOf("one", "two"),
        )
        val suggestionsBytes = "0202036f6e650374776f".hexToByteArray()
        assertContentEquals(
            suggestionsBytes,
            MinecraftProtocolFormat.encodeToByteArray(
                chatSuggestionsPacket,
            ),
        )
        assertEquals(
            chatSuggestionsPacket,
            MinecraftProtocolFormat.decodeFromByteArray<ChatSuggestionsPacket>(
                suggestionsBytes,
            ),
        )
    }

    @Test
    fun `custom payload dispatches vanilla brand and preserves unknown bytes`() {
        val brand = PlayClientboundPluginMessagePacket(
            CustomPayload.Brand("vanilla"),
        )
        val brandBytes = "0f6d696e6563726166743a6272616e640776616e696c6c61".hexToByteArray()
        assertContentEquals(
            brandBytes,
            MinecraftProtocolFormat.encodeToByteArray(
                brand,
            ),
        )
        assertEquals(
            brand,
            MinecraftProtocolFormat.decodeFromByteArray<PlayClientboundPluginMessagePacket>(
                brandBytes,
            ),
        )

        val unknown = PlayClientboundPluginMessagePacket(
            CustomPayload.Unknown(
                Identifier("example:raw"),
                ByteString(byteArrayOf(1, 2, 3)),
            ),
        )
        val unknownBytes = "0b6578616d706c653a726177010203".hexToByteArray()
        assertContentEquals(
            unknownBytes,
            MinecraftProtocolFormat.encodeToByteArray(
                unknown,
            ),
        )
        assertEquals(
            unknown,
            MinecraftProtocolFormat.decodeFromByteArray<PlayClientboundPluginMessagePacket>(
                unknownBytes,
            ),
        )

    }
}
