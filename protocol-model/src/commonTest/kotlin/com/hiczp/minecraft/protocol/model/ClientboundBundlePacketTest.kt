package com.hiczp.minecraft.protocol.model

import com.hiczp.minecraft.protocol.model.packet.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ClientboundBundlePacketTest {
    @Test
    fun bundleRetainsSubPacketsWithoutApplyingSessionPolicy() {
        val source = mutableListOf<ClientboundPacket>(ClientboundChunkBatchStartPacket)
        val clientboundBundlePacket = ClientboundBundlePacket(source)
        source += ClientboundChunkBatchFinishedPacket(1)

        assertSame(source, clientboundBundlePacket.subPackets)
        assertEquals(source, clientboundBundlePacket.subPackets)
        assertEquals(
            ClientboundBundleDelimiterPacket,
            ClientboundBundlePacket(listOf(ClientboundBundleDelimiterPacket)).single()
        )
        assertSame(clientboundBundlePacket, ClientboundBundlePacket(listOf(clientboundBundlePacket)).single())
    }

    @Test
    fun bundleRetainsMoreThanTheSessionSubPacketLimit() {
        val packets = List(ClientboundBundlePacket.MAX_SUB_PACKET_COUNT + 1) { ClientboundChunkBatchStartPacket }

        assertSame(packets, ClientboundBundlePacket(packets).subPackets)
    }
}
