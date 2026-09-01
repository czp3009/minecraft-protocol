package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class SurfaceQueryEngineTest {
    private val opaqueBlocks = SurfaceBlockTransparency { false }
    private val emptySurface = ChunkSurface(emptyList(), List(SURFACE_CELL_COUNT) { null })

    @Test
    fun groupsChunksByRegionAndOmitsMissingChunksWhileReadingRegionsConcurrently() = runTest {
        var activeReadCount = 0
        var maximumActiveReadCount = 0
        val calls = mutableListOf<Pair<RegionPosition, List<ChunkPosition>>>()
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = projectors(),
            surfaceRegionReader = { _, regionPosition, chunkPositions, _, _ ->
                calls += regionPosition to chunkPositions
                activeReadCount++
                maximumActiveReadCount = maxOf(maximumActiveReadCount, activeReadCount)
                yield()
                activeReadCount--
                SurfaceRegionReadOutcome.Success(
                    chunkPositions.associateWith { SurfaceChunkReadOutcome.Missing },
                )
            },
            loadBlockTransparency = { opaqueBlocks },
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
        )

        val updates = surfaceQueryEngine.query(
            SurfaceRequest(DimensionId.Overworld, ChunkViewport(31, 0, 32, 0)),
        ).toList()

        assertEquals(2, maximumActiveReadCount)
        assertEquals(
            setOf(
                RegionPosition(0, 0) to listOf(ChunkPosition(31, 0)),
                RegionPosition(1, 0) to listOf(ChunkPosition(32, 0)),
            ),
            calls.toSet(),
        )
        assertEquals(emptyList(), updates)
    }

    @Test
    fun retriesOnlyFailedChunksUntilTheirFailureUpdatesDisappear() = runTest {
        val calls = mutableListOf<Pair<RegionPosition, List<ChunkPosition>>>()
        val regionAttempts = mutableMapOf<RegionPosition, Int>()
        val surfaceRegionReader = SurfaceRegionReader { _, regionPosition, chunkPositions, _, _ ->
            calls += regionPosition to chunkPositions
            val attempt = regionAttempts.getOrDefault(regionPosition, 0)
            regionAttempts[regionPosition] = attempt + 1
            when (regionPosition to attempt) {
                RegionPosition(0, 0) to 0 -> SurfaceRegionReadOutcome.Success(
                    mapOf(
                        ChunkPosition(30, 0) to SurfaceChunkReadOutcome.Missing,
                        ChunkPosition(31, 0) to SurfaceChunkReadOutcome.Failed,
                    ),
                )

                RegionPosition(0, 0) to 1 -> SurfaceRegionReadOutcome.Success(
                    mapOf(ChunkPosition(31, 0) to SurfaceChunkReadOutcome.Present(11, emptySurface)),
                )

                RegionPosition(1, 0) to 0 -> SurfaceRegionReadOutcome.Failed
                RegionPosition(1, 0) to 1 -> SurfaceRegionReadOutcome.Success(
                    mapOf(
                        ChunkPosition(32, 0) to SurfaceChunkReadOutcome.Missing,
                        ChunkPosition(33, 0) to SurfaceChunkReadOutcome.Present(12, emptySurface),
                    ),
                )

                else -> error("Unexpected Region attempt $regionPosition/$attempt")
            }
        }
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = projectors(),
            surfaceRegionReader = surfaceRegionReader,
            loadBlockTransparency = { opaqueBlocks },
            coroutineDispatcher = StandardTestDispatcher(testScheduler),
            readRetryDelay = Duration.ZERO,
        )

        val updates = surfaceQueryEngine.query(
            SurfaceRequest(DimensionId.Overworld, ChunkViewport(30, 0, 33, 0)),
        ).toList()

        assertEquals(
            listOf(
                listOf(ChunkPosition(30, 0), ChunkPosition(31, 0)),
                listOf(ChunkPosition(31, 0)),
            ),
            calls.filter { (regionPosition) -> regionPosition == RegionPosition(0, 0) }.map { it.second },
        )
        assertEquals(
            listOf(
                listOf(ChunkPosition(32, 0), ChunkPosition(33, 0)),
                listOf(ChunkPosition(32, 0), ChunkPosition(33, 0)),
            ),
            calls.filter { (regionPosition) -> regionPosition == RegionPosition(1, 0) }.map { it.second },
        )
        assertEquals(
            setOf(
                SurfaceChunkResult.ReadFailed(31, 0),
                SurfaceChunkResult.ReadFailed(32, 0),
                SurfaceChunkResult.ReadFailed(33, 0),
                SurfaceChunkResult.Success(31, 0, 11, emptySurface),
                SurfaceChunkResult.Success(33, 0, 12, emptySurface),
            ),
            updates.map { update -> (update as SurfaceQueryUpdate.Chunk).result }.toSet(),
        )
    }

    @Test
    fun rejectsUnknownDimensionAndOversizedViewportWithoutLoadingAssetsOrReading() = runTest {
        var readCount = 0
        var assetLoadCount = 0
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = projectors(),
            surfaceRegionReader = { _, _, _, _, _ ->
                readCount++
                error("No Region expected")
            },
            loadBlockTransparency = {
                assetLoadCount++
                opaqueBlocks
            },
        )

        assertEquals(
            listOf(SurfaceQueryUpdate.Rejected(SurfaceQueryRejection.UNKNOWN_DIMENSION)),
            surfaceQueryEngine.query(
                SurfaceRequest(DimensionId.Nether, ChunkViewport(0, 0, 0, 0)),
            ).toList(),
        )
        assertEquals(
            listOf(SurfaceQueryUpdate.Rejected(SurfaceQueryRejection.RANGE_TOO_LARGE)),
            surfaceQueryEngine.query(
                SurfaceRequest(DimensionId.Overworld, ChunkViewport(0, 0, 256, 0)),
            ).toList(),
        )
        assertEquals(0, readCount)
        assertEquals(0, assetLoadCount)
    }

    @Test
    fun preservesCancellationFromRegionReader() = runTest {
        val surfaceQueryEngine = SurfaceQueryEngine(
            surfaceChunkProjectors = projectors(),
            surfaceRegionReader = { _, _, _, _, _ -> throw CancellationException("cancelled") },
            loadBlockTransparency = { opaqueBlocks },
        )

        assertFailsWith<CancellationException> {
            surfaceQueryEngine.query(
                SurfaceRequest(DimensionId.Overworld, ChunkViewport(0, 0, 0, 0)),
            ).toList()
        }
    }

    private fun projectors(): Map<DimensionId, SurfaceChunkProjector> = mapOf(
        DimensionId.Overworld to SurfaceChunkProjector { _, _ -> error("No present Chunk projection expected") },
    )
}
