package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.packet.ClientboundLevelChunkPacketData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class ChunkDataTest {
    @Test
    fun heightmapArraysUseContentEqualityWithoutBeingCopied() {
        val heightmap = longArrayOf(1L, 2L)
        val chunkData = ClientboundLevelChunkPacketData(
            heightmaps = mapOf(HeightmapType.WORLD_SURFACE to heightmap),
            buffer = ByteString(byteArrayOf()),
            blockEntitiesData = emptyList(),
        )
        val equalChunkData = ClientboundLevelChunkPacketData(
            heightmaps = mapOf(HeightmapType.WORLD_SURFACE to longArrayOf(1L, 2L)),
            buffer = ByteString(byteArrayOf()),
            blockEntitiesData = emptyList(),
        )

        assertSame(heightmap, chunkData.heightmaps[HeightmapType.WORLD_SURFACE])
        assertEquals(equalChunkData, chunkData)
        assertEquals(equalChunkData.hashCode(), chunkData.hashCode())
        assertNotEquals(
            ClientboundLevelChunkPacketData(
                heightmaps = mapOf(HeightmapType.WORLD_SURFACE to longArrayOf(2L, 1L)),
                buffer = ByteString(byteArrayOf()),
                blockEntitiesData = emptyList(),
            ),
            chunkData,
        )
    }
}
