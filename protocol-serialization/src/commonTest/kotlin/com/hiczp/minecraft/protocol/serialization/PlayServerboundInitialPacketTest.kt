package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.packet.GameMode
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PlayServerboundInitialPacketTest {
    @Test
    fun `teleport attack and block query use VarInt identifiers`() {
        assertPacketBytes(
            ConfirmTeleportationPacket(300),
            ConfirmTeleportationPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            AttackPacket(300),
            AttackPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            QueryBlockEntityTagPacket(300, BlockPosition(0, 0, 0)),
            QueryBlockEntityTagPacket.serializer(),
            "ac020000000000000000",
        )
    }

    @Test
    fun `bundle selection accepts only minus one or nonnegative indices`() {
        assertPacketBytes(
            BundleItemSelectedPacket(slotId = 1, selectedItemIndex = -1),
            BundleItemSelectedPacket.serializer(),
            "01ffffffff0f",
        )
        assertFails {
            MinecraftFormat.decodeFromByteArray(
                BundleItemSelectedPacket.serializer(),
                "01feffffff0f".hexBytes(),
            )
        }
    }

    @Test
    fun `difficulty wraps while game mode falls back to zero`() {
        assertPacketBytes(
            ServerboundChangeDifficultyPacket(Difficulty.HARD),
            ServerboundChangeDifficultyPacket.serializer(),
            "03",
        )
        assertEquals(
            Difficulty.HARD,
            MinecraftFormat.decodeFromByteArray(
                ServerboundChangeDifficultyPacket.serializer(),
                "ff01".hexBytes(),
            ).difficulty,
        )
        assertPacketBytes(
            ChangeGameModePacket(GameMode.SPECTATOR),
            ChangeGameModePacket.serializer(),
            "03",
        )
        assertEquals(
            GameMode.SURVIVAL,
            MinecraftFormat.decodeFromByteArray(
                ChangeGameModePacket.serializer(),
                "7f".hexBytes(),
            ).gameMode,
        )
    }

    @Test
    fun `chat acknowledgement command and chunk batch keep primitive shapes`() {
        assertPacketBytes(
            AcknowledgeMessagePacket(300),
            AcknowledgeMessagePacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ChatCommandPacket("x"),
            ChatCommandPacket.serializer(),
            "0178",
        )
        assertPacketBytes(
            ChunkBatchReceivedPacket(1.0f),
            ChunkBatchReceivedPacket.serializer(),
            "3f800000",
        )
    }

    @Test
    fun `client status includes the new game-rule request action`() {
        assertPacketBytes(
            ClientStatusPacket(ClientStatusAction.REQUEST_GAME_RULE_VALUES),
            ClientStatusPacket.serializer(),
            "02",
        )
        assertPacketBytes(
            ClientTickEndPacket,
            ClientTickEndPacket.serializer(),
            "",
        )
    }

    @Test
    fun `play client information reuses the complete common payload`() {
        val packet = PlayClientInformationPacket(
            ClientInformation(
                locale = "en_us",
                viewDistance = 10,
                chatMode = ChatMode.COMMANDS_ONLY,
                chatColors = true,
                displayedSkinParts = 255,
                mainHand = MainHand.RIGHT,
                enableTextFiltering = false,
                allowServerListings = true,
                particleStatus = ParticleStatus.MINIMAL,
            ),
        )
        assertPacketBytes(
            packet,
            PlayClientInformationPacket.serializer(),
            "05656e5f75730a0101ff01000102",
        )
    }

    @Test
    fun `command suggestions use the official 32500 character limit`() {
        assertPacketBytes(
            CommandSuggestionsRequestPacket(300, "x"),
            CommandSuggestionsRequestPacket.serializer(),
            "ac020178",
        )
        assertFails {
            MinecraftFormat.encodeToByteArray(
                CommandSuggestionsRequestPacket.serializer(),
                CommandSuggestionsRequestPacket(1, "x".repeat(32_501)),
            )
        }
        assertPacketBytes(
            AcknowledgeConfigurationPacket,
            AcknowledgeConfigurationPacket.serializer(),
            "",
        )
    }

    private fun <T> assertPacketBytes(
        packet: T,
        serializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexBytes()
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

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
