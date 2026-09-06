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
            ServerboundSpectatorActionPacket(null),
            ServerboundSpectatorActionPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            ServerboundSpectatorActionPacket(0),
            ServerboundSpectatorActionPacket.serializer(),
            "01",
        )
        assertPacketBytes(
            ServerboundSpectatorActionPacket(300),
            ServerboundSpectatorActionPacket.serializer(),
            "ad02",
        )
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundSpectatorActionPacket(-1),
            )
        }
    }

    @Test
    fun `swing and teleport packets use VarInt hand and fixed UUID`() {
        assertPacketBytes(
            ServerboundSwingPacket(InteractionHand.OFF_HAND),
            ServerboundSwingPacket.serializer(),
            "01",
        )
        assertPacketBytes(
            ServerboundTeleportToEntityPacket(Uuid.fromLongs(0, 0)),
            ServerboundTeleportToEntityPacket.serializer(),
            "00000000000000000000000000000000",
        )
    }

    @Test
    fun `test instance data combines three distinct enum fallback policies`() {
        val serverboundTestInstanceBlockActionPacket = ServerboundTestInstanceBlockActionPacket(
            pos = BlockPosition(0, 0, 0),
            action = ServerboundTestInstanceBlockActionPacket.Action.RUN,
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
            serverboundTestInstanceBlockActionPacket,
            ServerboundTestInstanceBlockActionPacket.serializer(),
            "0000000000000000060001ac02000301020108000178",
        )

        val fallback = MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundTestInstanceBlockActionPacket>(
            "00000000000000007f00000000ff01007f00".hexToByteArray(),
        )
        assertEquals(ServerboundTestInstanceBlockActionPacket.Action.INIT, fallback.action)
        assertEquals(StructureRotation.COUNTERCLOCKWISE_90, fallback.data.rotation)
        assertEquals(TestInstanceStatus.CLEARED, fallback.data.status)
        assertContentEquals(
            "0000000000000000000000000003000000".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(fallback),
        )
    }

    @Test
    fun `use item on embeds block hit result before the sequence`() {
        assertPacketBytes(
            ServerboundUseItemOnPacket(
                hand = InteractionHand.OFF_HAND,
                blockHit = BlockHitResult(
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
            ServerboundUseItemOnPacket.serializer(),
            "01000000000000000005000000003f0000003f8000000100ac02",
        )
        assertPacketBytes(
            ServerboundUseItemPacket(
                hand = InteractionHand.OFF_HAND,
                sequence = 300,
                yRot = 1.0f,
                xRot = -2.0f,
            ),
            ServerboundUseItemPacket.serializer(),
            "01ac023f800000c0000000",
        )
    }

    @Test
    fun `play custom click action length-prefixes an NBT-End optional`() {
        val id = Identifier("minecraft:x")
        assertPacketBytes(
            ServerboundCustomClickActionPacket(id, null),
            ServerboundCustomClickActionPacket.serializer(),
            "0b6d696e6563726166743a780100",
        )
        assertPacketBytes(
            ServerboundCustomClickActionPacket(id, NbtString("x")),
            ServerboundCustomClickActionPacket.serializer(),
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
            MinecraftPacketPayloadFormat.encodeToByteArray(kSerializer, packet),
        )
        assertEquals(
            packet,
            MinecraftPacketPayloadFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
