package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlayServerboundMovementAndActionPacketTest {
    @Test
    fun `all four player movement variants pack status into one byte`() {
        val playerMovementFlags = PlayerMovementFlags(onGround = true, horizontalCollision = true)
        assertPacketBytes(
            ServerboundMovePlayerPacket.Pos(1.0, 2.0, -1.0, playerMovementFlags),
            ServerboundMovePlayerPacket.Pos.serializer(),
            "3ff00000000000004000000000000000bff000000000000003",
        )
        assertPacketBytes(
            ServerboundMovePlayerPacket.PosRot(
                x = 0.0,
                y = 0.0,
                z = 0.0,
                yRot = 1.0f,
                xRot = -2.0f,
                flags = playerMovementFlags,
            ),
            ServerboundMovePlayerPacket.PosRot.serializer(),
            "0000000000000000000000000000000000000000000000003f800000c000000003",
        )
        assertPacketBytes(
            ServerboundMovePlayerPacket.Rot(1.0f, -2.0f, playerMovementFlags),
            ServerboundMovePlayerPacket.Rot.serializer(),
            "3f800000c000000003",
        )
        assertPacketBytes(
            ServerboundMovePlayerPacket.StatusOnly(playerMovementFlags),
            ServerboundMovePlayerPacket.StatusOnly.serializer(),
            "03",
        )

        val decoded = MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundMovePlayerPacket.StatusOnly>(
            "ff".hexToByteArray(),
        )
        assertEquals(playerMovementFlags, decoded.flags)
        assertContentEquals(
            "03".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(decoded),
        )
    }

    @Test
    fun `vehicle paddle and pick packets follow their independent primitive layouts`() {
        assertPacketBytes(
            ServerboundMoveVehiclePacket(
                position = Vector3d(0.0, 0.0, 0.0),
                yRot = 0.0f,
                xRot = 0.0f,
                onGround = true,
            ),
            ServerboundMoveVehiclePacket.serializer(),
            "000000000000000000000000000000000000000000000000000000000000000001",
        )
        assertPacketBytes(
            ServerboundPaddleBoatPacket(left = true, right = false),
            ServerboundPaddleBoatPacket.serializer(),
            "0100",
        )
        assertPacketBytes(
            ServerboundPickItemFromBlockPacket(BlockPosition(0, 0, 0), includeData = true),
            ServerboundPickItemFromBlockPacket.serializer(),
            "000000000000000001",
        )
        assertPacketBytes(
            ServerboundPickItemFromEntityPacket(id = 300, includeData = true),
            ServerboundPickItemFromEntityPacket.serializer(),
            "ac0201",
        )
    }

    @Test
    fun `ping recipe ability and pong distinguish fixed integers from VarInts`() {
        assertPacketBytes(
            ServerboundPingRequestPacket(0x0102_0304_0506_0708L),
            ServerboundPingRequestPacket.serializer(),
            "0102030405060708",
        )
        assertPacketBytes(
            ServerboundPlaceRecipePacket(containerId = 300, recipe = 1, useMaxItems = true),
            ServerboundPlaceRecipePacket.serializer(),
            "ac020101",
        )
        assertPacketBytes(
            ServerboundPlayerAbilitiesPacket(isFlying = true),
            ServerboundPlayerAbilitiesPacket.serializer(),
            "02",
        )
        val serverboundPlayerAbilitiesPacket =
            MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundPlayerAbilitiesPacket>(
                "ff".hexToByteArray(),
            )
        assertContentEquals(
            "02".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(serverboundPlayerAbilitiesPacket),
        )
        assertPacketBytes(
            ServerboundPongPacket(0x0102_0304),
            ServerboundPongPacket.serializer(),
            "01020304",
        )
    }

    @Test
    fun `action direction and sequence match official enum codecs`() {
        assertPacketBytes(
            ServerboundPlayerActionPacket(
                action = ServerboundPlayerActionPacket.Action.STAB,
                pos = BlockPosition(0, 0, 0),
                direction = BlockFace.EAST,
                sequence = 300,
            ),
            ServerboundPlayerActionPacket.serializer(),
            "07000000000000000005ac02",
        )

        val wrappedFace = MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundPlayerActionPacket>(
            "000000000000000000ff00".hexToByteArray(),
        )
        assertEquals(BlockFace.SOUTH, wrappedFace.direction)
        assertContentEquals(
            "0000000000000000000300".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(wrappedFace),
        )
        assertPacketBytes(
            ServerboundPlayerCommandPacket(
                id = 1,
                action = ServerboundPlayerCommandPacket.Action.START_FALL_FLYING,
                data = 300,
            ),
            ServerboundPlayerCommandPacket.serializer(),
            "0106ac02",
        )
    }

    @Test
    fun `player input packs seven booleans and discards unknown high bit`() {
        val all = PlayerInput(
            forward = true,
            backward = true,
            left = true,
            right = true,
            jump = true,
            shift = true,
            sprint = true,
        )
        assertPacketBytes(
            ServerboundPlayerInputPacket(all),
            ServerboundPlayerInputPacket.serializer(),
            "7f",
        )
        val decoded = MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundPlayerInputPacket>(
            "ff".hexToByteArray(),
        )
        assertEquals(all, decoded.input)
        assertContentEquals(
            "7f".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(decoded),
        )
        assertPacketBytes(ServerboundPlayerLoadedPacket, ServerboundPlayerLoadedPacket.serializer(), "")
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
