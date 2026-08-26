package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayClientboundUpdatePacketTest {
    @Test
    fun `transfer and attributes use current registry and modifier codecs`() {
        assertPacketBytes(
            PlayTransferPacket("x", 255),
            PlayTransferPacket.serializer(),
            "0178ff01",
        )
        val updateAttributesPacket = UpdateAttributesPacket(
            entityId = 1,
            attributes = listOf(
                AttributeSnapshot(
                    attributeTypeId = 300,
                    baseValue = 1.0,
                    modifiers = listOf(
                        AttributeModifier(
                            Identifier("minecraft:x"),
                            amount = -2.0,
                            operation = AttributeModifierOperation.ADD_MULTIPLIED_TOTAL,
                        ),
                    ),
                ),
            ),
        )
        assertPacketBytes(
            updateAttributesPacket,
            UpdateAttributesPacket.serializer(),
            "0101ac023ff0000000000000010b6d696e6563726166743a78c00000000000000002",
        )

        val invalidOperation = MinecraftProtocolFormat.decodeFromByteArray<UpdateAttributesPacket>(
            "0101010000000000000000010b6d696e6563726166743a7800000000000000008001".hexToByteArray(),
        )
        assertEquals(
            AttributeModifierOperation.ADD_VALUE,
            invalidOperation.attributes.single().modifiers.single().operation,
        )
    }

    @Test
    fun `attribute list keeps official maximum of 128`() {
        val attributes = List(129) {
            AttributeSnapshot(0, 0.0, emptyList())
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftProtocolFormat.encodeToByteArray(
                UpdateAttributesPacket(1, attributes),
            )
        }
    }

    @Test
    fun `mob effect preserves raw flag bits including unknown bits`() {
        assertPacketBytes(
            EntityEffectPacket(
                entityId = 1,
                effectTypeId = 2,
                amplifier = 3,
                durationTicks = -1,
                flags = MobEffectFlags(0xFF.toByte()),
            ),
            EntityEffectPacket.serializer(),
            "010203ffffffff0fff",
        )
    }

    @Test
    fun `play tag update is nested prefixed registry and tag data`() {
        val playUpdateTagsPacket = PlayUpdateTagsPacket(
            linkedMapOf(
                Identifier("minecraft:block") to listOf(
                    TagDefinition(
                        Identifier("minecraft:test"),
                        listOf(1, 300),
                    ),
                ),
            ),
        )
        assertPacketBytes(
            playUpdateTagsPacket,
            PlayUpdateTagsPacket.serializer(),
            "010f6d696e6563726166743a626c6f636b010e6d696e6563726166743a746573740201ac02",
        )
    }

    @Test
    fun `projectile report details and clear dialog use direct shapes`() {
        assertPacketBytes(
            ProjectilePowerPacket(300, 1.0),
            ProjectilePowerPacket.serializer(),
            "ac023ff0000000000000",
        )
        assertPacketBytes(
            PlayCustomReportDetailsPacket(
                listOf(ReportDetail("x", "y")),
            ),
            PlayCustomReportDetailsPacket.serializer(),
            "0101780179",
        )
        assertPacketBytes(
            ClearDialogPacket,
            ClearDialogPacket.serializer(),
            "",
        )
    }

    @Test
    fun `server links support built-in and component labels`() {
        val builtIn = PlayServerLinksPacket(
            listOf(
                ServerLink(
                    ServerLinkLabel.BuiltIn(BuiltInServerLinkLabel.BUG_REPORT),
                    "u",
                ),
            ),
        )
        assertPacketBytes(
            builtIn,
            PlayServerLinksPacket.serializer(),
            "0101000175",
        )

        val custom = PlayServerLinksPacket(
            listOf(
                ServerLink(
                    ServerLinkLabel.Custom(
                        TextComponent(NbtString("x")),
                    ),
                    "u",
                ),
            ),
        )
        assertPacketBytes(
            custom,
            PlayServerLinksPacket.serializer(),
            "0100080001780175",
        )

        val invalidBuiltIn = MinecraftProtocolFormat.decodeFromByteArray<PlayServerLinksPacket>(
            "01017f0175".hexToByteArray(),
        )
        assertEquals(
            builtIn,
            invalidBuiltIn,
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
            MinecraftProtocolFormat.encodeToByteArray(kSerializer, packet),
        )
        assertEquals(
            packet,
            MinecraftProtocolFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
