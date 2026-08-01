package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlayCommonPacketTest {
    @Test
    fun `container cookie cooldown and chat packets match official primitive codecs`() {
        assertContentEquals(
            "ac02".hexToByteArray(),
            MinecraftFormat.encodeToByteArray(
                ClientboundCloseContainerPacket.serializer(),
                ClientboundCloseContainerPacket(300),
            ),
        )
        assertContentEquals(
            "010002fffd".hexToByteArray(),
            MinecraftFormat.encodeToByteArray(
                SetContainerPropertyPacket.serializer(),
                SetContainerPropertyPacket(1, 2, -3),
            ),
        )
        assertContentEquals(
            "0e6d696e6563726166743a74657374".hexToByteArray(),
            MinecraftFormat.encodeToByteArray(
                PlayCookieRequestPacket.serializer(),
                PlayCookieRequestPacket(Identifier("minecraft:test")),
            ),
        )
        assertContentEquals(
            "0f6d696e6563726166743a67726f757014".hexToByteArray(),
            MinecraftFormat.encodeToByteArray(
                SetCooldownPacket.serializer(),
                SetCooldownPacket(Identifier("minecraft:group"), 20),
            ),
        )

        val suggestions = ChatSuggestionsPacket(
            ChatSuggestionsAction.SET,
            listOf("one", "two"),
        )
        val suggestionsBytes = "0202036f6e650374776f".hexToByteArray()
        assertContentEquals(
            suggestionsBytes,
            MinecraftFormat.encodeToByteArray(
                ChatSuggestionsPacket.serializer(),
                suggestions,
            ),
        )
        assertEquals(
            suggestions,
            MinecraftFormat.decodeFromByteArray(
                ChatSuggestionsPacket.serializer(),
                suggestionsBytes,
            ),
        )
    }

    @Test
    fun `custom payload dispatches vanilla brand and preserves unknown bytes`() {
        val brand = PlayClientboundPluginMessagePacket(
            CustomPayload.Brand("vanilla"),
        )
        val brandBytes = (
                "0f6d696e6563726166743a6272616e64" +
                        "0776616e696c6c61"
                ).hexToByteArray()
        assertContentEquals(
            brandBytes,
            MinecraftFormat.encodeToByteArray(
                PlayClientboundPluginMessagePacket.serializer(),
                brand,
            ),
        )
        assertEquals(
            brand,
            MinecraftFormat.decodeFromByteArray(
                PlayClientboundPluginMessagePacket.serializer(),
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
            MinecraftFormat.encodeToByteArray(
                PlayClientboundPluginMessagePacket.serializer(),
                unknown,
            ),
        )
        assertEquals(
            unknown,
            MinecraftFormat.decodeFromByteArray(
                PlayClientboundPluginMessagePacket.serializer(),
                unknownBytes,
            ),
        )

    }
}
