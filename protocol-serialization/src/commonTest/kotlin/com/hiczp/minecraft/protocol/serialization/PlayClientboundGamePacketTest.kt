package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.model.type.GameMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer

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
            ClientboundEntityEventPacket(entityId = 1, eventId = -1),
            ClientboundEntityEventPacket.serializer(),
            "00000001ff",
        )
    }

    @Test
    fun `entity position sync follows PositionMoveRotation codec order`() {
        assertPacketBytes(
            ClientboundEntityPositionSyncPacket(
                id = 300,
                values = PositionMoveRotation(
                    position = Vector3d(1.0, 2.0, 3.0),
                    deltaMovement = Vector3d(-1.0, 0.0, 0.5),
                    yaw = 90.0f,
                    pitch = -45.0f,
                ),
                onGround = true,
            ),
            ClientboundEntityPositionSyncPacket.serializer(),
            "ac023ff000000000000040000000000000004008000000000000bff000000000000000000000000000003fe000000000000042b40000c234000001",
        )
    }

    @Test
    fun `packed chunk packets put Z in the high half before X`() {
        assertPacketBytes(
            ClientboundForgetLevelChunkPacket(ChunkPos(1, 2)),
            ClientboundForgetLevelChunkPacket.serializer(),
            "0000000200000001",
        )
    }

    @Test
    fun `game event is unsigned byte plus float`() {
        assertPacketBytes(
            ClientboundGameEventPacket(
                GameEventType.LEVEL_CHUNKS_LOAD_START,
                1.0f,
            ),
            ClientboundGameEventPacket.serializer(),
            "0d3f800000",
        )
    }

    @Test
    fun `game rules are a VarInt-prefixed identifier string map`() {
        assertPacketBytes(
            ClientboundGameRuleValuesPacket(
                linkedMapOf(Identifier("minecraft:x") to "true"),
            ),
            ClientboundGameRuleValuesPacket.serializer(),
            "010b6d696e6563726166743a780474727565",
        )
    }

    @Test
    fun `game test and horse screen fields use their distinct integer forms`() {
        assertPacketBytes(
            ClientboundGameTestHighlightPosPacket(
                absolutePos = BlockPosition(0, 0, 0),
                relativePos = BlockPosition(1, 2, 3),
            ),
            ClientboundGameTestHighlightPosPacket.serializer(),
            "00000000000000000000004000003002",
        )
        assertPacketBytes(
            ClientboundMountScreenOpenPacket(
                containerId = 300,
                inventoryColumns = 3,
                entityId = 1,
            ),
            ClientboundMountScreenOpenPacket.serializer(),
            "ac020300000001",
        )
    }

    @Test
    fun `hurt border and keep-alive packets retain fixed versus variable widths`() {
        assertPacketBytes(
            ClientboundHurtAnimationPacket(id = 1, yaw = 1.5f),
            ClientboundHurtAnimationPacket.serializer(),
            "013fc00000",
        )
        assertPacketBytes(
            ClientboundInitializeBorderPacket(
                newCenterX = 1.0,
                newCenterZ = -2.0,
                oldSize = 3.0,
                newSize = 4.0,
                lerpTime = 300,
                newAbsoluteMaxSize = 2,
                warningBlocks = 3,
                warningTime = 4,
            ),
            ClientboundInitializeBorderPacket.serializer(),
            "3ff0000000000000c00000000000000040080000000000004010000000000000ac02020304",
        )
        assertPacketBytes(
            ClientboundKeepAlivePacket(0x0102030405060708),
            ClientboundKeepAlivePacket.serializer(),
            "0102030405060708",
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
