package com.hiczp.minecraft.protocol.model.type

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class ChunkDataTest {
    @Test
    fun heightmapArraysUseContentEqualityWithoutBeingCopied() {
        val heightmap = longArrayOf(1L, 2L)
        val chunkData = ChunkData(
            heightmaps = mapOf(HeightmapType.WORLD_SURFACE to heightmap),
            sections = emptyList(),
            blockEntities = emptyList(),
        )
        val equalChunkData = ChunkData(
            heightmaps = mapOf(HeightmapType.WORLD_SURFACE to longArrayOf(1L, 2L)),
            sections = emptyList(),
            blockEntities = emptyList(),
        )

        assertSame(heightmap, chunkData.heightmaps[HeightmapType.WORLD_SURFACE])
        assertEquals(equalChunkData, chunkData)
        assertEquals(equalChunkData.hashCode(), chunkData.hashCode())
        assertNotEquals(
            ChunkData(
                heightmaps = mapOf(HeightmapType.WORLD_SURFACE to longArrayOf(2L, 1L)),
                sections = emptyList(),
                blockEntities = emptyList(),
            ),
            chunkData,
        )
    }
}
