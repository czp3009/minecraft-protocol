package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayClientboundStatePacketTest {
    @Test
    fun `display link and low-precision velocity codecs match vanilla`() {
        assertPacketBytes(
            ClientboundSetDisplayObjectivePacket(DisplaySlot.TEAM_WHITE, "x"),
            ClientboundSetDisplayObjectivePacket.serializer(),
            "120178",
        )
        val invalidSlot = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundSetDisplayObjectivePacket>(
            "7f0178".hexToByteArray(),
        )
        assertEquals(DisplaySlot.LIST, invalidSlot.slot)

        assertPacketBytes(
            ClientboundSetEntityLinkPacket(1, -1),
            ClientboundSetEntityLinkPacket.serializer(),
            "00000001ffffffff",
        )
        assertPacketBytes(
            ClientboundSetEntityMotionPacket(1, Vector3d(0.0, 0.0, 0.0)),
            ClientboundSetEntityMotionPacket.serializer(),
            "0100",
        )
    }

    @Test
    fun `experience health held slot and passengers retain field order`() {
        assertPacketBytes(
            ClientboundSetExperiencePacket(
                experienceProgress = 0.5f,
                experienceLevel = 300,
                totalExperience = 1,
            ),
            ClientboundSetExperiencePacket.serializer(),
            "3f000000ac0201",
        )
        assertPacketBytes(
            ClientboundSetHealthPacket(health = 20.0f, food = 20, saturation = 5.0f),
            ClientboundSetHealthPacket.serializer(),
            "41a000001440a00000",
        )
        assertPacketBytes(
            ClientboundSetHeldSlotPacket(8),
            ClientboundSetHeldSlotPacket.serializer(),
            "08",
        )
        assertPacketBytes(
            ClientboundSetPassengersPacket(1, listOf(1, 300)),
            ClientboundSetPassengersPacket.serializer(),
            "010201ac02",
        )
        assertPacketBytes(
            ClientboundSetSimulationDistancePacket(32),
            ClientboundSetSimulationDistancePacket.serializer(),
            "20",
        )
    }

    @Test
    fun `current time packet uses clock map introduced by 26_2`() {
        val clientboundSetTimePacket = ClientboundSetTimePacket(
            gameTime = 1,
            clockUpdates = linkedMapOf(
                300 to ClockNetworkState(
                    totalTicks = 500,
                    partialTick = 0.5f,
                    rate = 1.0f,
                ),
            ),
        )
        assertPacketBytes(
            clientboundSetTimePacket,
            ClientboundSetTimePacket.serializer(),
            "000000000000000101ac02f4033f0000003f800000",
        )
    }

    @Test
    fun `title and transition packets use NBT or fixed Ints exactly`() {
        assertPacketBytes(
            ClientboundSetSubtitleTextPacket(TEXT_X),
            ClientboundSetSubtitleTextPacket.serializer(),
            NBT_X_HEX,
        )
        assertPacketBytes(
            ClientboundSetTitleTextPacket(TEXT_X),
            ClientboundSetTitleTextPacket.serializer(),
            NBT_X_HEX,
        )
        assertPacketBytes(
            ClientboundSetTitlesAnimationPacket(1, 2, 3),
            ClientboundSetTitlesAnimationPacket.serializer(),
            "000000010000000200000003",
        )
        assertPacketBytes(
            ClientboundStartConfigurationPacket,
            ClientboundStartConfigurationPacket.serializer(),
            "",
        )
    }

    @Test
    fun `stop sound covers every flags branch and latest UI source`() {
        val sound = Identifier("minecraft:x")
        val cases = listOf(
            StopSound(null, null) to "00",
            StopSound(SoundSource.UI, null) to "010a",
            StopSound(null, sound) to
                    "020b6d696e6563726166743a78",
            StopSound(SoundSource.MUSIC, sound) to
                    "03010b6d696e6563726166743a78",
        )
        for ((stopSound, expected) in cases) {
            assertPacketBytes(
                ClientboundStopSoundPacket(stopSound),
                ClientboundStopSoundPacket.serializer(),
                expected,
            )
        }

        val highFlags = "f3010b6d696e6563726166743a78".hexToByteArray()
        val decoded = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundStopSoundPacket>(
            highFlags,
        )
        assertEquals(
            ClientboundStopSoundPacket(StopSound(SoundSource.MUSIC, sound)),
            decoded,
        )
        assertContentEquals(
            "03010b6d696e6563726166743a78".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(decoded),
        )

    }

    @Test
    fun `cookie and chat packets preserve their payload boundaries`() {
        assertPacketBytes(
            ClientboundStoreCookiePacket(
                Identifier("minecraft:x"),
                ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
            ),
            ClientboundStoreCookiePacket.serializer(),
            "0b6d696e6563726166743a7802aabb",
        )
        assertPacketBytes(
            ClientboundSystemChatPacket(TEXT_X, overlay = true),
            ClientboundSystemChatPacket.serializer(),
            "${NBT_X_HEX}01",
        )
        assertPacketBytes(
            ClientboundTabListPacket(
                TEXT_X,
                TextComponent(NbtString("y")),
            ),
            ClientboundTabListPacket.serializer(),
            "${NBT_X_HEX}08000179",
        )
    }

    @Test
    fun `tag query uses TAG End instead of a Boolean optional`() {
        assertPacketBytes(
            ClientboundTagQueryPacket(1, null),
            ClientboundTagQueryPacket.serializer(),
            "0100",
        )
        assertPacketBytes(
            ClientboundTagQueryPacket(1, NbtCompound(emptyMap())),
            ClientboundTagQueryPacket.serializer(),
            "010a00",
        )
        assertFailsWith<SerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundTagQueryPacket>(
                "0108000178".hexToByteArray(),
            )
        }
    }

    @Test
    fun `pickup and vehicle synchronization use VarInts then fixed mask`() {
        assertPacketBytes(
            ClientboundTakeItemEntityPacket(1, 2, 300),
            ClientboundTakeItemEntityPacket.serializer(),
            "0102ac02",
        )
        assertPacketBytes(
            ClientboundTeleportEntityPacket(
                id = 1,
                change = PositionMoveRotation(
                    Vector3d(0.0, 0.0, 0.0),
                    Vector3d(0.0, 0.0, 0.0),
                    0.0f,
                    0.0f,
                ),
                relatives = RelativeMovements(emptySet()),
                onGround = true,
            ),
            ClientboundTeleportEntityPacket.serializer(),
            "01${"00".repeat(56)}0000000001",
        )
    }

    @Test
    fun `test block size follows official VarInt Vec3i not stale Wiki doubles`() {
        assertPacketBytes(
            ClientboundTestInstanceBlockStatus(
                status = TEXT_X,
                size = Vector3i(1, 300, -1),
            ),
            ClientboundTestInstanceBlockStatus.serializer(),
            "${NBT_X_HEX}0101ac02ffffffff0f",
        )
    }

    @Test
    fun `tick control uses float boolean and VarInt`() {
        assertPacketBytes(
            ClientboundTickingStatePacket(20.0f, isFrozen = true),
            ClientboundTickingStatePacket.serializer(),
            "41a0000001",
        )
        assertPacketBytes(
            ClientboundTickingStepPacket(300),
            ClientboundTickingStepPacket.serializer(),
            "ac02",
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
        const val NBT_X_HEX: String = "08000178"
        val TEXT_X: TextComponent = TextComponent(NbtString("x"))
    }
}
