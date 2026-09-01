package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class ViewportSelection(
    val dimensionId: DimensionId,
    val chunkViewport: ChunkViewport,
) {
    val normalized: ViewportSelection
        get() = copy(chunkViewport = chunkViewport.normalized)
}

data class SurfaceChunkSnapshot(
    val timestampEpochSeconds: Int,
    val surface: ChunkSurface,
)

data class ViewportRenderState(
    val requestedSelection: ViewportSelection? = null,
    val displayedSelection: ViewportSelection? = null,
    val surfaces: Map<ChunkCoordinate, SurfaceChunkSnapshot> = emptyMap(),
    val receivedChunkCoordinates: Set<ChunkCoordinate> = emptySet(),
    val loading: Boolean = false,
    val connected: Boolean = false,
    val readFailedCoordinates: Set<ChunkCoordinate> = emptySet(),
    val rejection: SurfaceQueryRejection? = null,
    val callFailure: String? = null,
)

fun interface RequestScheduler {
    suspend fun wait(duration: Duration)
}

object CoroutineRequestScheduler : RequestScheduler {
    override suspend fun wait(duration: Duration) {
        delay(duration)
    }
}

class ViewportController(
    private val coroutineScope: CoroutineScope,
    webMapService: WebMapService? = null,
    private val requestScheduler: RequestScheduler = CoroutineRequestScheduler,
    private val debounceDuration: Duration = 200.milliseconds,
    private val requestCancelled: (ViewportSelection) -> Unit = {},
    private val stateChanged: (ViewportRenderState) -> Unit,
) {
    private val surfaceCache = mutableMapOf<DimensionChunkCoordinate, SurfaceChunkSnapshot>()
    private var connectedService: WebMapService? = webMapService
    private var requestJob: Job? = null
    private var requestedSelection: ViewportSelection? = null
    private var interactionActive = false

    var state: ViewportRenderState = ViewportRenderState(connected = webMapService != null)
        private set

    fun connected(webMapService: WebMapService) {
        cancelRequest(notify = false)
        connectedService = webMapService
        val viewportSelection = requestedSelection
        publish(state.copy(connected = true, callFailure = null))
        if (viewportSelection != null && !interactionActive) scheduleRequest(viewportSelection, waitForDebounce = false)
    }

    fun disconnected(message: String) {
        connectedService = null
        cancelRequest(notify = false)
        publish(state.copy(loading = false, connected = false, callFailure = message))
    }

    fun select(viewportSelection: ViewportSelection, restartDebounce: Boolean = false) {
        val normalizedSelection = viewportSelection.normalized
        if (requestedSelection == normalizedSelection && !restartDebounce && !interactionActive) return
        cancelRequest(notify = true)
        interactionActive = false
        requestedSelection = normalizedSelection
        publishSelection(normalizedSelection)
        scheduleRequest(normalizedSelection, waitForDebounce = true)
    }

    fun interactionStarted() {
        interactionActive = true
        cancelRequest(notify = true)
        publish(state.copy(loading = false))
    }

    fun close() {
        connectedService = null
        requestedSelection = null
        cancelRequest(notify = false)
    }

    private fun publishRequestStarted(viewportSelection: ViewportSelection) {
        publish(
            state.copy(
                requestedSelection = viewportSelection,
                displayedSelection = viewportSelection,
                surfaces = cachedSurfaces(viewportSelection),
                receivedChunkCoordinates = emptySet(),
                loading = connectedService != null,
                connected = connectedService != null,
                readFailedCoordinates = emptySet(),
                rejection = null,
                callFailure = null,
            ),
        )
    }

    private fun publishSelection(viewportSelection: ViewportSelection) {
        val connected = connectedService != null
        publish(
            state.copy(
                requestedSelection = viewportSelection,
                displayedSelection = viewportSelection,
                surfaces = cachedSurfaces(viewportSelection),
                receivedChunkCoordinates = emptySet(),
                loading = false,
                connected = connected,
                readFailedCoordinates = emptySet(),
                rejection = null,
                callFailure = state.callFailure.takeUnless { connected },
            ),
        )
    }

    private fun scheduleRequest(viewportSelection: ViewportSelection, waitForDebounce: Boolean) {
        val webMapService = connectedService ?: return
        requestJob = coroutineScope.launch {
            if (waitForDebounce) requestScheduler.wait(debounceDuration)
            currentCoroutineContext().ensureActive()
            publishRequestStarted(viewportSelection)
            collectRequest(viewportSelection, webMapService)
        }
    }

    private suspend fun collectRequest(
        viewportSelection: ViewportSelection,
        webMapService: WebMapService,
    ) {
        try {
            webMapService.querySurface(viewportSelection.toSurfaceRequest()).collect { surfaceQueryUpdate ->
                currentCoroutineContext().ensureActive()
                when (surfaceQueryUpdate) {
                    is SurfaceQueryUpdate.Rejected -> publish(
                        state.copy(
                            loading = false,
                            rejection = surfaceQueryUpdate.rejection,
                        ),
                    )

                    is SurfaceQueryUpdate.Chunk -> applyChunk(viewportSelection, surfaceQueryUpdate.result)
                }
            }
            currentCoroutineContext().ensureActive()
            publish(state.copy(loading = false, readFailedCoordinates = emptySet()))
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (failure: Throwable) {
            publish(state.copy(loading = false, callFailure = failure.message ?: failure::class.simpleName))
        }
    }

    private fun applyChunk(
        viewportSelection: ViewportSelection,
        surfaceChunkResult: SurfaceChunkResult,
    ) {
        val chunkCoordinate = surfaceChunkResult.coordinate
        if (chunkCoordinate !in viewportSelection.chunkViewport) return
        val cacheKey = DimensionChunkCoordinate(viewportSelection.dimensionId, chunkCoordinate)
        val failedCoordinates = state.readFailedCoordinates.toMutableSet()
        when (surfaceChunkResult) {
            is SurfaceChunkResult.ReadFailed -> failedCoordinates += chunkCoordinate
            is SurfaceChunkResult.Success -> {
                failedCoordinates -= chunkCoordinate
                val cachedSnapshot = surfaceCache[cacheKey]
                if (
                    cachedSnapshot == null ||
                    surfaceChunkResult.timestampEpochSeconds > cachedSnapshot.timestampEpochSeconds
                ) {
                    surfaceCache[cacheKey] = SurfaceChunkSnapshot(
                        timestampEpochSeconds = surfaceChunkResult.timestampEpochSeconds,
                        surface = surfaceChunkResult.surface,
                    )
                }
            }
        }
        val viewportRenderState = state.copy(
            displayedSelection = viewportSelection,
            surfaces = cachedSurfaces(viewportSelection),
            receivedChunkCoordinates = state.receivedChunkCoordinates + chunkCoordinate,
            readFailedCoordinates = failedCoordinates,
        )
        if (viewportRenderState != state) publish(viewportRenderState)
    }

    private fun cachedSurfaces(viewportSelection: ViewportSelection): Map<ChunkCoordinate, SurfaceChunkSnapshot> =
        buildMap {
            viewportSelection.chunkViewport.chunkRange.forEach { chunkPosition ->
                val chunkCoordinate = ChunkCoordinate.from(chunkPosition)
                surfaceCache[DimensionChunkCoordinate(
                    viewportSelection.dimensionId,
                    chunkCoordinate
                )]?.let { snapshot ->
                    put(chunkCoordinate, snapshot)
                }
            }
        }

    private fun publish(viewportRenderState: ViewportRenderState) {
        state = viewportRenderState
        stateChanged(viewportRenderState)
    }

    private fun cancelRequest(notify: Boolean) {
        val activeJob = requestJob
        val cancelledSelection = requestedSelection
        if (activeJob?.isActive == true) {
            activeJob.cancel()
            if (notify && cancelledSelection != null) requestCancelled(cancelledSelection)
        }
        requestJob = null
    }
}

private data class DimensionChunkCoordinate(
    val dimensionId: DimensionId,
    val chunkCoordinate: ChunkCoordinate,
)

private fun ViewportSelection.toSurfaceRequest(): SurfaceRequest = SurfaceRequest(dimensionId, chunkViewport)
