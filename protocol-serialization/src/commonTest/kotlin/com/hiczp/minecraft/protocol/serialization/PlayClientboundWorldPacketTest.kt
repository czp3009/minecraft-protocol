package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class PlayClientboundWorldPacketTest {
    @Test
    fun `spawn entity follows the 26_2 field order and LpVec3 codec`() {
        val clientboundAddEntityPacket = ClientboundAddEntityPacket(
            id = 1,
            uuid = ZERO_UUID,
            type = 2,
            x = 1.0,
            y = -2.0,
            z = 0.0,
            movement = Vector3d(0.0, 0.0, 0.0),
            xRot = Angle(1),
            yRot = Angle(0xFE.toByte()),
            yHeadRot = Angle(0x7F),
            data = 300,
        )
        val expected =
            "0100000000000000000000000000000000023ff0000000000000c00000000000000000000000000000000001fe7fac02".hexToByteArray()

        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(clientboundAddEntityPacket),
        )
        assertEquals(
            expected = clientboundAddEntityPacket,
            actual = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundAddEntityPacket>(expected),
        )
    }

    @Test
    fun `boss bar union writes every official discriminator and unsigned flags`() {
        val actions = listOf(
            BossBarAction.Add(
                title = TextComponent.literal("x"),
                health = 1.0f,
                color = BossBarColor.BLUE,
                division = BossBarDivision.TEN_NOTCHES,
                flags = 255,
            ) to "00080001783f8000000102ff",
            BossBarAction.Remove to "01",
            BossBarAction.UpdateHealth(0.5f) to "023f000000",
            BossBarAction.UpdateTitle(TextComponent.literal("y")) to "0308000179",
            BossBarAction.UpdateStyle(
                BossBarColor.WHITE,
                BossBarDivision.TWENTY_NOTCHES,
            ) to "040604",
            BossBarAction.UpdateFlags(255) to "05ff",
        )

        for ((bossBarAction, actionHex) in actions) {
            val clientboundBossEventPacket = ClientboundBossEventPacket(ZERO_UUID, bossBarAction)
            val expected = "00000000000000000000000000000000$actionHex".hexToByteArray()
            assertContentEquals(
                expected,
                MinecraftPacketPayloadFormat.encodeToByteArray(clientboundBossEventPacket),
            )
            assertEquals(
                expected = clientboundBossEventPacket,
                actual = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundBossEventPacket>(expected),
            )
        }
    }

    @Test
    fun `difficulty is VarInt and wraps out of range IDs like vanilla`() {
        val clientboundChangeDifficultyPacket = ClientboundChangeDifficultyPacket(Difficulty.HARD, locked = false)
        assertContentEquals(
            "0300".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(
                clientboundChangeDifficultyPacket,
            ),
        )
        assertEquals(
            clientboundChangeDifficultyPacket,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundChangeDifficultyPacket>(
                "ff0100".hexToByteArray(),
            ),
        )
    }

    @Test
    fun `chunk biome data uses packed Z then X and enforces vanilla byte limit`() {
        val clientboundChunksBiomesPacket = ClientboundChunksBiomesPacket(
            listOf(
                ClientboundChunksBiomesPacket.ChunkBiomeData(
                    pos = ChunkPos(1, 2),
                    buffer = ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
                ),
            ),
        )
        val expected = "01000000020000000102aabb".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(clientboundChunksBiomesPacket),
        )
        assertEquals(
            expected = clientboundChunksBiomesPacket,
            actual = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundChunksBiomesPacket>(expected),
        )
    }

    @Test
    fun `block entity requires the compound NBT used by the official codec`() {
        val clientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket(
            BlockPosition(0, 0, 0),
            type = 1,
            tag = NbtCompound(mapOf("key" to NbtString("value"))),
        )
        val encoded = MinecraftPacketPayloadFormat.encodeToByteArray(clientboundBlockEntityDataPacket)
        assertEquals(
            clientboundBlockEntityDataPacket,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundBlockEntityDataPacket>(encoded),
        )
        assertFailsWith<SerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundBlockEntityDataPacket>(
                // Position, type ID, then a no-name NBT Int instead of Compound.
                "0000000000000000010300000000".hexToByteArray(),
            )
        }
    }

    private companion object {
        val ZERO_UUID: Uuid = Uuid.fromLongs(0, 0)
    }
}
