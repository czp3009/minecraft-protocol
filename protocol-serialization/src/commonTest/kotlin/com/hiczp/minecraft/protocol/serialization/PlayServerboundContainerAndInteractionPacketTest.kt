package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PlayServerboundContainerAndInteractionPacketTest {
    @Test
    fun `container packets use VarInt identifiers in official field order`() {
        assertPacketBytes(
            ClickContainerButtonPacket(containerId = 300, buttonId = 1),
            ClickContainerButtonPacket.serializer(),
            "ac0201",
        )
        assertPacketBytes(
            ServerboundCloseContainerPacket(containerId = 300),
            ServerboundCloseContainerPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ChangeContainerSlotStatePacket(
                slotId = 1,
                containerId = 300,
                enabled = true,
            ),
            ChangeContainerSlotStatePacket.serializer(),
            "01ac0201",
        )
    }

    @Test
    fun `cookie response uses boolean optional then bounded byte array`() {
        val key = Identifier("minecraft:x")
        assertPacketBytes(
            PlayCookieResponsePacket(key, null),
            PlayCookieResponsePacket.serializer(),
            "0b6d696e6563726166743a7800",
        )
        assertPacketBytes(
            PlayCookieResponsePacket(key, ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))),
            PlayCookieResponsePacket.serializer(),
            "0b6d696e6563726166743a780102aabb",
        )
        assertFails {
            MinecraftFormat.encodeToByteArray(
                PlayCookieResponsePacket.serializer(),
                PlayCookieResponsePacket(key, ByteString(ByteArray(5_121))),
            )
        }
    }

    @Test
    fun `play plugin message preserves the channel-specific payload shape`() {
        assertPacketBytes(
            PlayServerboundPluginMessagePacket(CustomPayload.Brand("test")),
            PlayServerboundPluginMessagePacket.serializer(),
            "0f6d696e6563726166743a6272616e640474657374",
        )
    }

    @Test
    fun `debug subscription request is a bounded registry-id set`() {
        assertPacketBytes(
            DebugSubscriptionRequestPacket(
                linkedSetOf(
                    DebugSubscriptionType.DEDICATED_SERVER_TICK_TIME,
                    DebugSubscriptionType.GAME_EVENT,
                ),
            ),
            DebugSubscriptionRequestPacket.serializer(),
            "02000f",
        )
        assertFails {
            MinecraftFormat.decodeFromByteArray(
                DebugSubscriptionRequestPacket.serializer(),
                "21000000000000000000000000000000000000000000000000000000000000000000"
                    .hexToByteArray(),
            )
        }
    }

    @Test
    fun `edit book applies independent page-count page-length and title limits`() {
        assertPacketBytes(
            EditBookPacket(slot = 1, pages = listOf("a", "bc"), title = "x"),
            EditBookPacket.serializer(),
            "01020161026263010178",
        )
        assertFails {
            MinecraftFormat.encodeToByteArray(
                EditBookPacket.serializer(),
                EditBookPacket(0, List(101) { "" }, null),
            )
        }
        assertFails {
            MinecraftFormat.encodeToByteArray(
                EditBookPacket.serializer(),
                EditBookPacket(0, listOf("x".repeat(1_025)), null),
            )
        }
        assertFails {
            MinecraftFormat.encodeToByteArray(
                EditBookPacket.serializer(),
                EditBookPacket(0, emptyList(), "x".repeat(33)),
            )
        }
    }

    @Test
    fun `entity query and interact follow the 26_2 official codecs`() {
        assertPacketBytes(
            QueryEntityTagPacket(transactionId = 1, entityId = 300),
            QueryEntityTagPacket.serializer(),
            "01ac02",
        )
        assertPacketBytes(
            InteractPacket(
                entityId = 1,
                hand = InteractionHand.OFF_HAND,
                targetOffset = Vector3d(0.0, 0.0, 0.0),
                usingSecondaryAction = true,
            ),
            InteractPacket.serializer(),
            "01010001",
        )
    }

    @Test
    fun `jigsaw keepalive and difficulty lock retain fixed primitive shapes`() {
        assertPacketBytes(
            JigsawGeneratePacket(
                location = BlockPosition(0, 0, 0),
                levels = 300,
                keepJigsaws = true,
            ),
            JigsawGeneratePacket.serializer(),
            "0000000000000000ac0201",
        )
        assertPacketBytes(
            PlayServerboundKeepAlivePacket(0x0102_0304_0506_0708L),
            PlayServerboundKeepAlivePacket.serializer(),
            "0102030405060708",
        )
        assertPacketBytes(
            LockDifficultyPacket(true),
            LockDifficultyPacket.serializer(),
            "01",
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
