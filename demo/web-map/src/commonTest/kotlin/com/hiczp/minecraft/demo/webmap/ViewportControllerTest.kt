package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ViewportControllerTest {
    @Test
    fun publishesEachChunkAsSoonAsItsFlowItemArrives() = runTest {
        val webMapService = DeferredWebMapService()
        val viewportController = controller(this, webMapService)
        val viewportSelection = ViewportSelection(DimensionId.Overworld, ChunkViewport(31, 0, 32, 0))

        viewportController.select(viewportSelection)
        runCurrent()
        val call = webMapService.calls.single()
        call.send(update(success(31, timestamp = 1, surface = surface("stone"))))
        runCurrent()

        assertTrue(viewportController.state.loading)
        assertEquals(setOf(ChunkCoordinate(31, 0)), viewportController.state.surfaces.keys)
        assertEquals(setOf(ChunkCoordinate(31, 0)), viewportController.state.receivedChunkCoordinates)

        call.send(update(success(32, timestamp = 2, surface = surface("dirt"))))
        call.complete()
        advanceUntilIdle()

        assertFalse(viewportController.state.loading)
        assertEquals(setOf(ChunkCoordinate(31, 0), ChunkCoordinate(32, 0)), viewportController.state.surfaces.keys)
        assertEquals(
            setOf(ChunkCoordinate(31, 0), ChunkCoordinate(32, 0)),
            viewportController.state.receivedChunkCoordinates,
        )
    }

    @Test
    fun equalTimestampKeepsTheAlreadyCompositedChunkSnapshot() = runTest {
        val webMapService = DeferredWebMapService()
        val viewportController = controller(this, webMapService)
        val viewportSelection = selection(0)

        viewportController.select(viewportSelection)
        runCurrent()
        webMapService.calls.single().apply {
            send(update(success(0, timestamp = 7, surface = surface("stone"))))
            complete()
        }
        advanceUntilIdle()
        val firstSnapshot = viewportController.state.surfaces.getValue(ChunkCoordinate(0, 0))

        viewportController.select(viewportSelection, restartDebounce = true)
        runCurrent()
        webMapService.calls.last().apply {
            send(update(success(0, timestamp = 7, surface = surface("diamond_block"))))
            complete()
        }
        advanceUntilIdle()

        assertSame(firstSnapshot, viewportController.state.surfaces.getValue(ChunkCoordinate(0, 0)))
        assertEquals(surface("stone"), firstSnapshot.surface)
    }

    @Test
    fun olderTimestampCannotReplaceTheAlreadyCompositedChunkSnapshot() = runTest {
        val webMapService = DeferredWebMapService()
        val viewportController = controller(this, webMapService)
        val viewportSelection = selection(0)

        viewportController.select(viewportSelection)
        runCurrent()
        webMapService.calls.single().apply {
            send(update(success(0, timestamp = 7, surface = surface("stone"))))
            complete()
        }
        advanceUntilIdle()
        val firstSnapshot = viewportController.state.surfaces.getValue(ChunkCoordinate(0, 0))

        viewportController.select(viewportSelection, restartDebounce = true)
        runCurrent()
        webMapService.calls.last().apply {
            send(update(SurfaceChunkResult.ReadFailed(0, 0)))
            send(update(success(0, timestamp = 6, surface = surface("diamond_block"))))
            complete()
        }
        advanceUntilIdle()

        assertTrue(viewportController.state.readFailedCoordinates.isEmpty())
        assertSame(firstSnapshot, viewportController.state.surfaces.getValue(ChunkCoordinate(0, 0)))
        assertEquals(surface("stone"), firstSnapshot.surface)
    }

    @Test
    fun anUnreturnedChunkKeepsTheCachedChunkTileInput() = runTest {
        val webMapService = DeferredWebMapService()
        val viewportController = controller(this, webMapService)
        val viewportSelection = selection(0)

        viewportController.select(viewportSelection)
        runCurrent()
        webMapService.calls.single().apply {
            send(update(success(0, timestamp = 1, surface = surface("stone"))))
            complete()
        }
        advanceUntilIdle()

        viewportController.select(viewportSelection, restartDebounce = true)
        runCurrent()
        webMapService.calls.last().complete()
        advanceUntilIdle()

        assertEquals(surface("stone"), viewportController.state.surfaces.getValue(ChunkCoordinate(0, 0)).surface)
    }

    @Test
    fun completedStreamClearsAReadFailureThatResolvedWithoutChunkData() = runTest {
        val webMapService = DeferredWebMapService()
        val viewportController = controller(this, webMapService)

        viewportController.select(selection(0))
        runCurrent()
        val call = webMapService.calls.single()
        call.send(update(SurfaceChunkResult.ReadFailed(0, 0)))
        runCurrent()
        assertEquals(setOf(ChunkCoordinate(0, 0)), viewportController.state.readFailedCoordinates)

        call.complete()
        advanceUntilIdle()

        assertTrue(viewportController.state.readFailedCoordinates.isEmpty())
    }

    @Test
    fun reconnectPreservesCacheAndRestartsTheCurrentSelection() = runTest {
        val firstService = DeferredWebMapService()
        val viewportController = controller(this, firstService)
        val viewportSelection = selection(0)
        viewportController.select(viewportSelection)
        runCurrent()
        firstService.calls.single().apply {
            send(update(success(0, timestamp = 1, surface = surface("stone"))))
            complete()
        }
        advanceUntilIdle()

        viewportController.disconnected("socket closed")
        assertFalse(viewportController.state.connected)
        assertEquals(surface("stone"), viewportController.state.surfaces.getValue(ChunkCoordinate(0, 0)).surface)

        val secondService = DeferredWebMapService()
        viewportController.connected(secondService)
        runCurrent()
        assertEquals(viewportSelection.toRequest(), secondService.calls.single().surfaceRequest)
        secondService.calls.single().apply {
            send(update(success(0, timestamp = 2, surface = surface("dirt"))))
            complete()
        }
        advanceUntilIdle()

        assertTrue(viewportController.state.connected)
        assertEquals(surface("dirt"), viewportController.state.surfaces.getValue(ChunkCoordinate(0, 0)).surface)
    }

    @Test
    fun changedViewportCancelsTheOlderSurfaceFlow() = runTest {
        val webMapService = DeferredWebMapService()
        val viewportController = controller(this, webMapService)

        viewportController.select(selection(0))
        runCurrent()
        val firstCall = webMapService.calls.single()
        viewportController.select(selection(1))
        runCurrent()

        assertTrue(firstCall.cancelled)
        assertEquals(selection(1).toRequest(), webMapService.calls.last().surfaceRequest)
        viewportController.close()
    }

    @Test
    fun interactionCancelsTheCurrentFlowAndRestartsTheSameViewportOnlyAfterDebounce() = runTest {
        val webMapService = DeferredWebMapService()
        val requestScheduler = ControlledRequestScheduler()
        val viewportSelection = selection(0)
        val viewportController = ViewportController(
            coroutineScope = this,
            webMapService = webMapService,
            requestScheduler = requestScheduler,
            stateChanged = {},
        )

        viewportController.select(viewportSelection)
        runCurrent()
        assertTrue(webMapService.calls.isEmpty())
        assertEquals(viewportSelection, viewportController.state.displayedSelection)

        requestScheduler.releaseNext()
        runCurrent()
        val firstCall = webMapService.calls.single()
        assertTrue(viewportController.state.loading)

        viewportController.interactionStarted()
        runCurrent()
        assertTrue(firstCall.cancelled)
        assertFalse(viewportController.state.loading)

        viewportController.select(viewportSelection)
        runCurrent()
        assertEquals(1, webMapService.calls.size)
        requestScheduler.releaseNext()
        runCurrent()
        assertEquals(2, webMapService.calls.size)
        viewportController.close()
    }

    @Test
    fun aNewViewportPublishesEveryCachedChunkBeforeItsRequestDebounceCompletes() = runTest {
        val webMapService = DeferredWebMapService()
        val requestScheduler = ControlledRequestScheduler()
        val viewportController = ViewportController(
            coroutineScope = this,
            webMapService = webMapService,
            requestScheduler = requestScheduler,
            stateChanged = {},
        )

        viewportController.select(selection(0))
        runCurrent()
        requestScheduler.releaseNext()
        runCurrent()
        webMapService.calls.single().apply {
            send(update(success(0, timestamp = 1, surface = surface("stone"))))
            complete()
        }
        advanceUntilIdle()

        viewportController.select(selection(1))
        runCurrent()
        requestScheduler.releaseNext()
        runCurrent()
        webMapService.calls.last().apply {
            send(update(success(1, timestamp = 1, surface = surface("dirt"))))
            complete()
        }
        advanceUntilIdle()

        val expandedSelection = ViewportSelection(DimensionId.Overworld, ChunkViewport(0, 0, 1, 0))
        viewportController.select(expandedSelection)
        runCurrent()

        assertEquals(2, webMapService.calls.size)
        assertEquals(expandedSelection, viewportController.state.displayedSelection)
        assertEquals(
            setOf(ChunkCoordinate(0, 0), ChunkCoordinate(1, 0)),
            viewportController.state.surfaces.keys,
        )
        assertFalse(viewportController.state.loading)
        viewportController.close()
    }

    private fun controller(
        coroutineScope: CoroutineScope,
        webMapService: WebMapService,
    ): ViewportController = ViewportController(
        coroutineScope = coroutineScope,
        webMapService = webMapService,
        requestScheduler = { _: Duration -> },
        debounceDuration = Duration.ZERO,
        stateChanged = {},
    )

    private fun selection(chunkX: Int): ViewportSelection =
        ViewportSelection(DimensionId.Overworld, ChunkViewport(chunkX, 0, chunkX, 0))

    private fun update(surfaceChunkResult: SurfaceChunkResult): SurfaceQueryUpdate.Chunk =
        SurfaceQueryUpdate.Chunk(surfaceChunkResult)

    private fun success(
        chunkX: Int,
        timestamp: Int,
        surface: ChunkSurface,
    ): SurfaceChunkResult.Success = SurfaceChunkResult.Success(chunkX, 0, timestamp, surface)

    private fun surface(blockName: String): ChunkSurface = ChunkSurface(
        palette = listOf(SurfaceColumn(listOf(SurfaceBlockState(Identifier(blockName))))),
        cells = List(SURFACE_CELL_COUNT) { 0 },
    )

    private fun ViewportSelection.toRequest(): SurfaceRequest = SurfaceRequest(dimensionId, chunkViewport)

    private class DeferredWebMapService : WebMapService {
        val calls = mutableListOf<Call>()

        override suspend fun worldMetadata(): WorldMetadata = error("Not used")

        override fun assetLoading(): Flow<AssetLoadStatus> = flowOf(
            AssetLoadStatus.Ready("revision", 1, 1),
        )

        override fun blockRenderResources(
            blockRenderResourceRequest: BlockRenderResourceRequest,
        ): Flow<BlockRenderResourceResult> = flowOf()

        override suspend fun textureResource(textureResourceRequest: TextureResourceRequest): TextureResource? = null

        override fun querySurface(surfaceRequest: SurfaceRequest): Flow<SurfaceQueryUpdate> {
            val call = Call(surfaceRequest)
            calls += call
            return flow {
                try {
                    emitAll(call.updates.receiveAsFlow())
                } finally {
                    if (!call.completed) call.cancelled = true
                }
            }
        }

        class Call(
            val surfaceRequest: SurfaceRequest,
        ) {
            val updates = Channel<SurfaceQueryUpdate>(Channel.UNLIMITED)
            var cancelled: Boolean = false
            var completed: Boolean = false

            suspend fun send(surfaceQueryUpdate: SurfaceQueryUpdate) {
                updates.send(surfaceQueryUpdate)
            }

            fun complete() {
                completed = true
                updates.close()
            }
        }
    }

    private class ControlledRequestScheduler : RequestScheduler {
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        override suspend fun wait(duration: Duration) {
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }

        fun releaseNext() {
            gates.removeAt(0).complete(Unit)
        }
    }
}
