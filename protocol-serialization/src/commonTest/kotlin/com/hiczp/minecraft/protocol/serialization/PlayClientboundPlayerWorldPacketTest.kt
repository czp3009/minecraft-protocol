package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PlayClientboundPlayerWorldPacketTest {
    @Test
    fun `combat and player removal packets match vanilla`() {
        assertPacketBytes(
            EndCombatPacket(300),
            EndCombatPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            EnterCombatPacket,
            EnterCombatPacket.serializer(),
            "",
        )
        assertPacketBytes(
            CombatDeathPacket(1, TEXT_X),
            CombatDeathPacket.serializer(),
            "0108000178",
        )
        assertPacketBytes(
            PlayerInfoRemovePacket(listOf(ZERO_UUID)),
            PlayerInfoRemovePacket.serializer(),
            "0100000000000000000000000000000000",
        )
    }

    @Test
    fun `look target writes its entity branch only when selected`() {
        val position = Vector3d(1.0, 2.0, 3.0)
        val positionPacket = LookAtPacket(
            EntityAnchor.EYES,
            LookTarget.Position(position),
        )
        assertPacketBytes(
            positionPacket,
            LookAtPacket.serializer(),
            "01$VECTOR_123_HEX" + "00",
        )

        val entityPacket = LookAtPacket(
            EntityAnchor.FEET,
            LookTarget.Entity(position, entityId = 300, EntityAnchor.EYES),
        )
        assertPacketBytes(
            entityPacket,
            LookAtPacket.serializer(),
            "00$VECTOR_123_HEX" + "01ac0201",
        )

    }

    @Test
    fun `player synchronization uses a fixed Int relative bit mask`() {
        val packet = SynchronizePlayerPositionPacket(
            teleportId = 1,
            change = PositionMoveRotation(
                Vector3d(0.0, 0.0, 0.0),
                Vector3d(0.0, 0.0, 0.0),
                yaw = 0.0f,
                pitch = 0.0f,
            ),
            relatives = RelativeMovements(
                setOf(
                    RelativeMovement.X,
                    RelativeMovement.VELOCITY_Z,
                    RelativeMovement.ROTATE_VELOCITY,
                ),
            ),
        )
        assertPacketBytes(
            packet,
            SynchronizePlayerPositionPacket.serializer(),
            "01" + "00".repeat(56) + "00000181",
        )
        assertPacketBytes(
            PlayerRotationPacket(
                yaw = 1.0f,
                relativeYaw = true,
                pitch = -2.0f,
                relativePitch = false,
            ),
            PlayerRotationPacket.serializer(),
            "3f80000001c000000000",
        )

        val withUnknownBits = (
                "01" + "00".repeat(56) + "ffffff81"
                ).hexToByteArray()
        assertEquals(
            packet,
            MinecraftFormat.decodeFromByteArray(
                SynchronizePlayerPositionPacket.serializer(),
                withUnknownBits,
            ),
        )
    }

    @Test
    fun `recipe and entity ID arrays use VarInt elements`() {
        assertPacketBytes(
            RecipeBookRemovePacket(listOf(1, 300)),
            RecipeBookRemovePacket.serializer(),
            "0201ac02",
        )
        assertPacketBytes(
            RemoveEntitiesPacket(listOf(1, 300)),
            RemoveEntitiesPacket.serializer(),
            "0201ac02",
        )
        assertPacketBytes(
            RemoveEntityEffectPacket(entityId = 1, effectTypeId = 300),
            RemoveEntityEffectPacket.serializer(),
            "01ac02",
        )

        val settings = RecipeBookSettings(
            crafting = RecipeBookTypeSettings(open = true, filtering = false),
            furnace = RecipeBookTypeSettings(open = false, filtering = true),
            blastFurnace = RecipeBookTypeSettings(open = true, filtering = false),
            smoker = RecipeBookTypeSettings(open = false, filtering = true),
        )
        assertPacketBytes(
            RecipeBookSettingsPacket(settings),
            RecipeBookSettingsPacket.serializer(),
            "0100000101000001",
        )
    }

    @Test
    fun `score and resource-pack optionals are Boolean prefixed`() {
        assertPacketBytes(
            ResetScorePacket("x", null),
            ResetScorePacket.serializer(),
            "017800",
        )
        assertPacketBytes(
            ResetScorePacket("x", "y"),
            ResetScorePacket.serializer(),
            "0178010179",
        )
        assertPacketBytes(
            PlayRemoveResourcePackPacket(null),
            PlayRemoveResourcePackPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            PlayRemoveResourcePackPacket(ZERO_UUID),
            PlayRemoveResourcePackPacket.serializer(),
            "0100000000000000000000000000000000",
        )
        assertPacketBytes(
            PlayAddResourcePackPacket(
                id = ZERO_UUID,
                url = "u",
                hash = "h",
                required = true,
                prompt = TEXT_X,
            ),
            PlayAddResourcePackPacket.serializer(),
            (
                    "00000000000000000000000000000000" +
                            "0175" +
                            "0168" +
                            "01" +
                            "0108000178"
                    ),
        )
    }

    @Test
    fun `section block changes pack state and local coordinates into VarLongs`() {
        val packet = UpdateSectionBlocksPacket(
            sectionPosition = SectionPosition(1, 2, 3),
            blocks = listOf(
                SectionBlockChange(
                    blockStateId = 300,
                    localX = 1,
                    localY = 2,
                    localZ = 3,
                ),
            ),
        )
        assertPacketBytes(
            packet,
            UpdateSectionBlocksPacket.serializer(),
            "000004000030000201b2824b",
        )
        assertPacketBytes(
            SetHeadRotationPacket(300, Angle(0xFF.toByte())),
            SetHeadRotationPacket.serializer(),
            "ac02ff",
        )
    }

    @Test
    fun `server presentation packets retain NBT and byte-array boundaries`() {
        assertPacketBytes(
            SelectAdvancementsTabPacket(null),
            SelectAdvancementsTabPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            ServerDataPacket(
                motd = TEXT_X,
                iconPng = ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
            ),
            ServerDataPacket.serializer(),
            "080001780102aabb",
        )
        assertPacketBytes(
            SetActionBarTextPacket(TEXT_X),
            SetActionBarTextPacket.serializer(),
            "08000178",
        )
    }

    @Test
    fun `world-border and chunk-cache values keep fixed and variable widths`() {
        assertPacketBytes(
            SetBorderCenterPacket(1.0, -2.0),
            SetBorderCenterPacket.serializer(),
            "3ff0000000000000c000000000000000",
        )
        assertPacketBytes(
            SetBorderLerpSizePacket(1.0, 2.0, 300),
            SetBorderLerpSizePacket.serializer(),
            "3ff00000000000004000000000000000ac02",
        )
        assertPacketBytes(
            SetBorderSizePacket(3.0),
            SetBorderSizePacket.serializer(),
            "4008000000000000",
        )
        assertPacketBytes(
            SetBorderWarningDelayPacket(300),
            SetBorderWarningDelayPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            SetBorderWarningDistancePacket(300),
            SetBorderWarningDistancePacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            SetCameraPacket(300),
            SetCameraPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            SetCenterChunkPacket(-1, 300),
            SetCenterChunkPacket.serializer(),
            "ffffffff0fac02",
        )
        assertPacketBytes(
            SetRenderDistancePacket(32),
            SetRenderDistancePacket.serializer(),
            "20",
        )
    }

    @Test
    fun `default spawn includes dimension position yaw and pitch`() {
        val packet = SetDefaultSpawnPositionPacket(
            RespawnData(
                GlobalPosition(
                    Identifier("minecraft:overworld"),
                    BlockPosition(0, 0, 0),
                ),
                yaw = 1.0f,
                pitch = -2.0f,
            ),
        )
        assertPacketBytes(
            packet,
            SetDefaultSpawnPositionPacket.serializer(),
            (
                    "13" +
                            "6d696e6563726166743a6f766572776f726c64" +
                            ZERO_POSITION_HEX +
                            "3f800000c0000000"
                    ),
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
        const val ZERO_POSITION_HEX: String = "0000000000000000"
        const val VECTOR_123_HEX: String =
            "3ff000000000000040000000000000004008000000000000"
        val ZERO_UUID: Uuid = Uuid.fromLongs(0, 0)
        val TEXT_X: TextComponent = TextComponent(NbtString("x"))
    }
}
