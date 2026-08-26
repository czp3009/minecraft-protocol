package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.RecipeBookCategory
import com.hiczp.minecraft.protocol.model.type.ResourcePackResult
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
            ChangeRecipeBookSettingsPacket(
                book = RecipeBookCategory.SMOKER,
                open = true,
                filtering = false,
            ),
            ChangeRecipeBookSettingsPacket.serializer(),
            "030100",
        )
        assertPacketBytes(
            SetSeenRecipePacket(300),
            SetSeenRecipePacket.serializer(),
            "ac02",
        )
    }

    @Test
    fun `rename item enforces the codec string bound rather than server gameplay policy`() {
        assertPacketBytes(
            RenameItemPacket("x"),
            RenameItemPacket.serializer(),
            "0178",
        )
        assertFails {
            MinecraftProtocolFormat.encodeToByteArray(
                RenameItemPacket("x".repeat(32_768)),
            )
        }
    }

    @Test
    fun `resource pack response is uuid followed by strict VarInt result`() {
        assertPacketBytes(
            PlayResourcePackResponsePacket(
                id = Uuid.fromLongs(0, 0),
                result = ResourcePackResult.DISCARDED,
            ),
            PlayResourcePackResponsePacket.serializer(),
            "0000000000000000000000000000000007",
        )
    }

    @Test
    fun `advancement tab identifier is conditional without an optional boolean`() {
        val opened = SeenAdvancementsPacket(
            SeenAdvancementsAction.OpenedTab(Identifier("minecraft:test")),
        )
        assertPacketBytes(
            opened,
            SeenAdvancementsPacket.serializer(),
            "000e6d696e6563726166743a74657374",
        )
        assertPacketBytes(
            SeenAdvancementsPacket(SeenAdvancementsAction.ClosedScreen),
            SeenAdvancementsPacket.serializer(),
            "01",
        )

    }

    @Test
    fun `trade beacon and held-item packets retain distinct integer encodings`() {
        assertPacketBytes(
            SelectTradePacket(300),
            SelectTradePacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            SetBeaconEffectPacket(primaryEffectId = 300, secondaryEffectId = null),
            SetBeaconEffectPacket.serializer(),
            "01ac0200",
        )
        assertPacketBytes(
            SetBeaconEffectPacket(primaryEffectId = null, secondaryEffectId = 1),
            SetBeaconEffectPacket.serializer(),
            "000101",
        )
        assertPacketBytes(
            ServerboundSetHeldItemPacket((-1).toShort()),
            ServerboundSetHeldItemPacket.serializer(),
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
            MinecraftProtocolFormat.encodeToByteArray(kSerializer, packet),
        )
        assertEquals(
            packet,
            MinecraftProtocolFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
