package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ViewportControllerTest {
    @Test
    fun pendingViewportKeepsThePreviouslyCommittedSurface() = runTest {
        val firstSurface = surface(Identifier("stone"))
        val secondSurface = surface(Identifier("grass_block"))
        val service = DeferredWebMapService()
        val states = mutableListOf<ViewportRenderState>()
        val controller = controller(this, service, states::add)
        val firstSelection = selection(0)
        val secondSelection = selection(1)

        controller.select(firstSelection)
        runCurrent()
        service.completeNext(success(firstSelection, firstSurface))
        advanceUntilIdle()
        assertEquals(firstSurface, controller.state.surfaces.getValue(ChunkCoordinate(0, 0)))

        controller.select(secondSelection)
        runCurrent()

        assertTrue(controller.state.loading)
        assertEquals(firstSelection, controller.state.displayedSelection)
        assertEquals(firstSurface, controller.state.surfaces.getValue(ChunkCoordinate(0, 0)))

        service.completeNext(success(secondSelection, secondSurface))
        advanceUntilIdle()
        assertEquals(secondSelection, controller.state.displayedSelection)
        assertEquals(secondSurface, controller.state.surfaces.getValue(ChunkCoordinate(1, 0)))
    }

    @Test
    fun changedViewportCancelsAndIgnoresTheOlderGeneration() = runTest {
        val service = DeferredWebMapService()
        val controller = controller(this, service) {}
        val firstSelection = selection(0)
        val secondSelection = selection(1)

        controller.select(firstSelection)
        runCurrent()
        val firstCall = service.calls.single()
        controller.select(secondSelection)
        runCurrent()
        assertTrue(firstCall.cancelled)

        service.completeNext(success(secondSelection, surface(Identifier("dirt"))))
        advanceUntilIdle()

        assertEquals(secondSelection, controller.state.displayedSelection)
        assertFalse(controller.state.surfaces.containsKey(ChunkCoordinate(0, 0)))
    }

    @Test
    fun dimensionChangeImmediatelyClearsTheCommittedRenderState() = runTest {
        val service = DeferredWebMapService()
        val controller = controller(this, service) {}
        val overworldSelection = selection(0)
        val netherSelection = selection(0, DimensionId.Nether)

        controller.select(overworldSelection)
        runCurrent()
        service.completeNext(success(overworldSelection, surface(Identifier("grass_block"))))
        advanceUntilIdle()

        controller.select(netherSelection)
        runCurrent()

        assertTrue(controller.state.loading)
        assertNull(controller.state.displayedSelection)
        assertTrue(controller.state.surfaces.isEmpty())
        controller.close()
    }

    @Test
    fun repeatedResizeSelectionRestartsThePendingDebounce() = runTest {
        val cancelledSelections = mutableListOf<ViewportSelection>()
        val service = DeferredWebMapService()
        val selected = selection(0)
        val controller = ViewportController(
            webMapService = service,
            coroutineScope = this,
            requestScheduler = RequestScheduler { awaitCancellation() },
            requestCancelled = cancelledSelections::add,
            stateChanged = {},
        )

        controller.select(selected)
        runCurrent()
        controller.select(selected, restartDebounce = true)
        runCurrent()

        assertEquals(listOf(selected), cancelledSelections)
        assertTrue(service.calls.isEmpty())
        controller.close()
    }

    @Test
    fun failedChunksUseSingleChunkRepairsAndPreserveOldValuesUntilSuccess() = runTest {
        val oldSurface = surface(Identifier("stone"))
        val repairedSurface = surface(Identifier("diamond_block"))
        val service = QueueWebMapService(
            ArrayDeque(
                listOf(
                    success(selection(0), oldSurface),
                    SurfaceQueryResult.Success(
                        SurfaceResponse(0, 0, 0, 0, listOf(SurfaceChunkResult.ReadFailed(0, 0))),
                    ),
                    SurfaceQueryResult.Success(
                        SurfaceResponse(0, 0, 0, 0, listOf(SurfaceChunkResult.Success(0, 0, repairedSurface))),
                    ),
                ),
            ),
        )
        val controller = controller(this, service) {}

        controller.select(selection(0))
        advanceUntilIdle()
        controller.select(selection(0, dimensionId = DimensionId.Nether))
        advanceUntilIdle()

        assertEquals(3, service.requests.size)
        assertEquals(ChunkViewport.single(ChunkCoordinate(0, 0)), service.requests.last().chunkViewport)
        assertEquals(repairedSurface, controller.state.surfaces.getValue(ChunkCoordinate(0, 0)))
        assertTrue(controller.state.readFailedCoordinates.isEmpty())
    }

    private fun controller(
        coroutineScope: CoroutineScope,
        webMapService: WebMapService,
        stateChanged: (ViewportRenderState) -> Unit,
    ): ViewportController = ViewportController(
        webMapService = webMapService,
        coroutineScope = coroutineScope,
        requestScheduler = RequestScheduler { _: Duration -> },
        retryPolicy = SurfaceRetryPolicy(maximumAttempts = 2),
        debounceDuration = Duration.ZERO,
        stateChanged = stateChanged,
    )

    private fun selection(chunkX: Int, dimensionId: DimensionId = DimensionId.Overworld): ViewportSelection =
        ViewportSelection(dimensionId, ChunkViewport(chunkX, 0, chunkX, 0))

    private fun success(viewportSelection: ViewportSelection, chunkSurface: ChunkSurface): SurfaceQueryResult.Success {
        val chunkViewport = viewportSelection.chunkViewport
        return SurfaceQueryResult.Success(
            SurfaceResponse(
                minChunkX = chunkViewport.minChunkX,
                minChunkZ = chunkViewport.minChunkZ,
                maxChunkX = chunkViewport.maxChunkX,
                maxChunkZ = chunkViewport.maxChunkZ,
                chunks = listOf(
                    SurfaceChunkResult.Success(chunkViewport.minChunkX, chunkViewport.minChunkZ, chunkSurface),
                ),
            ),
        )
    }

    private fun surface(identifier: Identifier): ChunkSurface = ChunkSurface(
        palette = listOf(SurfaceBlockState(identifier)),
        cells = List(SURFACE_CELL_COUNT) { 0 },
    )

    private class DeferredWebMapService : WebMapService {
        val calls = mutableListOf<Call>()

        override suspend fun worldMetadata(): WorldMetadata = error("Not used")

        override suspend fun querySurface(surfaceRequest: SurfaceRequest): SurfaceQueryResult {
            val result = CompletableDeferred<SurfaceQueryResult>()
            val call = Call(surfaceRequest, result)
            calls += call
            return try {
                result.await()
            } catch (cancellationException: CancellationException) {
                call.cancelled = true
                throw cancellationException
            }
        }

        fun completeNext(surfaceQueryResult: SurfaceQueryResult) {
            calls.first { call -> !call.cancelled && !call.result.isCompleted }.result.complete(surfaceQueryResult)
        }

        data class Call(
            val surfaceRequest: SurfaceRequest,
            val result: CompletableDeferred<SurfaceQueryResult>,
            var cancelled: Boolean = false,
        )
    }

    private class QueueWebMapService(
        private val responses: ArrayDeque<SurfaceQueryResult>,
    ) : WebMapService {
        val requests = mutableListOf<SurfaceRequest>()

        override suspend fun worldMetadata(): WorldMetadata = error("Not used")

        override suspend fun querySurface(surfaceRequest: SurfaceRequest): SurfaceQueryResult {
            requests += surfaceRequest
            return responses.removeFirst()
        }
    }
}
