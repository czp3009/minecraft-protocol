package com.hiczp.minecraft.protocol.model

import com.hiczp.minecraft.protocol.model.packet.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ClientboundBundlePacketTest {
    @Test
    fun bundleRetainsSubPacketsAndRejectsWireStructure() {
        val source = mutableListOf<ClientboundPacket>(ChunkBatchStartPacket)
        val clientboundBundlePacket = ClientboundBundlePacket(source)
        source += ChunkBatchFinishedPacket(1)

        assertSame(source, clientboundBundlePacket.subPackets)
        assertEquals(source, clientboundBundlePacket.subPackets)
        assertFailsWith<IllegalArgumentException> {
            ClientboundBundlePacket(listOf(BundleDelimiterPacket))
        }
        assertFailsWith<IllegalArgumentException> {
            ClientboundBundlePacket(listOf(clientboundBundlePacket))
        }
    }

    @Test
    fun bundleEnforcesTheOfficialSubPacketLimit() {
        val packets = List(ClientboundBundlePacket.MAX_SUB_PACKET_COUNT + 1) { ChunkBatchStartPacket }

        assertFailsWith<IllegalArgumentException> {
            ClientboundBundlePacket(packets)
        }
    }
}
