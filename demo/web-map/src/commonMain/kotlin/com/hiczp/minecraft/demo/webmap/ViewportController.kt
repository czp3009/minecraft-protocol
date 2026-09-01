package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ViewportSelection(
    val dimensionId: DimensionId,
    val chunkViewport: ChunkViewport,
) {
    val normalized: ViewportSelection
        get() = copy(chunkViewport = chunkViewport.normalized)
}

data class ViewportRenderState(
    val requestedSelection: ViewportSelection? = null,
    val displayedSelection: ViewportSelection? = null,
    val surfaces: Map<ChunkCoordinate, ChunkSurface> = emptyMap(),
    val loading: Boolean = false,
    val readFailedCoordinates: Set<ChunkCoordinate> = emptySet(),
    val exhaustedCoordinates: Set<ChunkCoordinate> = emptySet(),
    val rejection: SurfaceQueryRejection? = null,
    val callFailure: String? = null,
)

data class SurfaceRetryPolicy(
    val maximumAttempts: Int = 5,
    val initialDelay: Duration = 500.milliseconds,
    val maximumDelay: Duration = 8.seconds,
) {
    init {
        require(maximumAttempts > 0) { "Maximum repair attempts must be positive" }
        require(initialDelay.isPositive()) { "Initial repair delay must be positive" }
        require(maximumDelay >= initialDelay) { "Maximum repair delay must not be shorter than the initial delay" }
    }

    fun delayBeforeAttempt(attempt: Int): Duration {
        require(attempt > 0) { "Repair attempt numbers start at one" }
        var retryDelay = initialDelay
        repeat(attempt - 1) {
            retryDelay = minOf(retryDelay * 2, maximumDelay)
        }
        return retryDelay
    }
}

fun interface RequestScheduler {
    suspend fun wait(duration: Duration)
}

object CoroutineRequestScheduler : RequestScheduler {
    override suspend fun wait(duration: Duration) {
        delay(duration)
    }
}

class ViewportController(
    private val webMapService: WebMapService,
    private val coroutineScope: CoroutineScope,
    private val requestScheduler: RequestScheduler = CoroutineRequestScheduler,
    private val retryPolicy: SurfaceRetryPolicy = SurfaceRetryPolicy(),
    private val debounceDuration: Duration = 200.milliseconds,
    private val requestCancelled: (ViewportSelection) -> Unit = {},
    private val stateChanged: (ViewportRenderState) -> Unit,
) {
    private val surfaceCache = mutableMapOf<DimensionChunkCoordinate, ChunkSurface>()
    private var requestGeneration = 0L
    private var generationJob: Job? = null
    private var requestedSelection: ViewportSelection? = null

    var state: ViewportRenderState = ViewportRenderState()
        private set

    fun select(viewportSelection: ViewportSelection, restartDebounce: Boolean = false) {
        val normalizedSelection = viewportSelection.normalized
        if (
            requestedSelection == normalizedSelection &&
            (!restartDebounce || generationJob?.isActive != true)
        ) {
            return
        }
        cancelGeneration(notify = true)
        val dimensionChanged = state.displayedSelection?.dimensionId?.let { dimensionId ->
            dimensionId != normalizedSelection.dimensionId
        } == true
        requestedSelection = normalizedSelection
        requestGeneration++
        val generation = requestGeneration
        publish(
            if (dimensionChanged) {
                ViewportRenderState(requestedSelection = normalizedSelection, loading = true)
            } else {
                state.copy(
                    requestedSelection = normalizedSelection,
                    loading = true,
                    readFailedCoordinates = emptySet(),
                    exhaustedCoordinates = emptySet(),
                    rejection = null,
                    callFailure = null,
                )
            },
        )
        generationJob = coroutineScope.launch {
            requestScheduler.wait(debounceDuration)
            loadGeneration(generation, normalizedSelection)
        }
    }

    fun interactionStarted() {
        if (generationJob?.isActive != true) return
        requestGeneration++
        cancelGeneration(notify = true)
        requestedSelection = null
    }

    fun close() {
        requestGeneration++
        cancelGeneration(notify = false)
        requestedSelection = null
    }

    private suspend fun loadGeneration(generation: Long, viewportSelection: ViewportSelection) {
        val surfaceQueryResult = try {
            webMapService.querySurface(viewportSelection.toSurfaceRequest())
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (failure: Throwable) {
            if (isCurrent(generation, viewportSelection)) {
                publish(state.copy(loading = false, callFailure = failure.message ?: failure::class.simpleName))
            }
            return
        }
        if (!isCurrent(generation, viewportSelection)) return
        when (surfaceQueryResult) {
            is SurfaceQueryResult.Rejected -> publish(
                state.copy(
                    loading = false,
                    rejection = surfaceQueryResult.rejection,
                ),
            )

            is SurfaceQueryResult.Success -> applyFullResponse(
                generation,
                viewportSelection,
                surfaceQueryResult.response
            )
        }
    }

    private suspend fun applyFullResponse(
        generation: Long,
        viewportSelection: ViewportSelection,
        surfaceResponse: SurfaceResponse,
    ) {
        if (surfaceResponse.chunkViewport != viewportSelection.chunkViewport) return
        val resultsByCoordinate = surfaceResponse.chunks.associateBy(SurfaceChunkResult::coordinate)
        val readFailedCoordinates = linkedSetOf<ChunkCoordinate>()
        viewportSelection.chunkViewport.chunkRange.forEach { chunkPosition ->
            val chunkCoordinate = ChunkCoordinate.from(chunkPosition)
            val cacheKey = DimensionChunkCoordinate(viewportSelection.dimensionId, chunkCoordinate)
            when (val surfaceChunkResult = resultsByCoordinate[chunkCoordinate]) {
                null -> surfaceCache.remove(cacheKey)
                is SurfaceChunkResult.Success -> surfaceCache[cacheKey] = surfaceChunkResult.surface
                is SurfaceChunkResult.ReadFailed -> readFailedCoordinates += chunkCoordinate
            }
        }
        publishCurrentViewport(
            viewportSelection = viewportSelection,
            loading = false,
            readFailedCoordinates = readFailedCoordinates,
            exhaustedCoordinates = emptySet(),
        )
        repairFailedChunks(generation, viewportSelection, readFailedCoordinates)
    }

    private suspend fun repairFailedChunks(
        generation: Long,
        viewportSelection: ViewportSelection,
        initialCoordinates: Set<ChunkCoordinate>,
    ) {
        val pendingCoordinates = initialCoordinates.toMutableSet()
        val exhaustedCoordinates = linkedSetOf<ChunkCoordinate>()
        val attempts = mutableMapOf<ChunkCoordinate, Int>()
        while (pendingCoordinates.isNotEmpty() && isCurrent(generation, viewportSelection)) {
            val chunkCoordinate = pendingCoordinates.minOrNull() ?: break
            val attempt = attempts.getOrElse(chunkCoordinate) { 0 } + 1
            requestScheduler.wait(retryPolicy.delayBeforeAttempt(attempt))
            if (!isCurrent(generation, viewportSelection)) return
            val surfaceQueryResult = try {
                webMapService.querySurface(
                    SurfaceRequest(
                        dimensionId = viewportSelection.dimensionId,
                        chunkViewport = ChunkViewport.single(chunkCoordinate),
                    ),
                )
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (failure: Throwable) {
                if (isCurrent(generation, viewportSelection)) {
                    publish(state.copy(callFailure = failure.message ?: failure::class.simpleName))
                }
                return
            }
            if (!isCurrent(generation, viewportSelection)) return
            val surfaceChunkResult = (surfaceQueryResult as? SurfaceQueryResult.Success)
                ?.response
                ?.chunks
                ?.singleOrNull { result -> result.coordinate == chunkCoordinate }
            val cacheKey = DimensionChunkCoordinate(viewportSelection.dimensionId, chunkCoordinate)
            when (surfaceChunkResult) {
                is SurfaceChunkResult.Success -> {
                    surfaceCache[cacheKey] = surfaceChunkResult.surface
                    pendingCoordinates -= chunkCoordinate
                }

                null -> {
                    surfaceCache.remove(cacheKey)
                    pendingCoordinates -= chunkCoordinate
                }

                is SurfaceChunkResult.ReadFailed -> {
                    attempts[chunkCoordinate] = attempt
                    if (attempt >= retryPolicy.maximumAttempts) {
                        pendingCoordinates -= chunkCoordinate
                        exhaustedCoordinates += chunkCoordinate
                    }
                }
            }
            publishCurrentViewport(
                viewportSelection = viewportSelection,
                loading = false,
                readFailedCoordinates = pendingCoordinates,
                exhaustedCoordinates = exhaustedCoordinates,
            )
        }
    }

    private fun publishCurrentViewport(
        viewportSelection: ViewportSelection,
        loading: Boolean,
        readFailedCoordinates: Set<ChunkCoordinate>,
        exhaustedCoordinates: Set<ChunkCoordinate>,
    ) {
        val surfaces = buildMap {
            viewportSelection.chunkViewport.chunkRange.forEach { chunkPosition ->
                val chunkCoordinate = ChunkCoordinate.from(chunkPosition)
                surfaceCache[DimensionChunkCoordinate(viewportSelection.dimensionId, chunkCoordinate)]?.let { surface ->
                    put(chunkCoordinate, surface)
                }
            }
        }
        publish(
            ViewportRenderState(
                requestedSelection = viewportSelection,
                displayedSelection = viewportSelection,
                surfaces = surfaces,
                loading = loading,
                readFailedCoordinates = readFailedCoordinates.toSet(),
                exhaustedCoordinates = exhaustedCoordinates.toSet(),
            ),
        )
    }

    private fun publish(viewportRenderState: ViewportRenderState) {
        state = viewportRenderState
        stateChanged(viewportRenderState)
    }

    private fun cancelGeneration(notify: Boolean) {
        val activeJob = generationJob
        val cancelledSelection = requestedSelection
        if (activeJob?.isActive == true) {
            activeJob.cancel()
            if (notify && cancelledSelection != null) requestCancelled(cancelledSelection)
        }
        generationJob = null
    }

    private fun isCurrent(generation: Long, viewportSelection: ViewportSelection): Boolean =
        generation == requestGeneration && requestedSelection == viewportSelection
}

private data class DimensionChunkCoordinate(
    val dimensionId: DimensionId,
    val chunkCoordinate: ChunkCoordinate,
)

private fun ViewportSelection.toSurfaceRequest(): SurfaceRequest = SurfaceRequest(dimensionId, chunkViewport)
