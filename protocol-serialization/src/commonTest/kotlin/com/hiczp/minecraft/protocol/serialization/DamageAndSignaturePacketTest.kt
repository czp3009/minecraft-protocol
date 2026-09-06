package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.ClientboundDamageEventPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundDebugSamplePacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundDeleteChatPacket
import com.hiczp.minecraft.protocol.model.packet.DebugSampleType
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.PackedMessageSignature
import com.hiczp.minecraft.protocol.model.type.Vector3d
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DamageAndSignaturePacketTest {
    @Test
    fun `damage optional IDs use value plus one without a boolean prefix`() {
        val clientboundDamageEventPacket = ClientboundDamageEventPacket(
            entityId = 1,
            sourceType = 2,
            sourceCauseId = null,
            sourceDirectId = 4,
            sourcePosition = Vector3d(1.0, 2.0, 3.0),
        )
        val expected = "01020005013ff000000000000040000000000000004008000000000000".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(clientboundDamageEventPacket),
        )
        assertEquals(
            expected = clientboundDamageEventPacket,
            actual = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundDamageEventPacket>(expected),
        )
    }

    @Test
    fun `debug sample contains prefixed fixed-width longs then a VarInt enum`() {
        val clientboundDebugSamplePacket = ClientboundDebugSamplePacket(
            listOf(1, 2, 3, 4),
            DebugSampleType.TICK_TIME,
        )
        val expected = "04000000000000000100000000000000020000000000000003000000000000000400".hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(clientboundDebugSamplePacket),
        )
        assertEquals(
            expected = clientboundDebugSamplePacket,
            actual = MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundDebugSamplePacket>(expected),
        )
    }

    @Test
    fun `packed message signature chooses cache ID or exact 256 raw bytes`() {
        val cached = ClientboundDeleteChatPacket(PackedMessageSignature.Cached(3))
        assertContentEquals(
            "04".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(cached),
        )
        assertEquals(
            cached,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundDeleteChatPacket>(
                "04".hexToByteArray(),
            ),
        )

        val signature = ByteArray(PackedMessageSignature.SIGNATURE_BYTES) {
            it.toByte()
        }
        val full = ClientboundDeleteChatPacket(
            PackedMessageSignature.Full(ByteString(signature)),
        )
        val encoded = MinecraftPacketPayloadFormat.encodeToByteArray(
            full,
        )
        assertEquals(257, encoded.size)
        assertEquals(0, encoded.first().toInt())

        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundDeleteChatPacket(PackedMessageSignature.Full(ByteString(ByteArray(255)))),
            )
        }
        assertContentEquals(signature, encoded.copyOfRange(1, encoded.size))
        assertEquals(
            full,
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundDeleteChatPacket>(
                encoded,
            ),
        )
    }
}
