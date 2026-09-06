package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PlayServerboundContainerAndInteractionPacketTest {
    @Test
    fun `container packets use VarInt identifiers in official field order`() {
        assertPacketBytes(
            ServerboundContainerButtonClickPacket(containerId = 300, buttonId = 1),
            ServerboundContainerButtonClickPacket.serializer(),
            "ac0201",
        )
        assertPacketBytes(
            ServerboundContainerClosePacket(containerId = 300),
            ServerboundContainerClosePacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ServerboundContainerSlotStateChangedPacket(
                slotId = 1,
                containerId = 300,
                newState = true,
            ),
            ServerboundContainerSlotStateChangedPacket.serializer(),
            "01ac0201",
        )
    }

    @Test
    fun `cookie response uses boolean optional then bounded byte array`() {
        val key = Identifier("minecraft:x")
        assertPacketBytes(
            ServerboundCookieResponsePacket(key, null),
            ServerboundCookieResponsePacket.serializer(),
            "0b6d696e6563726166743a7800",
        )
        assertPacketBytes(
            ServerboundCookieResponsePacket(key, ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))),
            ServerboundCookieResponsePacket.serializer(),
            "0b6d696e6563726166743a780102aabb",
        )
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundCookieResponsePacket(key, ByteString(ByteArray(5_121))),
            )
        }
    }

    @Test
    fun `play plugin message preserves the channel-specific payload shape`() {
        assertPacketBytes(
            ServerboundCustomPayloadPacket(CustomPayload.Brand("test")),
            ServerboundCustomPayloadPacket.serializer(),
            "0f6d696e6563726166743a6272616e640474657374",
        )
    }

    @Test
    fun `debug subscription request is a bounded registry-id set`() {
        assertPacketBytes(
            ServerboundDebugSubscriptionRequestPacket(
                linkedSetOf(
                    DebugSubscriptionType.DEDICATED_SERVER_TICK_TIME,
                    DebugSubscriptionType.GAME_EVENT,
                ),
            ),
            ServerboundDebugSubscriptionRequestPacket.serializer(),
            "02000f",
        )
        assertFails {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundDebugSubscriptionRequestPacket>(
                "21000000000000000000000000000000000000000000000000000000000000000000"
                    .hexToByteArray(),
            )
        }
    }

    @Test
    fun `edit book applies independent page-count page-length and title limits`() {
        assertPacketBytes(
            ServerboundEditBookPacket(slot = 1, pages = listOf("a", "bc"), title = "x"),
            ServerboundEditBookPacket.serializer(),
            "01020161026263010178",
        )
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundEditBookPacket(0, List(101) { "" }, null),
            )
        }
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundEditBookPacket(0, listOf("x".repeat(1_025)), null),
            )
        }
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundEditBookPacket(0, emptyList(), "x".repeat(33)),
            )
        }
    }

    @Test
    fun `entity query and interact follow the 26_2 official codecs`() {
        assertPacketBytes(
            ServerboundEntityTagQueryPacket(transactionId = 1, entityId = 300),
            ServerboundEntityTagQueryPacket.serializer(),
            "01ac02",
        )
        assertPacketBytes(
            ServerboundInteractPacket(
                entityId = 1,
                hand = InteractionHand.OFF_HAND,
                location = Vector3d(0.0, 0.0, 0.0),
                usingSecondaryAction = true,
            ),
            ServerboundInteractPacket.serializer(),
            "01010001",
        )
    }

    @Test
    fun `jigsaw keepalive and difficulty lock retain fixed primitive shapes`() {
        assertPacketBytes(
            ServerboundJigsawGeneratePacket(
                pos = BlockPosition(0, 0, 0),
                levels = 300,
                keepJigsaws = true,
            ),
            ServerboundJigsawGeneratePacket.serializer(),
            "0000000000000000ac0201",
        )
        assertPacketBytes(
            ServerboundKeepAlivePacket(0x0102_0304_0506_0708L),
            ServerboundKeepAlivePacket.serializer(),
            "0102030405060708",
        )
        assertPacketBytes(
            ServerboundLockDifficultyPacket(true),
            ServerboundLockDifficultyPacket.serializer(),
            "01",
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
