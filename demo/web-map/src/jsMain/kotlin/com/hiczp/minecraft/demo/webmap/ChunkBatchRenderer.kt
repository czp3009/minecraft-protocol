@file:Suppress("UnsafeCastFromDynamic")

package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.CHUNK_SIDE
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.MinecraftCoordinates
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ChunkBatchRenderer(
    private val map: dynamic,
    private val progressChanged: (ChunkRenderProgress) -> Unit = {},
) {
    private val canvas: dynamic = browserDocument.createElement("canvas")
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tileCache = linkedMapOf<ChunkCanvasKey, CachedChunkTile>()
    private var tileCacheBytes = 0L
    private var renderJob: Job? = null
    private var renderUpdates: Channel<CanvasRenderUpdate>? = null
    private var canvasViewport: CanvasViewport? = null
    private var canvasDimensionId: DimensionId? = null
    private var activeSelection: ViewportSelection? = null
    private var visibleRenderedChunkCoordinates = emptySet<ChunkCoordinate>()
    private var interactionActive = false
    private var closed = false

    init {
        canvas.className = "world-map-canvas"
        canvas.setAttribute("aria-hidden", "true")
        map.getContainer().appendChild(canvas)
    }

    fun render(viewportRenderState: ViewportRenderState, officialAssetSession: OfficialAssetSession?) {
        if (closed) return
        if (interactionActive || viewportRenderState.displayedSelection == null) return
        val canvasRenderInput = CanvasRenderInput.from(
            viewportRenderState = viewportRenderState,
            officialAssetSession = officialAssetSession,
        )
        val beginsViewportGeneration = activeSelection != canvasRenderInput.displayedSelection
        if (beginsViewportGeneration) {
            beginViewportGeneration(viewportRenderState, canvasRenderInput.displayedSelection)
        } else {
            publishProgress(canvasRenderInput)
        }
        renderUpdates?.trySend(CanvasRenderUpdate(canvasRenderInput, officialAssetSession))
    }

    fun interactionStarted() {
        interactionActive = true
        activeSelection = null
        renderJob?.cancel()
        renderUpdates?.cancel()
        renderJob = null
        renderUpdates = null
        updateLocalTransform()
    }

    fun mapTransformed() {
        updateLocalTransform()
    }

    fun viewportSettled() {
        interactionActive = false
    }

    fun close() {
        if (closed) return
        closed = true
        coroutineScope.cancel()
        renderUpdates?.cancel()
        canvas.remove()
        tileCache.clear()
        tileCacheBytes = 0L
        canvasViewport = null
        canvasDimensionId = null
        activeSelection = null
        renderUpdates = null
        visibleRenderedChunkCoordinates = emptySet()
    }

    private fun beginViewportGeneration(
        viewportRenderState: ViewportRenderState,
        viewportSelection: ViewportSelection,
    ) {
        renderJob?.cancel()
        renderUpdates?.cancel()
        renderJob = null
        renderUpdates = null
        activeSelection = viewportSelection
        val newRenderUpdates = Channel<CanvasRenderUpdate>(Channel.CONFLATED)
        renderUpdates = newRenderUpdates
        renderJob = coroutineScope.launch {
            try {
                prepareViewportGeneration(viewportRenderState, viewportSelection)
                renderGeneration(viewportSelection, newRenderUpdates)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (failure: Throwable) {
                BrowserConsole.error("Canvas rendering failed.", failure)
            }
        }
    }

    private suspend fun prepareViewportGeneration(
        viewportRenderState: ViewportRenderState,
        viewportSelection: ViewportSelection,
    ) {
        currentCoroutineContext().ensureActive()
        val newCanvasViewport = captureCanvasViewport()
        val working: dynamic = browserDocument.createElement("canvas")
        working.width = newCanvasViewport.physicalWidth
        working.height = newCanvasViewport.physicalHeight
        val context: dynamic = working.getContext("2d")
        context.imageSmoothingEnabled = false
        context.setTransform(
            newCanvasViewport.devicePixelRatio,
            0,
            0,
            newCanvasViewport.devicePixelRatio,
            0,
            0,
        )
        seedWorkingCanvas(context, newCanvasViewport, viewportSelection.dimensionId)
        val renderActions = visibleRenderActions(viewportRenderState.surfaces, newCanvasViewport)
        val renderedChunkCoordinates = mutableSetOf<ChunkCoordinate>()
        val reusedChunkCanvasKeys = mutableListOf<ChunkCanvasKey>()
        renderActions.forEachIndexed { actionIndex, chunkRenderAction ->
            if (actionIndex % RENDER_YIELD_INTERVAL == 0) yield()
            currentCoroutineContext().ensureActive()
            val chunkCanvasKey = ChunkCanvasKey(viewportSelection.dimensionId, chunkRenderAction.chunkCoordinate)
            val cachedChunkTile = tileCache[chunkCanvasKey]
            if (cachedChunkTile != null) {
                drawChunkTile(context, chunkRenderAction.rectangle, cachedChunkTile)
                renderedChunkCoordinates += chunkRenderAction.chunkCoordinate
                reusedChunkCanvasKeys += chunkCanvasKey
            }
        }
        currentCoroutineContext().ensureActive()
        visibleRenderedChunkCoordinates = renderedChunkCoordinates
        publishWorkingCanvas(working, newCanvasViewport, viewportSelection.dimensionId)
        currentCoroutineContext().ensureActive()
        reusedChunkCanvasKeys.forEach(::cachedChunkTile)
        progressChanged(ChunkRenderProgress(viewportSelection, renderedChunkCoordinates.size))
    }

    private suspend fun renderGeneration(
        viewportSelection: ViewportSelection,
        renderUpdates: Channel<CanvasRenderUpdate>,
    ) {
        var canvasRenderUpdate = renderUpdates.receive()
        while (true) {
            currentCoroutineContext().ensureActive()
            canvasRenderUpdate = renderUpdates.drainLatest(canvasRenderUpdate)
            val canvasRenderInput = canvasRenderUpdate.canvasRenderInput
            val currentCanvasViewport = canvasViewport ?: return
            val assetRevision = canvasRenderInput.assetRevision ?: BLACK_FALLBACK_ASSET_REVISION
            val visibleRenderActions = visibleRenderActions(canvasRenderInput.surfaces, currentCanvasViewport)
            val pendingRenderActions = buildList {
                visibleRenderActions.forEachIndexed { actionIndex, chunkRenderAction ->
                    if (actionIndex % RENDER_YIELD_INTERVAL == 0) yield()
                    currentCoroutineContext().ensureActive()
                    val cachedChunkTile =
                        tileCache[ChunkCanvasKey(viewportSelection.dimensionId, chunkRenderAction.chunkCoordinate)]
                    if (
                        cachedChunkTile == null ||
                        cachedChunkTile.timestampEpochSeconds != chunkRenderAction.surfaceChunkSnapshot.timestampEpochSeconds ||
                        cachedChunkTile.assetRevision != assetRevision
                    ) {
                        add(chunkRenderAction)
                    }
                }
            }
            if (pendingRenderActions.isEmpty()) {
                canvasRenderUpdate = renderUpdates.receive()
                continue
            }
            pendingRenderActions.forEachIndexed { actionIndex, chunkRenderAction ->
                if (actionIndex % RENDER_YIELD_INTERVAL == 0) yield()
                currentCoroutineContext().ensureActive()
                canvasRenderUpdate = renderUpdates.drainLatest(canvasRenderUpdate)
                if (!canvasRenderUpdate.matches(
                        chunkRenderAction,
                        viewportSelection,
                        assetRevision
                    )
                ) return@forEachIndexed
                val cachedChunkTile = renderChunkTile(
                    chunkCoordinate = chunkRenderAction.chunkCoordinate,
                    chunkSurface = chunkRenderAction.surfaceChunkSnapshot.surface,
                    timestampEpochSeconds = chunkRenderAction.surfaceChunkSnapshot.timestampEpochSeconds,
                    assetRevision = assetRevision,
                    officialAssetSession = canvasRenderUpdate.officialAssetSession,
                )
                currentCoroutineContext().ensureActive()
                canvasRenderUpdate = renderUpdates.drainLatest(canvasRenderUpdate)
                if (!canvasRenderUpdate.matches(
                        chunkRenderAction,
                        viewportSelection,
                        assetRevision
                    )
                ) return@forEachIndexed
                val chunkCanvasKey = ChunkCanvasKey(viewportSelection.dimensionId, chunkRenderAction.chunkCoordinate)
                val context: dynamic = canvas.getContext("2d")
                currentCoroutineContext().ensureActive()
                drawChunkTile(context, chunkRenderAction.rectangle, cachedChunkTile)
                currentCoroutineContext().ensureActive()
                cacheChunkTile(chunkCanvasKey, cachedChunkTile)
                visibleRenderedChunkCoordinates = visibleRenderedChunkCoordinates + chunkRenderAction.chunkCoordinate
                publishProgress(canvasRenderUpdate.canvasRenderInput)
            }
        }
    }

    private fun CanvasRenderUpdate.matches(
        chunkRenderAction: ChunkRenderAction,
        viewportSelection: ViewportSelection,
        assetRevision: String,
    ): Boolean =
        !interactionActive &&
                activeSelection == viewportSelection &&
                canvasRenderInput.displayedSelection == viewportSelection &&
                canvasRenderInput.surfaces[chunkRenderAction.chunkCoordinate] == chunkRenderAction.surfaceChunkSnapshot &&
                (canvasRenderInput.assetRevision ?: BLACK_FALLBACK_ASSET_REVISION) == assetRevision

    private fun publishProgress(canvasRenderInput: CanvasRenderInput) {
        val renderedChunkCount = visibleRenderedChunkCoordinates.count { chunkCoordinate ->
            chunkCoordinate in canvasRenderInput.surfaces
        }
        progressChanged(ChunkRenderProgress(canvasRenderInput.displayedSelection, renderedChunkCount))
    }

    private suspend fun renderChunkTile(
        chunkCoordinate: ChunkCoordinate,
        chunkSurface: ChunkSurface,
        timestampEpochSeconds: Int,
        assetRevision: String,
        officialAssetSession: OfficialAssetSession?,
    ): CachedChunkTile {
        val chunkPixelSide = CHUNK_SIDE * MAXIMUM_RENDER_BLOCK_PIXELS
        val tile: dynamic = browserDocument.createElement("canvas")
        tile.width = chunkPixelSide
        tile.height = chunkPixelSide
        val context: dynamic = tile.getContext("2d")
        context.imageSmoothingEnabled = false
        val blockXRange = MinecraftCoordinates.blockXRange(chunkCoordinate.chunkPosition)
        val blockZRange = MinecraftCoordinates.blockZRange(chunkCoordinate.chunkPosition)
        val surfaceColumnInputs = mutableListOf<SurfaceColumnInput>()
        for (cellIndex in 0 until SURFACE_CELL_COUNT) {
            if (cellIndex % RENDER_YIELD_INTERVAL == 0) yield()
            currentCoroutineContext().ensureActive()
            val localX = cellIndex % CHUNK_SIDE
            val localZ = cellIndex / CHUNK_SIDE
            val surfaceColumn = chunkSurface[localX, localZ] ?: continue
            surfaceColumnInputs += SurfaceColumnInput(
                localX = localX,
                localZ = localZ,
                blockX = MinecraftCoordinates.offsetBlockCoordinate(blockXRange.first, localX),
                blockZ = MinecraftCoordinates.offsetBlockCoordinate(blockZRange.first, localZ),
                blocks = surfaceColumn.blocks,
            )
        }
        val surfaceBlockRenderRequests = mutableListOf<SurfaceBlockRenderRequest>()
        surfaceColumnInputs.forEachIndexed { columnIndex, surfaceColumnInput ->
            if (columnIndex % RENDER_YIELD_INTERVAL == 0) yield()
            currentCoroutineContext().ensureActive()
            surfaceColumnInput.blocks.forEach { surfaceBlockState ->
                surfaceBlockRenderRequests += SurfaceBlockRenderRequest(
                    surfaceBlockState = surfaceBlockState,
                    blockX = surfaceColumnInput.blockX,
                    blockZ = surfaceColumnInput.blockZ,
                )
            }
        }
        officialAssetSession?.prefetch(surfaceBlockRenderRequests)
        val preparedColumns = coroutineScope {
            surfaceColumnInputs.map { surfaceColumnInput ->
                async {
                    currentCoroutineContext().ensureActive()
                    PreparedSurfaceColumn(
                        localX = surfaceColumnInput.localX,
                        localZ = surfaceColumnInput.localZ,
                        layers = surfaceColumnInput.blocks.map { surfaceBlockState ->
                            PreparedSurfaceLayer(
                                sprite = officialAssetSession?.sprite(
                                    surfaceBlockState,
                                    surfaceColumnInput.blockX,
                                    surfaceColumnInput.blockZ,
                                ),
                            )
                        },
                    )
                }
            }.awaitAll()
        }
        preparedColumns.forEachIndexed { columnIndex, preparedSurfaceColumn ->
            if (columnIndex % RENDER_YIELD_INTERVAL == 0) yield()
            currentCoroutineContext().ensureActive()
            val pixelX = preparedSurfaceColumn.localX * MAXIMUM_RENDER_BLOCK_PIXELS
            val pixelY = preparedSurfaceColumn.localZ * MAXIMUM_RENDER_BLOCK_PIXELS
            preparedSurfaceColumn.layers.asReversed().forEach { preparedSurfaceLayer ->
                currentCoroutineContext().ensureActive()
                val sprite = preparedSurfaceLayer.sprite
                if (sprite == null) {
                    context.fillStyle = MISSING_TEXTURE_COLOR
                    context.fillRect(pixelX, pixelY, MAXIMUM_RENDER_BLOCK_PIXELS, MAXIMUM_RENDER_BLOCK_PIXELS)
                } else {
                    context.drawImage(
                        sprite,
                        pixelX,
                        pixelY,
                        MAXIMUM_RENDER_BLOCK_PIXELS,
                        MAXIMUM_RENDER_BLOCK_PIXELS,
                    )
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return CachedChunkTile(
            timestampEpochSeconds = timestampEpochSeconds,
            assetRevision = assetRevision,
            canvas = tile.unsafeCast<Any>(),
            byteSize = chunkPixelSide.toLong() * chunkPixelSide * RGBA_COMPONENT_COUNT,
        )
    }

    private suspend fun visibleRenderActions(
        surfaces: Map<ChunkCoordinate, SurfaceChunkSnapshot>,
        canvasViewport: CanvasViewport,
    ): List<ChunkRenderAction> = buildList {
        surfaces.entries.forEachIndexed { surfaceIndex, (chunkCoordinate, surfaceChunkSnapshot) ->
            if (surfaceIndex % RENDER_YIELD_INTERVAL == 0) yield()
            currentCoroutineContext().ensureActive()
            val rectangle = chunkRectangle(chunkCoordinate, canvasViewport)
            if (rectangle.intersects(canvasViewport.width, canvasViewport.height)) {
                add(ChunkRenderAction(chunkCoordinate, surfaceChunkSnapshot, rectangle))
            }
        }
    }

    private fun captureCanvasViewport(): CanvasViewport {
        val width = map.getSize().x.unsafeCast<Double>().coerceAtLeast(1.0)
        val height = map.getSize().y.unsafeCast<Double>().coerceAtLeast(1.0)
        val devicePixelRatio = max(browserWindow.devicePixelRatio.unsafeCast<Double>(), 1.0)
        val topLeft = map.containerPointToLatLng(Leaflet.point(0.0, 0.0))
        val bottomRight = map.containerPointToLatLng(Leaflet.point(width, height))
        return CanvasViewport(
            topLeftLatitude = topLeft.lat.unsafeCast<Double>(),
            topLeftLongitude = topLeft.lng.unsafeCast<Double>(),
            bottomRightLatitude = bottomRight.lat.unsafeCast<Double>(),
            bottomRightLongitude = bottomRight.lng.unsafeCast<Double>(),
            width = width,
            height = height,
            physicalWidth = ceil(width * devicePixelRatio).toInt(),
            physicalHeight = ceil(height * devicePixelRatio).toInt(),
            devicePixelRatio = devicePixelRatio,
        )
    }

    private fun seedWorkingCanvas(
        context: dynamic,
        newCanvasViewport: CanvasViewport,
        dimensionId: DimensionId,
    ) {
        val oldCanvasViewport = canvasViewport ?: return
        if (canvasDimensionId != dimensionId || canvas.width.unsafeCast<Int>() == 0) return
        val topLeft = newCanvasViewport.project(
            oldCanvasViewport.topLeftLatitude,
            oldCanvasViewport.topLeftLongitude,
        )
        val bottomRight = newCanvasViewport.project(
            oldCanvasViewport.bottomRightLatitude,
            oldCanvasViewport.bottomRightLongitude,
        )
        context.drawImage(
            canvas,
            0,
            0,
            oldCanvasViewport.physicalWidth,
            oldCanvasViewport.physicalHeight,
            topLeft.x,
            topLeft.y,
            bottomRight.x - topLeft.x,
            bottomRight.y - topLeft.y,
        )
    }

    private fun chunkRectangle(
        chunkCoordinate: ChunkCoordinate,
        canvasViewport: CanvasViewport,
    ): CanvasRectangle {
        val chunkPosition = chunkCoordinate.chunkPosition
        val blockXRange = MinecraftCoordinates.blockXRange(chunkPosition)
        val blockZRange = MinecraftCoordinates.blockZRange(chunkPosition)
        val minimumBlockX = blockXRange.first.toDouble()
        val minimumBlockZ = blockZRange.first.toDouble()
        val maximumBlockX = MinecraftCoordinates.offsetBlockCoordinate(blockXRange.last, 1).toDouble()
        val maximumBlockZ = MinecraftCoordinates.offsetBlockCoordinate(blockZRange.last, 1).toDouble()
        val firstPoint = canvasViewport.project(-minimumBlockZ, minimumBlockX)
        val secondPoint = canvasViewport.project(-maximumBlockZ, maximumBlockX)
        return CanvasRectangle(
            left = min(firstPoint.x, secondPoint.x),
            top = min(firstPoint.y, secondPoint.y),
            right = max(firstPoint.x, secondPoint.x),
            bottom = max(firstPoint.y, secondPoint.y),
        )
    }

    private fun publishWorkingCanvas(
        working: dynamic,
        newCanvasViewport: CanvasViewport,
        dimensionId: DimensionId,
    ) {
        canvas.width = newCanvasViewport.physicalWidth
        canvas.height = newCanvasViewport.physicalHeight
        canvas.style.width = "${newCanvasViewport.width}px"
        canvas.style.height = "${newCanvasViewport.height}px"
        val context: dynamic = canvas.getContext("2d")
        context.imageSmoothingEnabled = false
        context.setTransform(1, 0, 0, 1, 0, 0)
        context.drawImage(working, 0, 0)
        context.setTransform(
            newCanvasViewport.devicePixelRatio,
            0,
            0,
            newCanvasViewport.devicePixelRatio,
            0,
            0,
        )
        canvasViewport = newCanvasViewport
        canvasDimensionId = dimensionId
        updateLocalTransform()
    }

    private fun updateLocalTransform() {
        val currentCanvasViewport = canvasViewport ?: return
        val topLeft = map.latLngToContainerPoint(
            Leaflet.latLng(currentCanvasViewport.topLeftLatitude, currentCanvasViewport.topLeftLongitude),
        )
        val bottomRight = map.latLngToContainerPoint(
            Leaflet.latLng(currentCanvasViewport.bottomRightLatitude, currentCanvasViewport.bottomRightLongitude),
        )
        val canvasTransform = calculateCanvasTransform(
            referenceWidth = currentCanvasViewport.width,
            referenceHeight = currentCanvasViewport.height,
            transformedLeft = topLeft.x.unsafeCast<Double>(),
            transformedTop = topLeft.y.unsafeCast<Double>(),
            transformedRight = bottomRight.x.unsafeCast<Double>(),
            transformedBottom = bottomRight.y.unsafeCast<Double>(),
        )
        canvas.style.transform =
            "translate3d(${canvasTransform.translationX}px, ${canvasTransform.translationY}px, 0) scale(${canvasTransform.scaleX})"
    }

    private fun trimTileCache() {
        while (tileCache.size > MAXIMUM_CACHED_TILES || tileCacheBytes > MAXIMUM_TILE_CACHE_BYTES) {
            val oldestKey = tileCache.keys.firstOrNull() ?: return
            tileCache.remove(oldestKey)?.let { cachedChunkTile -> tileCacheBytes -= cachedChunkTile.byteSize }
        }
    }

    private fun cachedChunkTile(chunkCanvasKey: ChunkCanvasKey): CachedChunkTile? {
        val cachedChunkTile = tileCache.remove(chunkCanvasKey) ?: return null
        tileCache[chunkCanvasKey] = cachedChunkTile
        return cachedChunkTile
    }

    private fun cacheChunkTile(chunkCanvasKey: ChunkCanvasKey, cachedChunkTile: CachedChunkTile) {
        tileCache.remove(chunkCanvasKey)?.let { replacedChunkTile -> tileCacheBytes -= replacedChunkTile.byteSize }
        tileCache[chunkCanvasKey] = cachedChunkTile
        tileCacheBytes += cachedChunkTile.byteSize
        trimTileCache()
    }

    private fun drawChunkTile(context: dynamic, canvasRectangle: CanvasRectangle, cachedChunkTile: CachedChunkTile) {
        context.clearRect(canvasRectangle.left, canvasRectangle.top, canvasRectangle.width, canvasRectangle.height)
        context.drawImage(
            cachedChunkTile.canvas,
            canvasRectangle.left,
            canvasRectangle.top,
            canvasRectangle.width,
            canvasRectangle.height,
        )
    }

}

data class ChunkRenderProgress(
    val selection: ViewportSelection? = null,
    val renderedChunkCount: Int = 0,
)

private data class CanvasViewport(
    val topLeftLatitude: Double,
    val topLeftLongitude: Double,
    val bottomRightLatitude: Double,
    val bottomRightLongitude: Double,
    val width: Double,
    val height: Double,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val devicePixelRatio: Double,
) {
    fun project(latitude: Double, longitude: Double): CanvasPoint = CanvasPoint(
        x = (longitude - topLeftLongitude) / (bottomRightLongitude - topLeftLongitude) * width,
        y = (latitude - topLeftLatitude) / (bottomRightLatitude - topLeftLatitude) * height,
    )
}

private data class CanvasPoint(
    val x: Double,
    val y: Double,
)

private data class ChunkCanvasKey(
    val dimensionId: DimensionId,
    val chunkCoordinate: ChunkCoordinate,
)

private data class CachedChunkTile(
    val timestampEpochSeconds: Int,
    val assetRevision: String,
    val canvas: Any,
    val byteSize: Long,
)

private data class ChunkRenderAction(
    val chunkCoordinate: ChunkCoordinate,
    val surfaceChunkSnapshot: SurfaceChunkSnapshot,
    val rectangle: CanvasRectangle,
)

private data class CanvasRenderInput(
    val displayedSelection: ViewportSelection,
    val surfaces: Map<ChunkCoordinate, SurfaceChunkSnapshot>,
    val assetRevision: String?,
) {
    companion object {
        fun from(
            viewportRenderState: ViewportRenderState,
            officialAssetSession: OfficialAssetSession?,
        ): CanvasRenderInput = CanvasRenderInput(
            displayedSelection = checkNotNull(viewportRenderState.displayedSelection),
            surfaces = viewportRenderState.surfaces,
            assetRevision = officialAssetSession?.assetRevision,
        )
    }
}

private data class CanvasRenderUpdate(
    val canvasRenderInput: CanvasRenderInput,
    val officialAssetSession: OfficialAssetSession?,
)

private fun Channel<CanvasRenderUpdate>.drainLatest(current: CanvasRenderUpdate): CanvasRenderUpdate {
    var latest = current
    while (true) latest = tryReceive().getOrNull() ?: return latest
}

private data class PreparedSurfaceColumn(
    val localX: Int,
    val localZ: Int,
    val layers: List<PreparedSurfaceLayer>,
)

private data class SurfaceColumnInput(
    val localX: Int,
    val localZ: Int,
    val blockX: Int,
    val blockZ: Int,
    val blocks: List<SurfaceBlockState>,
)

private data class PreparedSurfaceLayer(
    val sprite: Any?,
)

private data class CanvasRectangle(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double
        get() = right - left

    val height: Double
        get() = bottom - top

    fun intersects(canvasWidth: Double, canvasHeight: Double): Boolean =
        right >= 0.0 && bottom >= 0.0 && left <= canvasWidth && top <= canvasHeight
}

private val browserWindow: dynamic = js("window")
private val browserDocument: dynamic = js("document")
private val MAXIMUM_RENDER_BLOCK_PIXELS: Int = pixelsPerBlock(MAX_MAP_ZOOM)
private const val BLACK_FALLBACK_ASSET_REVISION: String = "black-fallback"
private const val MAXIMUM_CACHED_TILES: Int = 2_048
private const val MAXIMUM_TILE_CACHE_BYTES: Long = 256L * 1024L * 1024L
private const val RGBA_COMPONENT_COUNT: Long = 4L
private const val RENDER_YIELD_INTERVAL: Int = 8
private const val MISSING_TEXTURE_COLOR: String = "#000000"
