package com.hiczp.minecraft.protocol.model

import com.hiczp.minecraft.protocol.model.packet.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientboundBundlePacketTest {
    @Test
    fun bundleSnapshotsSubPacketsAndRejectsWireStructure() {
        val source = mutableListOf<ClientboundPacket>(ChunkBatchStartPacket)
        val clientboundBundlePacket = ClientboundBundlePacket(source)
        source += ChunkBatchFinishedPacket(1)

        assertEquals(listOf(ChunkBatchStartPacket), clientboundBundlePacket.subPackets)
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
