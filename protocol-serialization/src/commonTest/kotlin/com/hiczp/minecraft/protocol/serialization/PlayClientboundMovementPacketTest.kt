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

class PlayClientboundMovementPacketTest {
    @Test
    fun `world event and low disk warning use official fixed shapes`() {
        assertPacketBytes(
            ClientboundLevelEventPacket(
                type = 1000,
                pos = ZERO_POSITION,
                data = -1,
                globalEvent = true,
            ),
            ClientboundLevelEventPacket.serializer(),
            "000003e8${ZERO_POSITION_HEX}ffffffff01",
        )
        assertPacketBytes(
            ClientboundLowDiskSpaceWarningPacket,
            ClientboundLowDiskSpaceWarningPacket.serializer(),
            "",
        )
    }

    @Test
    fun `relative entity moves preserve shorts and raw angle bytes`() {
        assertPacketBytes(
            ClientboundMoveEntityPacket.Pos(
                entityId = 300,
                xa = 1,
                ya = -2,
                za = Short.MAX_VALUE,
                onGround = true,
            ),
            ClientboundMoveEntityPacket.Pos.serializer(),
            "ac020001fffe7fff01",
        )
        assertPacketBytes(
            ClientboundMoveEntityPacket.PosRot(
                entityId = 1,
                xa = 1,
                ya = 2,
                za = 3,
                yRot = Angle(0x80.toByte()),
                xRot = Angle(0x7F),
                onGround = false,
            ),
            ClientboundMoveEntityPacket.PosRot.serializer(),
            "01000100020003807f00",
        )
        assertPacketBytes(
            ClientboundMoveEntityPacket.Rot(
                entityId = 1,
                yRot = Angle(0x40),
                xRot = Angle(0xC0.toByte()),
                onGround = true,
            ),
            ClientboundMoveEntityPacket.Rot.serializer(),
            "0140c001",
        )
    }

    @Test
    fun `minecart step uses doubles then two angles then weight`() {
        val clientboundMoveMinecartPacket = ClientboundMoveMinecartPacket(
            entityId = 1,
            lerpSteps = listOf(
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
            clientboundMoveMinecartPacket,
            ClientboundMoveMinecartPacket.serializer(),
            "01013ff000000000000040000000000000004008000000000000bff000000000000000000000000000003fe000000000000040c03f800000",
        )
    }

    @Test
    fun `vehicle and UI packets match their primitive codecs`() {
        assertPacketBytes(
            ClientboundMoveVehiclePacket(
                Vector3d(1.0, 2.0, 3.0),
                yRot = 90.0f,
                xRot = -45.0f,
            ),
            ClientboundMoveVehiclePacket.serializer(),
            "3ff00000000000004000000000000000400800000000000042b40000c2340000",
        )
        assertPacketBytes(
            ClientboundOpenBookPacket(InteractionHand.OFF_HAND),
            ClientboundOpenBookPacket.serializer(),
            "01",
        )
        assertPacketBytes(
            ClientboundOpenScreenPacket(
                containerId = 300,
                type = 2,
                title = TextComponent(NbtString("x")),
            ),
            ClientboundOpenScreenPacket.serializer(),
            "ac020208000178",
        )
        assertPacketBytes(
            ClientboundOpenSignEditorPacket(ZERO_POSITION, isFrontText = true),
            ClientboundOpenSignEditorPacket.serializer(),
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
            ClientboundPongResponsePacket(0x0102030405060708),
            ClientboundPongResponsePacket.serializer(),
            "0102030405060708",
        )
    }

    @Test
    fun `player abilities pack flags and discard unknown high bits like vanilla`() {
        val clientboundPlayerAbilitiesPacket = ClientboundPlayerAbilitiesPacket(
            PlayerAbilities(
                invulnerable = true,
                flying = false,
                canFly = true,
                instantBuild = true,
                flyingSpeed = Float.fromBits(0x3D4C_CCCD),
                walkingSpeed = Float.fromBits(0x3DCC_CCCD),
            ),
        )
        val canonical = "0d3d4ccccd3dcccccd".hexToByteArray()
        assertContentEquals(
            canonical,
            MinecraftPacketPayloadFormat.encodeToByteArray(
                clientboundPlayerAbilitiesPacket,
            ),
        )
        assertEquals(
            clientboundPlayerAbilitiesPacket,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundPlayerAbilitiesPacket>(
                "fd3d4ccccd3dcccccd".hexToByteArray(),
            ),
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

    private companion object {
        const val ZERO_POSITION_HEX: String = "0000000000000000"
        val ZERO_POSITION: BlockPosition = BlockPosition(0, 0, 0)
    }
}
