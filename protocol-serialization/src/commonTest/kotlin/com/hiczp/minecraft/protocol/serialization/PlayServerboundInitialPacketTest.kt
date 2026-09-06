package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.packet.GameMode
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PlayServerboundInitialPacketTest {
    @Test
    fun `teleport attack and block query use VarInt identifiers`() {
        assertPacketBytes(
            ServerboundAcceptTeleportationPacket(300),
            ServerboundAcceptTeleportationPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ServerboundAttackPacket(300),
            ServerboundAttackPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ServerboundBlockEntityTagQueryPacket(300, BlockPosition(0, 0, 0)),
            ServerboundBlockEntityTagQueryPacket.serializer(),
            "ac020000000000000000",
        )
    }

    @Test
    fun `bundle selection accepts only minus one or nonnegative indices`() {
        assertPacketBytes(
            ServerboundSelectBundleItemPacket(slotId = 1, selectedItemIndex = -1),
            ServerboundSelectBundleItemPacket.serializer(),
            "01ffffffff0f",
        )
        assertFails {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundSelectBundleItemPacket>(
                "01feffffff0f".hexToByteArray(),
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
            MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundChangeDifficultyPacket>(
                "ff01".hexToByteArray(),
            ).difficulty,
        )
        assertPacketBytes(
            ServerboundChangeGameModePacket(GameMode.SPECTATOR),
            ServerboundChangeGameModePacket.serializer(),
            "03",
        )
        assertEquals(
            GameMode.SURVIVAL,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundChangeGameModePacket>(
                "7f".hexToByteArray(),
            ).mode,
        )
    }

    @Test
    fun `chat acknowledgement command and chunk batch keep primitive shapes`() {
        assertPacketBytes(
            ServerboundChatAckPacket(300),
            ServerboundChatAckPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ServerboundChatCommandPacket("x"),
            ServerboundChatCommandPacket.serializer(),
            "0178",
        )
        assertPacketBytes(
            ServerboundChunkBatchReceivedPacket(1.0f),
            ServerboundChunkBatchReceivedPacket.serializer(),
            "3f800000",
        )
    }

    @Test
    fun `client status includes the new game-rule request action`() {
        assertPacketBytes(
            ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_GAMERULE_VALUES),
            ServerboundClientCommandPacket.serializer(),
            "02",
        )
        assertPacketBytes(
            ServerboundClientTickEndPacket,
            ServerboundClientTickEndPacket.serializer(),
            "",
        )
    }

    @Test
    fun `play client information reuses the complete common payload`() {
        val serverboundClientInformationPacket = ServerboundClientInformationPacket(
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
            serverboundClientInformationPacket,
            ServerboundClientInformationPacket.serializer(),
            "05656e5f75730a0101ff01000102",
        )
    }

    @Test
    fun `command suggestions use the official 32500 character limit`() {
        assertPacketBytes(
            ServerboundCommandSuggestionPacket(300, "x"),
            ServerboundCommandSuggestionPacket.serializer(),
            "ac020178",
        )
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundCommandSuggestionPacket(1, "x".repeat(32_501)),
            )
        }
        assertPacketBytes(
            ServerboundConfigurationAcknowledgedPacket,
            ServerboundConfigurationAcknowledgedPacket.serializer(),
            "",
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
