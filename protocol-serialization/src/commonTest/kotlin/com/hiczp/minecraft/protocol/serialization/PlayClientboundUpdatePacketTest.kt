package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
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
        val packet = UpdateAttributesPacket(
            entityId = 1,
            attributes = listOf(
                AttributeSnapshot(
                    attributeTypeId = 300,
                    baseValue = 1.0,
                    modifiers = listOf(
                        AttributeModifier(
                            Identifier("minecraft:x"),
                            amount = -2.0,
                            operation =
                                AttributeModifierOperation.ADD_MULTIPLIED_TOTAL,
                        ),
                    ),
                ),
            ),
        )
        assertPacketBytes(
            packet,
            UpdateAttributesPacket.serializer(),
            (
                    "0101" +
                            "ac02" +
                            "3ff0000000000000" +
                            "01" +
                            "0b6d696e6563726166743a78" +
                            "c000000000000000" +
                            "02"
                    ),
        )

        val invalidOperation = MinecraftFormat.decodeFromByteArray(
            UpdateAttributesPacket.serializer(),
            (
                    "0101" +
                            "01" +
                            "0000000000000000" +
                            "01" +
                            "0b6d696e6563726166743a78" +
                            "0000000000000000" +
                            "8001"
                    ).hexToByteArray(),
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
            MinecraftFormat.encodeToByteArray(
                UpdateAttributesPacket.serializer(),
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
        val packet = PlayUpdateTagsPacket(
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
            packet,
            PlayUpdateTagsPacket.serializer(),
            (
                    "01" +
                            "0f6d696e6563726166743a626c6f636b" +
                            "01" +
                            "0e6d696e6563726166743a74657374" +
                            "0201ac02"
                    ),
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

        val invalidBuiltIn = MinecraftFormat.decodeFromByteArray(
            PlayServerLinksPacket.serializer(),
            "01017f0175".hexToByteArray(),
        )
        assertEquals(
            builtIn,
            invalidBuiltIn,
        )
    }

    private fun <T> assertPacketBytes(
        packet: T,
        serializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
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
