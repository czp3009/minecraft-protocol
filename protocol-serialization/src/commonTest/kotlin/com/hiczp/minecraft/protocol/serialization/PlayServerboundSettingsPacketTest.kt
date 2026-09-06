package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.RecipeBookCategory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.uuid.Uuid

class PlayServerboundSettingsPacketTest {
    @Test
    fun `recipe book settings and seen recipe use VarInt enum and display id`() {
        assertPacketBytes(
            ServerboundRecipeBookChangeSettingsPacket(
                bookType = RecipeBookCategory.SMOKER,
                isOpen = true,
                isFiltering = false,
            ),
            ServerboundRecipeBookChangeSettingsPacket.serializer(),
            "030100",
        )
        assertPacketBytes(
            ServerboundRecipeBookSeenRecipePacket(300),
            ServerboundRecipeBookSeenRecipePacket.serializer(),
            "ac02",
        )
    }

    @Test
    fun `rename item enforces the codec string bound rather than server gameplay policy`() {
        assertPacketBytes(
            ServerboundRenameItemPacket("x"),
            ServerboundRenameItemPacket.serializer(),
            "0178",
        )
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundRenameItemPacket("x".repeat(32_768)),
            )
        }
    }

    @Test
    fun `resource pack response is uuid followed by strict VarInt result`() {
        assertPacketBytes(
            ServerboundResourcePackPacket(
                id = Uuid.fromLongs(0, 0),
                action = ServerboundResourcePackPacket.Action.DISCARDED,
            ),
            ServerboundResourcePackPacket.serializer(),
            "0000000000000000000000000000000007",
        )
    }

    @Test
    fun `advancement tab identifier is conditional without an optional boolean`() {
        val opened = ServerboundSeenAdvancementsPacket(
            SeenAdvancementsAction.OpenedTab(Identifier("minecraft:test")),
        )
        assertPacketBytes(
            opened,
            ServerboundSeenAdvancementsPacket.serializer(),
            "000e6d696e6563726166743a74657374",
        )
        assertPacketBytes(
            ServerboundSeenAdvancementsPacket(SeenAdvancementsAction.ClosedScreen),
            ServerboundSeenAdvancementsPacket.serializer(),
            "01",
        )

    }

    @Test
    fun `trade beacon and held-item packets retain distinct integer encodings`() {
        assertPacketBytes(
            ServerboundSelectTradePacket(300),
            ServerboundSelectTradePacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ServerboundSetBeaconPacket(primary = 300, secondary = null),
            ServerboundSetBeaconPacket.serializer(),
            "01ac0200",
        )
        assertPacketBytes(
            ServerboundSetBeaconPacket(primary = null, secondary = 1),
            ServerboundSetBeaconPacket.serializer(),
            "000101",
        )
        assertPacketBytes(
            ServerboundSetCarriedItemPacket((-1).toShort()),
            ServerboundSetCarriedItemPacket.serializer(),
            "ffff",
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
