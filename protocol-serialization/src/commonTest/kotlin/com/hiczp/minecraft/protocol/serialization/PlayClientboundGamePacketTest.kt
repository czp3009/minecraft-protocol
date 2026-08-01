package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlayClientboundGamePacketTest {
    @Test
    fun `spawn info uses minus one game-mode sentinel without a Boolean marker`() {
        val base = CommonPlayerSpawnInfo(
            dimensionTypeId = 1,
            dimension = Identifier("minecraft:overworld"),
            seed = 0,
            gameMode = GameMode.CREATIVE,
            previousGameMode = null,
            isDebug = false,
            isFlat = true,
            lastDeathLocation = null,
            portalCooldown = 2,
            seaLevel = 63,
        )
        assertPacketBytes(
            base,
            CommonPlayerSpawnInfo.serializer(),
            "01136d696e6563726166743a6f766572776f726c64000000000000000001ff000100023f",
        )
        assertPacketBytes(
            base.copy(previousGameMode = GameMode.SPECTATOR),
            CommonPlayerSpawnInfo.serializer(),
            "01136d696e6563726166743a6f766572776f726c6400000000000000000103000100023f",
        )
    }

    @Test
    fun `entity event uses fixed Int followed by raw Byte`() {
        assertPacketBytes(
            EntityEventPacket(entityId = 1, eventId = -1),
            EntityEventPacket.serializer(),
            "00000001ff",
        )
    }

    @Test
    fun `entity position sync follows PositionMoveRotation codec order`() {
        assertPacketBytes(
            TeleportEntityPacket(
                entityId = 300,
                values = PositionMoveRotation(
                    position = Vector3d(1.0, 2.0, 3.0),
                    deltaMovement = Vector3d(-1.0, 0.0, 0.5),
                    yaw = 90.0f,
                    pitch = -45.0f,
                ),
                onGround = true,
            ),
            TeleportEntityPacket.serializer(),
            (
                    "ac02" +
                            "3ff0000000000000" +
                            "4000000000000000" +
                            "4008000000000000" +
                            "bff0000000000000" +
                            "0000000000000000" +
                            "3fe0000000000000" +
                            "42b40000" +
                            "c2340000" +
                            "01"
                    ),
        )
    }

    @Test
    fun `packed chunk packets put Z in the high half before X`() {
        assertPacketBytes(
            UnloadChunkPacket(chunkZ = 2, chunkX = 1),
            UnloadChunkPacket.serializer(),
            "0000000200000001",
        )
    }

    @Test
    fun `game event is unsigned byte plus float`() {
        assertPacketBytes(
            GameEventPacket(
                GameEventType.LEVEL_CHUNKS_LOAD_START,
                1.0f,
            ),
            GameEventPacket.serializer(),
            "0d3f800000",
        )
    }

    @Test
    fun `game rules are a VarInt-prefixed identifier string map`() {
        assertPacketBytes(
            GameRuleValuesPacket(
                linkedMapOf(Identifier("minecraft:x") to "true"),
            ),
            GameRuleValuesPacket.serializer(),
            "010b6d696e6563726166743a780474727565",
        )
    }

    @Test
    fun `game test and horse screen fields use their distinct integer forms`() {
        assertPacketBytes(
            GameTestHighlightPositionPacket(
                absolutePosition = BlockPosition(0, 0, 0),
                relativePosition = BlockPosition(1, 2, 3),
            ),
            GameTestHighlightPositionPacket.serializer(),
            "00000000000000000000004000003002",
        )
        assertPacketBytes(
            OpenHorseScreenPacket(
                containerId = 300,
                inventoryColumns = 3,
                entityId = 1,
            ),
            OpenHorseScreenPacket.serializer(),
            "ac020300000001",
        )
    }

    @Test
    fun `hurt border and keep-alive packets retain fixed versus variable widths`() {
        assertPacketBytes(
            HurtAnimationPacket(entityId = 1, yaw = 1.5f),
            HurtAnimationPacket.serializer(),
            "013fc00000",
        )
        assertPacketBytes(
            InitializeWorldBorderPacket(
                centerX = 1.0,
                centerZ = -2.0,
                oldDiameter = 3.0,
                newDiameter = 4.0,
                speedMilliseconds = 300,
                portalTeleportBoundary = 2,
                warningBlocks = 3,
                warningTimeSeconds = 4,
            ),
            InitializeWorldBorderPacket.serializer(),
            (
                    "3ff0000000000000" +
                            "c000000000000000" +
                            "4008000000000000" +
                            "4010000000000000" +
                            "ac02" +
                            "020304"
                    ),
        )
        assertPacketBytes(
            PlayClientboundKeepAlivePacket(0x0102030405060708),
            PlayClientboundKeepAlivePacket.serializer(),
            "0102030405060708",
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
}
