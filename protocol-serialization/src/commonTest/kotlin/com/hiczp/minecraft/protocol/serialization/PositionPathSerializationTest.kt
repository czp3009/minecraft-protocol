package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.ClientboundEntityPositionSyncPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundMoveEntityPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PositionPathSerializationTest {
    @Test
    fun `relative steps pack count and ground state and put tick offsets before deltas`() {
        val delta = VecDelta.Stepped(
            listOf(VecDelta.Stepped.DeltaStep(-2, 3, 4, 1), VecDelta.Stepped.DeltaStep(5, 6, 7, 300)),
        )
        assertBytes(
            ClientboundMoveEntityPacket.Pos(1, delta, true),
            ClientboundMoveEntityPacket.Pos.serializer(),
            "010501fffe00030004ac02000500060007",
        )
        assertBytes(
            ClientboundMoveEntityPacket.PosRot(300, delta, Angle(0x80.toByte()), Angle(0x7f), false),
            ClientboundMoveEntityPacket.PosRot.serializer(),
            "ac020401fffe00030004ac02000500060007807f",
        )
    }

    @Test
    fun `absolute steps carry their own count and derive the endpoint`() {
        val steps = listOf(PositionStep(Vector3d(0.0, 0.0, 0.0), 0), PositionStep(Vector3d(1.0, -2.0, 3.0), 5))
        val position = PositionPath.Stepped(steps)
        assertEquals(steps.last().position, position.endPosition)
        assertBytes(
            ClientboundEntityPositionSyncPacket(1, position, 90.0f, -45.0f, true),
            ClientboundEntityPositionSyncPacket.serializer(),
            "010102000000000000000000000000000000000000000000000000003ff0000000000000c00000000000000040080000000000000542b40000c234000001",
        )
    }

    @Test
    fun `step payloads reject impossible counts and truncation before allocating a list`() {
        for (hex in listOf("0102", "01ffffffff0f", "0102010001000200")) {
            assertFails {
                MinecraftPacketPayloadFormat.decodeFromByteArray(
                    ClientboundMoveEntityPacket.Pos.serializer(),
                    hex.hexToByteArray()
                )
            }
        }
        assertFails { VecDelta.Stepped(emptyList()) }
        assertFails { PositionPath.Stepped(emptyList()) }
        assertFails {
            MinecraftPacketPayloadFormat.decodeFromByteArray(PositionPath.serializer(), "0100".hexToByteArray())
        }
    }

    @Test
    fun `compact bit sets use little endian bytes and omit trailing zeros`() {
        assertBytes(BitSet(longArrayOf(Long.MIN_VALUE, 0x101)), BitSet.serializer(), "0a00000000000000800101")
        assertBytes(BitSet(longArrayOf(0, 0)), BitSet.serializer(), "00")
        assertEquals(BitSet(longArrayOf(1)), BitSet(longArrayOf(1, 0)))
        assertFails { MinecraftPacketPayloadFormat.decodeFromByteArray(BitSet.serializer(), "03ff".hexToByteArray()) }
    }

    private fun <T> assertBytes(value: T, serializer: KSerializer<T>, hex: String) {
        val bytes = hex.hexToByteArray()
        assertContentEquals(bytes, MinecraftPacketPayloadFormat.encodeToByteArray(serializer, value))
        assertEquals(value, MinecraftPacketPayloadFormat.decodeFromByteArray(serializer, bytes))
    }
}
