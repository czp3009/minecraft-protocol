package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlayClientboundMovementPacketTest {
    @Test
    fun `world event and low disk warning use official fixed shapes`() {
        assertPacketBytes(
            WorldEventPacket(
                eventId = 1000,
                location = ZERO_POSITION,
                data = -1,
                disableRelativeVolume = true,
            ),
            WorldEventPacket.serializer(),
            "000003e8${ZERO_POSITION_HEX}ffffffff01",
        )
        assertPacketBytes(
            LowDiskSpaceWarningPacket,
            LowDiskSpaceWarningPacket.serializer(),
            "",
        )
    }

    @Test
    fun `relative entity moves preserve shorts and raw angle bytes`() {
        assertPacketBytes(
            UpdateEntityPositionPacket(
                entityId = 300,
                deltaX = 1,
                deltaY = -2,
                deltaZ = Short.MAX_VALUE,
                onGround = true,
            ),
            UpdateEntityPositionPacket.serializer(),
            "ac020001fffe7fff01",
        )
        assertPacketBytes(
            UpdateEntityPositionAndRotationPacket(
                entityId = 1,
                deltaX = 1,
                deltaY = 2,
                deltaZ = 3,
                yaw = Angle(0x80.toByte()),
                pitch = Angle(0x7F),
                onGround = false,
            ),
            UpdateEntityPositionAndRotationPacket.serializer(),
            "01000100020003807f00",
        )
        assertPacketBytes(
            UpdateEntityRotationPacket(
                entityId = 1,
                yaw = Angle(0x40),
                pitch = Angle(0xC0.toByte()),
                onGround = true,
            ),
            UpdateEntityRotationPacket.serializer(),
            "0140c001",
        )
    }

    @Test
    fun `minecart step uses doubles then two angles then weight`() {
        val packet = MoveMinecartAlongTrackPacket(
            entityId = 1,
            steps = listOf(
                MinecartStep(
                    position = Vector3d(1.0, 2.0, 3.0),
                    velocity = Vector3d(-1.0, 0.0, 0.5),
                    yaw = Angle(0x40),
                    pitch = Angle(0xC0.toByte()),
                    weight = 1.0f,
                ),
            ),
        )
        assertPacketBytes(
            packet,
            MoveMinecartAlongTrackPacket.serializer(),
            (
                    "0101" +
                            "3ff0000000000000" +
                            "4000000000000000" +
                            "4008000000000000" +
                            "bff0000000000000" +
                            "0000000000000000" +
                            "3fe0000000000000" +
                            "40c0" +
                            "3f800000"
                    ),
        )
    }

    @Test
    fun `vehicle and UI packets match their primitive codecs`() {
        assertPacketBytes(
            ClientboundMoveVehiclePacket(
                Vector3d(1.0, 2.0, 3.0),
                yaw = 90.0f,
                pitch = -45.0f,
            ),
            ClientboundMoveVehiclePacket.serializer(),
            (
                    "3ff0000000000000" +
                            "4000000000000000" +
                            "4008000000000000" +
                            "42b40000c2340000"
                    ),
        )
        assertPacketBytes(
            OpenBookPacket(InteractionHand.OFF_HAND),
            OpenBookPacket.serializer(),
            "01",
        )
        assertPacketBytes(
            OpenScreenPacket(
                containerId = 300,
                menuTypeId = 2,
                title = TextComponent(NbtString("x")),
            ),
            OpenScreenPacket.serializer(),
            "ac020208000178",
        )
        assertPacketBytes(
            OpenSignEditorPacket(ZERO_POSITION, frontText = true),
            OpenSignEditorPacket.serializer(),
            "${ZERO_POSITION_HEX}01",
        )
    }

    @Test
    fun `ping variants use fixed Int versus fixed Long`() {
        assertPacketBytes(
            ClientboundPingPacket(-1),
            ClientboundPingPacket.serializer(),
            "ffffffff",
        )
        assertPacketBytes(
            PongResponsePacket(0x0102030405060708),
            PongResponsePacket.serializer(),
            "0102030405060708",
        )
    }

    @Test
    fun `player abilities pack flags and discard unknown high bits like vanilla`() {
        val packet = ClientboundPlayerAbilitiesPacket(
            PlayerAbilities(
                invulnerable = true,
                flying = false,
                canFly = true,
                instantBuild = true,
                flyingSpeed = 0.05f,
                walkingSpeed = 0.1f,
            ),
        )
        val canonical = "0d3d4ccccd3dcccccd".hexBytes()
        assertContentEquals(
            canonical,
            MinecraftFormat.encodeToByteArray(
                ClientboundPlayerAbilitiesPacket.serializer(),
                packet,
            ),
        )
        assertEquals(
            packet,
            MinecraftFormat.decodeFromByteArray(
                ClientboundPlayerAbilitiesPacket.serializer(),
                "fd3d4ccccd3dcccccd".hexBytes(),
            ),
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

    private companion object {
        const val ZERO_POSITION_HEX: String = "0000000000000000"
        val ZERO_POSITION: BlockPosition = BlockPosition(0, 0, 0)
    }
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
