package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayClientboundStatePacketTest {
    @Test
    fun `display link and low-precision velocity codecs match vanilla`() {
        assertPacketBytes(
            DisplayObjectivePacket(DisplaySlot.TEAM_WHITE, "x"),
            DisplayObjectivePacket.serializer(),
            "120178",
        )
        val invalidSlot = MinecraftFormat.decodeFromByteArray(
            DisplayObjectivePacket.serializer(),
            "7f0178".hexToByteArray(),
        )
        assertEquals(DisplaySlot.LIST, invalidSlot.slot)

        assertPacketBytes(
            LinkEntitiesPacket(1, -1),
            LinkEntitiesPacket.serializer(),
            "00000001ffffffff",
        )
        assertPacketBytes(
            SetEntityVelocityPacket(1, Vector3d(0.0, 0.0, 0.0)),
            SetEntityVelocityPacket.serializer(),
            "0100",
        )
    }

    @Test
    fun `experience health held slot and passengers retain field order`() {
        assertPacketBytes(
            SetExperiencePacket(
                experienceBar = 0.5f,
                level = 300,
                totalExperience = 1,
            ),
            SetExperiencePacket.serializer(),
            "3f000000ac0201",
        )
        assertPacketBytes(
            SetHealthPacket(health = 20.0f, food = 20, saturation = 5.0f),
            SetHealthPacket.serializer(),
            "41a000001440a00000",
        )
        assertPacketBytes(
            ClientboundSetHeldItemPacket(8),
            ClientboundSetHeldItemPacket.serializer(),
            "08",
        )
        assertPacketBytes(
            SetPassengersPacket(1, listOf(1, 300)),
            SetPassengersPacket.serializer(),
            "010201ac02",
        )
        assertPacketBytes(
            SetSimulationDistancePacket(32),
            SetSimulationDistancePacket.serializer(),
            "20",
        )
    }

    @Test
    fun `current time packet uses clock map introduced by 26_2`() {
        val packet = UpdateTimePacket(
            gameTime = 1,
            clocks = linkedMapOf(
                300 to ClockNetworkState(
                    totalTicks = 500,
                    partialTick = 0.5f,
                    rate = 1.0f,
                ),
            ),
        )
        assertPacketBytes(
            packet,
            UpdateTimePacket.serializer(),
            "000000000000000101ac02f4033f0000003f800000",
        )
    }

    @Test
    fun `title and transition packets use NBT or fixed Ints exactly`() {
        assertPacketBytes(
            SetSubtitleTextPacket(TEXT_X),
            SetSubtitleTextPacket.serializer(),
            NBT_X_HEX,
        )
        assertPacketBytes(
            SetTitleTextPacket(TEXT_X),
            SetTitleTextPacket.serializer(),
            NBT_X_HEX,
        )
        assertPacketBytes(
            SetTitleAnimationTimesPacket(1, 2, 3),
            SetTitleAnimationTimesPacket.serializer(),
            "000000010000000200000003",
        )
        assertPacketBytes(
            StartConfigurationPacket,
            StartConfigurationPacket.serializer(),
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
        for ((value, expected) in cases) {
            assertPacketBytes(
                StopSoundPacket(value),
                StopSoundPacket.serializer(),
                expected,
            )
        }

        val highFlags = "f3010b6d696e6563726166743a78".hexToByteArray()
        val decoded = MinecraftFormat.decodeFromByteArray(
            StopSoundPacket.serializer(),
            highFlags,
        )
        assertEquals(
            StopSoundPacket(StopSound(SoundSource.MUSIC, sound)),
            decoded,
        )
        assertContentEquals(
            "03010b6d696e6563726166743a78".hexToByteArray(),
            MinecraftFormat.encodeToByteArray(StopSoundPacket.serializer(), decoded),
        )

    }

    @Test
    fun `cookie and chat packets preserve their payload boundaries`() {
        assertPacketBytes(
            PlayStoreCookiePacket(
                Identifier("minecraft:x"),
                ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
            ),
            PlayStoreCookiePacket.serializer(),
            "0b6d696e6563726166743a7802aabb",
        )
        assertPacketBytes(
            SystemChatMessagePacket(TEXT_X, overlay = true),
            SystemChatMessagePacket.serializer(),
            "${NBT_X_HEX}01",
        )
        assertPacketBytes(
            SetTabListHeaderAndFooterPacket(
                TEXT_X,
                TextComponent(NbtString("y")),
            ),
            SetTabListHeaderAndFooterPacket.serializer(),
            "${NBT_X_HEX}08000179",
        )
    }

    @Test
    fun `tag query uses TAG End instead of a Boolean optional`() {
        assertPacketBytes(
            TagQueryResponsePacket(1, null),
            TagQueryResponsePacket.serializer(),
            "0100",
        )
        assertPacketBytes(
            TagQueryResponsePacket(1, NbtCompound(emptyMap())),
            TagQueryResponsePacket.serializer(),
            "010a00",
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.decodeFromByteArray(
                TagQueryResponsePacket.serializer(),
                "0108000178".hexToByteArray(),
            )
        }
    }

    @Test
    fun `pickup and vehicle synchronization use VarInts then fixed mask`() {
        assertPacketBytes(
            PickupItemPacket(1, 2, 300),
            PickupItemPacket.serializer(),
            "0102ac02",
        )
        assertPacketBytes(
            SynchronizeVehiclePositionPacket(
                entityId = 1,
                change = PositionMoveRotation(
                    Vector3d(0.0, 0.0, 0.0),
                    Vector3d(0.0, 0.0, 0.0),
                    0.0f,
                    0.0f,
                ),
                relatives = RelativeMovements(emptySet()),
                onGround = true,
            ),
            SynchronizeVehiclePositionPacket.serializer(),
            "01${"00".repeat(56)}0000000001",
        )
    }

    @Test
    fun `test block size follows official VarInt Vec3i not stale Wiki doubles`() {
        assertPacketBytes(
            TestInstanceBlockStatusPacket(
                status = TEXT_X,
                size = Vector3i(1, 300, -1),
            ),
            TestInstanceBlockStatusPacket.serializer(),
            "${NBT_X_HEX}0101ac02ffffffff0f",
        )
    }

    @Test
    fun `tick control uses float boolean and VarInt`() {
        assertPacketBytes(
            SetTickingStatePacket(20.0f, frozen = true),
            SetTickingStatePacket.serializer(),
            "41a0000001",
        )
        assertPacketBytes(
            StepTickPacket(300),
            StepTickPacket.serializer(),
            "ac02",
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

    private companion object {
        const val NBT_X_HEX: String = "08000178"
        val TEXT_X: TextComponent = TextComponent(NbtString("x"))
    }
}
