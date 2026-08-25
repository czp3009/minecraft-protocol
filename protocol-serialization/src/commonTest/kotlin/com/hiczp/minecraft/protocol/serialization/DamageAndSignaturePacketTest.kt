package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.DamageEventPacket
import com.hiczp.minecraft.protocol.model.packet.DebugSamplePacket
import com.hiczp.minecraft.protocol.model.packet.DebugSampleType
import com.hiczp.minecraft.protocol.model.packet.DeleteMessagePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.PackedMessageSignature
import com.hiczp.minecraft.protocol.model.type.Vector3d
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DamageAndSignaturePacketTest {
    @Test
    fun `damage optional IDs use value plus one without a boolean prefix`() {
        val packet = DamageEventPacket(
            entityId = 1,
            sourceTypeId = 2,
            sourceCauseEntityId = null,
            sourceDirectEntityId = 4,
            sourcePosition = Vector3d(1.0, 2.0, 3.0),
        )
        val expected = "01020005013ff000000000000040000000000000004008000000000000".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(packet),
        )
        assertEquals(
            expected = packet,
            actual = MinecraftProtocolFormat.decodeFromByteArray<DamageEventPacket>(expected),
        )
    }

    @Test
    fun `debug sample contains prefixed fixed-width longs then a VarInt enum`() {
        val packet = DebugSamplePacket(
            listOf(1, 2, 3, 4),
            DebugSampleType.TICK_TIME,
        )
        val expected = "04000000000000000100000000000000020000000000000003000000000000000400".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(packet),
        )
        assertEquals(
            expected = packet,
            actual = MinecraftProtocolFormat.decodeFromByteArray<DebugSamplePacket>(expected),
        )
    }

    @Test
    fun `packed message signature chooses cache ID or exact 256 raw bytes`() {
        val cached = DeleteMessagePacket(PackedMessageSignature.Cached(3))
        assertContentEquals(
            "04".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(cached),
        )
        assertEquals(
            cached,
            MinecraftProtocolFormat.decodeFromByteArray<DeleteMessagePacket>(
                "04".hexToByteArray(),
            ),
        )

        val signature = ByteArray(PackedMessageSignature.SIGNATURE_BYTES) {
            it.toByte()
        }
        val full = DeleteMessagePacket(
            PackedMessageSignature.Full(ByteString(signature)),
        )
        val encoded = MinecraftProtocolFormat.encodeToByteArray(
            full,
        )
        assertEquals(257, encoded.size)
        assertEquals(0, encoded.first().toInt())
        assertContentEquals(signature, encoded.copyOfRange(1, encoded.size))
        assertEquals(
            full,
            MinecraftProtocolFormat.decodeFromByteArray<DeleteMessagePacket>(
                encoded,
            ),
        )
    }
}
