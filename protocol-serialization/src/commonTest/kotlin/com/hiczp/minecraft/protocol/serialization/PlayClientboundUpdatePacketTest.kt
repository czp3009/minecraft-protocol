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
            ClientboundTransferPacket("x", 255),
            ClientboundTransferPacket.serializer(),
            "0178ff01",
        )
        val clientboundUpdateAttributesPacket = ClientboundUpdateAttributesPacket(
            entityId = 1,
            attributes = listOf(
                ClientboundUpdateAttributesPacket.AttributeSnapshot(
                    attribute = 300,
                    base = 1.0,
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
            clientboundUpdateAttributesPacket,
            ClientboundUpdateAttributesPacket.serializer(),
            "0101ac023ff0000000000000010b6d696e6563726166743a78c00000000000000002",
        )

        val invalidOperation = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundUpdateAttributesPacket>(
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
            ClientboundUpdateAttributesPacket.AttributeSnapshot(0, 0.0, emptyList())
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundUpdateAttributesPacket(1, attributes),
            )
        }
    }

    @Test
    fun `mob effect preserves raw flag bits including unknown bits`() {
        assertPacketBytes(
            ClientboundUpdateMobEffectPacket(
                entityId = 1,
                effect = 2,
                effectAmplifier = 3,
                effectDurationTicks = -1,
                flags = MobEffectFlags(0xFF.toByte()),
            ),
            ClientboundUpdateMobEffectPacket.serializer(),
            "010203ffffffff0fff",
        )
    }

    @Test
    fun `play tag update is nested prefixed registry and tag data`() {
        val clientboundUpdateTagsPacket = ClientboundUpdateTagsPacket(
            listOf(
                RegistryTags(
                    Identifier("minecraft:block"),
                    listOf(
                    TagDefinition(
                        Identifier("minecraft:test"),
                        listOf(1, 300),
                    ),
                ),
                )
            ),
        )
        assertPacketBytes(
            clientboundUpdateTagsPacket,
            ClientboundUpdateTagsPacket.serializer(),
            "010f6d696e6563726166743a626c6f636b010e6d696e6563726166743a746573740201ac02",
        )
    }

    @Test
    fun `projectile report details and clear dialog use direct shapes`() {
        assertPacketBytes(
            ClientboundProjectilePowerPacket(300, 1.0),
            ClientboundProjectilePowerPacket.serializer(),
            "ac023ff0000000000000",
        )
        assertPacketBytes(
            ClientboundCustomReportDetailsPacket(
                listOf(ReportDetail("x", "y")),
            ),
            ClientboundCustomReportDetailsPacket.serializer(),
            "0101780179",
        )
        assertPacketBytes(
            ClientboundClearDialogPacket,
            ClientboundClearDialogPacket.serializer(),
            "",
        )
    }

    @Test
    fun `server links support built-in and component labels`() {
        val builtIn = ClientboundServerLinksPacket(
            listOf(
                ServerLink(
                    ServerLinkLabel.BuiltIn(BuiltInServerLinkLabel.BUG_REPORT),
                    "u",
                ),
            ),
        )
        assertPacketBytes(
            builtIn,
            ClientboundServerLinksPacket.serializer(),
            "0101000175",
        )

        val custom = ClientboundServerLinksPacket(
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
            ClientboundServerLinksPacket.serializer(),
            "0100080001780175",
        )

        val invalidBuiltIn = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundServerLinksPacket>(
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
            MinecraftPacketPayloadFormat.encodeToByteArray(kSerializer, packet),
        )
        assertEquals(
            packet,
            MinecraftPacketPayloadFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
