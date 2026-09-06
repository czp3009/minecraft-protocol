package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray

class PlayClientboundPlayerWorldPacketTest {
    @Test
    fun `combat and player removal packets match vanilla`() {
        assertPacketBytes(
            ClientboundPlayerCombatEndPacket(300),
            ClientboundPlayerCombatEndPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ClientboundPlayerCombatEnterPacket,
            ClientboundPlayerCombatEnterPacket.serializer(),
            "",
        )
        assertPacketBytes(
            ClientboundPlayerCombatKillPacket(1, TEXT_X),
            ClientboundPlayerCombatKillPacket.serializer(),
            "0108000178",
        )
        assertPacketBytes(
            ClientboundPlayerInfoRemovePacket(listOf(ZERO_UUID)),
            ClientboundPlayerInfoRemovePacket.serializer(),
            "0100000000000000000000000000000000",
        )
    }

    @Test
    fun `look target writes its entity branch only when selected`() {
        val position = Vector3d(1.0, 2.0, 3.0)
        val positionPacket = ClientboundPlayerLookAtPacket(
            EntityAnchor.EYES,
            LookTarget.Position(position),
        )
        assertPacketBytes(
            positionPacket,
            ClientboundPlayerLookAtPacket.serializer(),
            "01${VECTOR_123_HEX}00",
        )

        val entityPacket = ClientboundPlayerLookAtPacket(
            EntityAnchor.FEET,
            LookTarget.Entity(position, entityId = 300, EntityAnchor.EYES),
        )
        assertPacketBytes(
            entityPacket,
            ClientboundPlayerLookAtPacket.serializer(),
            "00${VECTOR_123_HEX}01ac0201",
        )

    }

    @Test
    fun `player synchronization uses a fixed Int relative bit mask`() {
        val clientboundPlayerPositionPacket = ClientboundPlayerPositionPacket(
            id = 1,
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
            clientboundPlayerPositionPacket,
            ClientboundPlayerPositionPacket.serializer(),
            "01${"00".repeat(56)}00000181",
        )
        assertPacketBytes(
            ClientboundPlayerRotationPacket(
                yRot = 1.0f,
                relativeY = true,
                xRot = -2.0f,
                relativeX = false,
            ),
            ClientboundPlayerRotationPacket.serializer(),
            "3f80000001c000000000",
        )

        val withUnknownBits = "01${"00".repeat(56)}ffffff81".hexToByteArray()
        assertEquals(
            clientboundPlayerPositionPacket,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundPlayerPositionPacket>(
                withUnknownBits,
            ),
        )
    }

    @Test
    fun `recipe and entity ID arrays use VarInt elements`() {
        assertPacketBytes(
            ClientboundRecipeBookRemovePacket(listOf(1, 300)),
            ClientboundRecipeBookRemovePacket.serializer(),
            "0201ac02",
        )
        assertPacketBytes(
            ClientboundRemoveEntitiesPacket(listOf(1, 300)),
            ClientboundRemoveEntitiesPacket.serializer(),
            "0201ac02",
        )
        assertPacketBytes(
            ClientboundRemoveMobEffectPacket(entityId = 1, effect = 300),
            ClientboundRemoveMobEffectPacket.serializer(),
            "01ac02",
        )

        val recipeBookSettings = RecipeBookSettings(
            crafting = RecipeBookTypeSettings(open = true, filtering = false),
            furnace = RecipeBookTypeSettings(open = false, filtering = true),
            blastFurnace = RecipeBookTypeSettings(open = true, filtering = false),
            smoker = RecipeBookTypeSettings(open = false, filtering = true),
        )
        assertPacketBytes(
            ClientboundRecipeBookSettingsPacket(recipeBookSettings),
            ClientboundRecipeBookSettingsPacket.serializer(),
            "0100000101000001",
        )
    }

    @Test
    fun `score and resource-pack optionals are Boolean prefixed`() {
        assertPacketBytes(
            ClientboundResetScorePacket("x", null),
            ClientboundResetScorePacket.serializer(),
            "017800",
        )
        assertPacketBytes(
            ClientboundResetScorePacket("x", "y"),
            ClientboundResetScorePacket.serializer(),
            "0178010179",
        )
        assertPacketBytes(
            ClientboundResourcePackPopPacket(null),
            ClientboundResourcePackPopPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            ClientboundResourcePackPopPacket(ZERO_UUID),
            ClientboundResourcePackPopPacket.serializer(),
            "0100000000000000000000000000000000",
        )
        assertPacketBytes(
            ClientboundResourcePackPushPacket(
                id = ZERO_UUID,
                url = "u",
                hash = "h",
                required = true,
                prompt = TEXT_X,
            ),
            ClientboundResourcePackPushPacket.serializer(),
            "0000000000000000000000000000000001750168010108000178",
        )
    }

    @Test
    fun `section block changes pack state and local coordinates into VarLongs`() {
        val clientboundSectionBlocksUpdatePacket = ClientboundSectionBlocksUpdatePacket(
            sectionPos = SectionPosition(1, 2, 3),
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
            clientboundSectionBlocksUpdatePacket,
            ClientboundSectionBlocksUpdatePacket.serializer(),
            "000004000030000201b2824b",
        )
        assertPacketBytes(
            ClientboundRotateHeadPacket(300, Angle(0xFF.toByte())),
            ClientboundRotateHeadPacket.serializer(),
            "ac02ff",
        )
    }

    @Test
    fun `server presentation packets retain NBT and byte-array boundaries`() {
        assertPacketBytes(
            ClientboundSelectAdvancementsTabPacket(null),
            ClientboundSelectAdvancementsTabPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            ClientboundServerDataPacket(
                motd = TEXT_X,
                iconBytes = ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
            ),
            ClientboundServerDataPacket.serializer(),
            "080001780102aabb",
        )
        assertPacketBytes(
            ClientboundSetActionBarTextPacket(TEXT_X),
            ClientboundSetActionBarTextPacket.serializer(),
            "08000178",
        )
    }

    @Test
    fun `world-border and chunk-cache values keep fixed and variable widths`() {
        assertPacketBytes(
            ClientboundSetBorderCenterPacket(1.0, -2.0),
            ClientboundSetBorderCenterPacket.serializer(),
            "3ff0000000000000c000000000000000",
        )
        assertPacketBytes(
            ClientboundSetBorderLerpSizePacket(1.0, 2.0, 300),
            ClientboundSetBorderLerpSizePacket.serializer(),
            "3ff00000000000004000000000000000ac02",
        )
        assertPacketBytes(
            ClientboundSetBorderSizePacket(3.0),
            ClientboundSetBorderSizePacket.serializer(),
            "4008000000000000",
        )
        assertPacketBytes(
            ClientboundSetBorderWarningDelayPacket(300),
            ClientboundSetBorderWarningDelayPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ClientboundSetBorderWarningDistancePacket(300),
            ClientboundSetBorderWarningDistancePacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ClientboundSetCameraPacket(300),
            ClientboundSetCameraPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ClientboundSetChunkCacheCenterPacket(-1, 300),
            ClientboundSetChunkCacheCenterPacket.serializer(),
            "ffffffff0fac02",
        )
        assertPacketBytes(
            ClientboundSetChunkCacheRadiusPacket(32),
            ClientboundSetChunkCacheRadiusPacket.serializer(),
            "20",
        )
    }

    @Test
    fun `default spawn includes dimension position yaw and pitch`() {
        val clientboundSetDefaultSpawnPositionPacket = ClientboundSetDefaultSpawnPositionPacket(
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
            clientboundSetDefaultSpawnPositionPacket,
            ClientboundSetDefaultSpawnPositionPacket.serializer(),
            "136d696e6563726166743a6f766572776f726c64${ZERO_POSITION_HEX}3f800000c0000000",
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
        const val VECTOR_123_HEX: String = "3ff000000000000040000000000000004008000000000000"
        val ZERO_UUID: Uuid = Uuid.fromLongs(0, 0)
        val TEXT_X: TextComponent = TextComponent(NbtString("x"))
    }
}
