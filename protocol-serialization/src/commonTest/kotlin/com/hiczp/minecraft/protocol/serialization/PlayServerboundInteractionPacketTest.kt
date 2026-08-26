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
import kotlin.test.assertFails
import kotlin.uuid.Uuid

class PlayServerboundInteractionPacketTest {
    @Test
    fun `spectator target uses zero-as-absent shifted VarInt`() {
        assertPacketBytes(
            SpectatorActionPacket(null),
            SpectatorActionPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            SpectatorActionPacket(0),
            SpectatorActionPacket.serializer(),
            "01",
        )
        assertPacketBytes(
            SpectatorActionPacket(300),
            SpectatorActionPacket.serializer(),
            "ad02",
        )
        assertFails {
            MinecraftProtocolFormat.encodeToByteArray(
                SpectatorActionPacket(-1),
            )
        }
    }

    @Test
    fun `swing and teleport packets use VarInt hand and fixed UUID`() {
        assertPacketBytes(
            SwingArmPacket(InteractionHand.OFF_HAND),
            SwingArmPacket.serializer(),
            "01",
        )
        assertPacketBytes(
            TeleportToEntityPacket(Uuid.fromLongs(0, 0)),
            TeleportToEntityPacket.serializer(),
            "00000000000000000000000000000000",
        )
    }

    @Test
    fun `test instance data combines three distinct enum fallback policies`() {
        val testInstanceBlockActionPacket = TestInstanceBlockActionPacket(
            position = BlockPosition(0, 0, 0),
            action = TestInstanceAction.RUN,
            data = TestInstanceData(
                test = null,
                size = TestInstanceSize(1, 300, 0),
                rotation = StructureRotation.COUNTERCLOCKWISE_90,
                ignoreEntities = true,
                status = TestInstanceStatus.FINISHED,
                errorMessage = TextComponent.literal("x"),
            ),
        )
        assertPacketBytes(
            testInstanceBlockActionPacket,
            TestInstanceBlockActionPacket.serializer(),
            "0000000000000000060001ac02000301020108000178",
        )

        val fallback = MinecraftProtocolFormat.decodeFromByteArray<TestInstanceBlockActionPacket>(
            "00000000000000007f00000000ff01007f00".hexToByteArray(),
        )
        assertEquals(TestInstanceAction.INIT, fallback.action)
        assertEquals(StructureRotation.COUNTERCLOCKWISE_90, fallback.data.rotation)
        assertEquals(TestInstanceStatus.CLEARED, fallback.data.status)
        assertContentEquals(
            "0000000000000000000000000003000000".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(fallback),
        )
    }

    @Test
    fun `use item on embeds block hit result before the sequence`() {
        assertPacketBytes(
            UseItemOnPacket(
                hand = InteractionHand.OFF_HAND,
                hit = BlockHitResult(
                    location = BlockPosition(0, 0, 0),
                    face = BlockFace.EAST,
                    cursorX = 0.0f,
                    cursorY = 0.5f,
                    cursorZ = 1.0f,
                    insideBlock = true,
                    worldBorderHit = false,
                ),
                sequence = 300,
            ),
            UseItemOnPacket.serializer(),
            "01000000000000000005000000003f0000003f8000000100ac02",
        )
        assertPacketBytes(
            UseItemPacket(
                hand = InteractionHand.OFF_HAND,
                sequence = 300,
                yaw = 1.0f,
                pitch = -2.0f,
            ),
            UseItemPacket.serializer(),
            "01ac023f800000c0000000",
        )
    }

    @Test
    fun `play custom click action length-prefixes an NBT-End optional`() {
        val id = Identifier("minecraft:x")
        assertPacketBytes(
            PlayCustomClickActionPacket(id, null),
            PlayCustomClickActionPacket.serializer(),
            "0b6d696e6563726166743a780100",
        )
        assertPacketBytes(
            PlayCustomClickActionPacket(id, NbtString("x")),
            PlayCustomClickActionPacket.serializer(),
            "0b6d696e6563726166743a780408000178",
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
