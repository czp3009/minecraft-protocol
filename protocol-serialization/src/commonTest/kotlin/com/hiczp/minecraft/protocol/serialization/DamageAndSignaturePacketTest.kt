package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.DamageEventPacket
import com.hiczp.minecraft.protocol.model.packet.DebugSamplePacket
import com.hiczp.minecraft.protocol.model.packet.DebugSampleType
import com.hiczp.minecraft.protocol.model.packet.DeleteMessagePacket
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.PackedMessageSignature
import com.hiczp.minecraft.protocol.model.type.Vector3d
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
        val expected = (
                "01020005" +
                        "01" +
                        "3ff0000000000000" +
                        "4000000000000000" +
                        "4008000000000000"
                ).hexBytes()
        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(DamageEventPacket.serializer(), packet),
        )
        assertEquals(
            packet,
            MinecraftFormat.decodeFromByteArray(DamageEventPacket.serializer(), expected),
        )
    }

    @Test
    fun `debug sample contains prefixed fixed-width longs then a VarInt enum`() {
        val packet = DebugSamplePacket(
            listOf(1, 2, 3, 4),
            DebugSampleType.TICK_TIME,
        )
        val expected = (
                "04" +
                        "0000000000000001" +
                        "0000000000000002" +
                        "0000000000000003" +
                        "0000000000000004" +
                        "00"
                ).hexBytes()
        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(DebugSamplePacket.serializer(), packet),
        )
        assertEquals(
            packet,
            MinecraftFormat.decodeFromByteArray(DebugSamplePacket.serializer(), expected),
        )
    }

    @Test
    fun `packed message signature chooses cache ID or exact 256 raw bytes`() {
        val cached = DeleteMessagePacket(PackedMessageSignature.Cached(3))
        assertContentEquals(
            "04".hexBytes(),
            MinecraftFormat.encodeToByteArray(DeleteMessagePacket.serializer(), cached),
        )
        assertEquals(
            cached,
            MinecraftFormat.decodeFromByteArray(
                DeleteMessagePacket.serializer(),
                "04".hexBytes(),
            ),
        )

        val signature = ByteArray(PackedMessageSignature.SIGNATURE_BYTES) {
            it.toByte()
        }
        val full = DeleteMessagePacket(
            PackedMessageSignature.Full(ByteString(signature)),
        )
        val encoded = MinecraftFormat.encodeToByteArray(
            DeleteMessagePacket.serializer(),
            full,
        )
        assertEquals(257, encoded.size)
        assertEquals(0, encoded.first().toInt())
        assertContentEquals(signature, encoded.copyOfRange(1, encoded.size))
        assertEquals(
            full,
            MinecraftFormat.decodeFromByteArray(
                DeleteMessagePacket.serializer(),
                encoded,
            ),
        )
    }
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
