package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SurfaceQueryEngineTest {
    @Test
    fun readsDifferentRegionsConcurrently() = runTest {
        var activeReadCount = 0
        var maximumActiveReadCount = 0
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = mapOf(DimensionId.Overworld to SurfaceChunkProjector { error("No present Chunk expected") }),
            surfaceRegionReader = SurfaceRegionReader { _, _, chunkPositions ->
                activeReadCount++
                maximumActiveReadCount = maxOf(maximumActiveReadCount, activeReadCount)
                yield()
                activeReadCount--
                RegionReadOutcome.Success(chunkPositions.associateWith { ChunkReadOutcome.Missing })
            },
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
        )

        surfaceQueryEngine.query(SurfaceRequest(DimensionId.Overworld, ChunkViewport(31, 0, 32, 0)))

        assertEquals(2, maximumActiveReadCount)
    }

    @Test
    fun groupsByRegionAndIsolatesRegionAndChunkFailures() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<Pair<RegionPosition, List<ChunkPosition>>>())
        val surfaceRegionReader = SurfaceRegionReader { _, regionPosition, chunkPositions ->
            calls += regionPosition to chunkPositions
            when (regionPosition) {
                RegionPosition(0, 0) -> RegionReadOutcome.Success(
                    mapOf(
                        ChunkPosition(30, 0) to ChunkReadOutcome.Missing,
                        ChunkPosition(31, 0) to ChunkReadOutcome.Failed(IllegalStateException("payload")),
                    ),
                )

                RegionPosition(1, 0) -> RegionReadOutcome.Failed(IllegalStateException("header"))
                else -> error("Unexpected Region $regionPosition")
            }
        }
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = mapOf(DimensionId.Overworld to SurfaceChunkProjector { error("No present Chunk expected") }),
            surfaceRegionReader = surfaceRegionReader,
        )

        val result = surfaceQueryEngine.query(
            SurfaceRequest(DimensionId.Overworld, ChunkViewport(30, 0, 33, 0)),
        )

        val response = assertIs<SurfaceQueryResult.Success>(result).response
        assertEquals(
            listOf(
                SurfaceChunkResult.ReadFailed(31, 0),
                SurfaceChunkResult.ReadFailed(32, 0),
                SurfaceChunkResult.ReadFailed(33, 0),
            ),
            response.chunks,
        )
        assertEquals(
            setOf(
                RegionPosition(0, 0) to listOf(ChunkPosition(30, 0), ChunkPosition(31, 0)),
                RegionPosition(1, 0) to listOf(ChunkPosition(32, 0), ChunkPosition(33, 0)),
            ),
            calls.toSet(),
        )
    }

    @Test
    fun rejectsUnknownDimensionAndOversizedViewportWithoutReading() = runTest {
        var readCount = 0
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = mapOf(DimensionId.Overworld to SurfaceChunkProjector { error("No Chunk expected") }),
            surfaceRegionReader = SurfaceRegionReader { _, _, _ ->
                readCount++
                error("No Region expected")
            },
        )

        assertEquals(
            SurfaceQueryResult.Rejected(SurfaceQueryRejection.UNKNOWN_DIMENSION),
            surfaceQueryEngine.query(SurfaceRequest(DimensionId.Nether, ChunkViewport(0, 0, 0, 0))),
        )
        assertEquals(
            SurfaceQueryResult.Rejected(SurfaceQueryRejection.RANGE_TOO_LARGE),
            surfaceQueryEngine.query(SurfaceRequest(DimensionId.Overworld, ChunkViewport(0, 0, 256, 0))),
        )
        assertEquals(0, readCount)
    }

    @Test
    fun preservesCancellationFromRegionReader() = runTest {
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = mapOf(DimensionId.Overworld to SurfaceChunkProjector { error("No Chunk expected") }),
            surfaceRegionReader = SurfaceRegionReader { _, _, _ -> throw CancellationException("cancelled") },
        )

        assertFailsWith<CancellationException> {
            surfaceQueryEngine.query(SurfaceRequest(DimensionId.Overworld, ChunkViewport(0, 0, 0, 0)))
        }
    }
}
