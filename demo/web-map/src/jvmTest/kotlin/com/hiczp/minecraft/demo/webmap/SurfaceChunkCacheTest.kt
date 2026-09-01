package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SurfaceChunkCacheTest {
    @Test
    fun concurrentRequestsShareOneChunkSlotAndCannotWriteItTogether() = runTest {
        val surfaceChunkCache = SurfaceChunkCache()
        val chunkPosition = ChunkPosition(4, 7)
        val firstSlot = surfaceChunkCache.slots(
            DimensionId.Overworld,
            RegionPosition(0, 0),
            listOf(chunkPosition),
        ).single()
        val secondSlot = surfaceChunkCache.slots(
            DimensionId.Overworld,
            RegionPosition(0, 0),
            listOf(chunkPosition),
        ).single()
        var activeWriterCount = 0
        var maximumActiveWriterCount = 0
        val firstWriterEntered = CompletableDeferred<Unit>()
        val releaseFirstWriter = CompletableDeferred<Unit>()

        val firstWriter = launch {
            firstSlot.mutex.withLock {
                activeWriterCount++
                maximumActiveWriterCount = maxOf(maximumActiveWriterCount, activeWriterCount)
                firstWriterEntered.complete(Unit)
                releaseFirstWriter.await()
                firstSlot.entry = CachedSurfaceChunk(1, surface("stone"))
                activeWriterCount--
            }
        }
        firstWriterEntered.await()
        val secondWriter = launch {
            secondSlot.mutex.withLock {
                activeWriterCount++
                maximumActiveWriterCount = maxOf(maximumActiveWriterCount, activeWriterCount)
                secondSlot.entry = CachedSurfaceChunk(2, surface("dirt"))
                activeWriterCount--
            }
        }
        releaseFirstWriter.complete(Unit)
        firstWriter.join()
        secondWriter.join()

        assertSame(firstSlot, secondSlot)
        assertEquals(1, maximumActiveWriterCount)
        assertEquals(2, firstSlot.entry?.timestampEpochSeconds)
    }

    private fun surface(blockName: String): ChunkSurface = ChunkSurface(
        palette = listOf(SurfaceColumn(listOf(SurfaceBlockState(Identifier(blockName))))),
        cells = List(SURFACE_CELL_COUNT) { 0 },
    )
}
