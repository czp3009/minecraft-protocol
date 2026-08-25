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
        val flags = PlayerMovementFlags(onGround = true, horizontalCollision = true)
        assertPacketBytes(
            SetPlayerPositionPacket(1.0, 2.0, -1.0, flags),
            SetPlayerPositionPacket.serializer(),
            "3ff00000000000004000000000000000bff000000000000003",
        )
        assertPacketBytes(
            SetPlayerPositionAndRotationPacket(
                x = 0.0,
                feetY = 0.0,
                z = 0.0,
                yaw = 1.0f,
                pitch = -2.0f,
                flags = flags,
            ),
            SetPlayerPositionAndRotationPacket.serializer(),
            "0000000000000000000000000000000000000000000000003f800000c000000003",
        )
        assertPacketBytes(
            SetPlayerRotationPacket(1.0f, -2.0f, flags),
            SetPlayerRotationPacket.serializer(),
            "3f800000c000000003",
        )
        assertPacketBytes(
            SetPlayerMovementFlagsPacket(flags),
            SetPlayerMovementFlagsPacket.serializer(),
            "03",
        )

        val decoded = MinecraftProtocolFormat.decodeFromByteArray<SetPlayerMovementFlagsPacket>(
            "ff".hexToByteArray(),
        )
        assertEquals(flags, decoded.flags)
        assertContentEquals(
            "03".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(decoded),
        )
    }

    @Test
    fun `vehicle paddle and pick packets follow their independent primitive layouts`() {
        assertPacketBytes(
            ServerboundMoveVehiclePacket(
                position = Vector3d(0.0, 0.0, 0.0),
                yaw = 0.0f,
                pitch = 0.0f,
                onGround = true,
            ),
            ServerboundMoveVehiclePacket.serializer(),
            "000000000000000000000000000000000000000000000000000000000000000001",
        )
        assertPacketBytes(
            PaddleBoatPacket(leftPaddleTurning = true, rightPaddleTurning = false),
            PaddleBoatPacket.serializer(),
            "0100",
        )
        assertPacketBytes(
            PickItemFromBlockPacket(BlockPosition(0, 0, 0), includeData = true),
            PickItemFromBlockPacket.serializer(),
            "000000000000000001",
        )
        assertPacketBytes(
            PickItemFromEntityPacket(entityId = 300, includeData = true),
            PickItemFromEntityPacket.serializer(),
            "ac0201",
        )
    }

    @Test
    fun `ping recipe ability and pong distinguish fixed integers from VarInts`() {
        assertPacketBytes(
            PlayPingRequestPacket(0x0102_0304_0506_0708L),
            PlayPingRequestPacket.serializer(),
            "0102030405060708",
        )
        assertPacketBytes(
            PlaceRecipePacket(containerId = 300, recipeId = 1, makeAll = true),
            PlaceRecipePacket.serializer(),
            "ac020101",
        )
        assertPacketBytes(
            ServerboundPlayerAbilitiesPacket(ServerboundAbilities(flying = true)),
            ServerboundPlayerAbilitiesPacket.serializer(),
            "02",
        )
        val ability = MinecraftProtocolFormat.decodeFromByteArray<ServerboundPlayerAbilitiesPacket>(
            "ff".hexToByteArray(),
        )
        assertContentEquals(
            "02".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(ability),
        )
        assertPacketBytes(
            PlayPongPacket(0x0102_0304),
            PlayPongPacket.serializer(),
            "01020304",
        )
    }

    @Test
    fun `action direction and sequence match official enum codecs`() {
        assertPacketBytes(
            PlayerActionPacket(
                action = PlayerAction.STAB,
                location = BlockPosition(0, 0, 0),
                face = BlockFace.EAST,
                sequence = 300,
            ),
            PlayerActionPacket.serializer(),
            "07000000000000000005ac02",
        )

        val wrappedFace = MinecraftProtocolFormat.decodeFromByteArray<PlayerActionPacket>(
            "000000000000000000ff00".hexToByteArray(),
        )
        assertEquals(BlockFace.SOUTH, wrappedFace.face)
        assertContentEquals(
            "0000000000000000000300".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(wrappedFace),
        )
        assertPacketBytes(
            PlayerCommandPacket(
                entityId = 1,
                action = PlayerCommandAction.START_FALL_FLYING,
                jumpBoost = 300,
            ),
            PlayerCommandPacket.serializer(),
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
            PlayerInputPacket(all),
            PlayerInputPacket.serializer(),
            "7f",
        )
        val decoded = MinecraftProtocolFormat.decodeFromByteArray<PlayerInputPacket>(
            "ff".hexToByteArray(),
        )
        assertEquals(all, decoded.input)
        assertContentEquals(
            "7f".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(decoded),
        )
        assertPacketBytes(PlayerLoadedPacket, PlayerLoadedPacket.serializer(), "")
    }

    private fun <T> assertPacketBytes(
        packet: T,
        serializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(serializer, packet),
        )
        assertEquals(
            packet,
            MinecraftProtocolFormat.decodeFromByteArray(serializer, expected),
        )
    }
}
