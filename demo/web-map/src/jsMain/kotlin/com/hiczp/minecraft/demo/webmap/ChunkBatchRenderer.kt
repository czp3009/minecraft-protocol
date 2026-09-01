@file:Suppress("UnsafeCastFromDynamic")

package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.CHUNK_SIDE
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.MinecraftCoordinates
import kotlin.math.*

internal class ChunkBatchRenderer(
    private val map: dynamic,
) {
    private val canvas: dynamic = browserDocument.createElement("canvas")
    private val tileCache = linkedMapOf<ChunkTileKey, CachedChunkTile>()
    private var tileCacheBytes = 0L
    private var renderedState = ViewportRenderState()
    private var assetSession: OfficialAssetSession? = null
    private var generation = 0L
    private var animationFrame: Int? = null
    private var canvasViewport: CanvasViewport? = null
    private var replacementCanvas: dynamic = null
    private var assetRefreshTimer: Int? = null
    private var interacting = false
    private var assetsDirty = false

    init {
        canvas.className = "world-map-canvas"
        canvas.setAttribute("aria-hidden", "true")
        map.getContainer().appendChild(canvas)
        BrowserConsole.debug("Canvas renderer initialized.")
    }

    fun render(viewportRenderState: ViewportRenderState, officialAssetSession: OfficialAssetSession?) {
        val previousState = renderedState
        renderedState = viewportRenderState
        assetSession = officialAssetSession
        if (viewportRenderState.displayedSelection == null) {
            clearCanvasAndCache()
        } else if (viewportRenderState.loading) {
            cancelRender()
        } else if (assetsDirty) {
            cancelAssetRefresh()
            consumeDirtyAssets()
            scheduleRender()
        } else if (
            viewportRenderState.rejection == null &&
            viewportRenderState.callFailure == null &&
            (
                    previousState.loading ||
                            previousState.displayedSelection != viewportRenderState.displayedSelection ||
                            previousState.surfaces != viewportRenderState.surfaces
                    )
        ) {
            scheduleRender()
        } else {
            updateLocalTransform()
        }
    }

    fun interactionStarted() {
        interacting = true
        cancelAssetRefresh()
        cancelRender()
        updateLocalTransform()
    }

    fun mapTransformed() {
        interacting = true
        cancelAssetRefresh()
        cancelRender()
        updateLocalTransform()
    }

    fun viewportSettled() {
        interacting = false
        cancelAssetRefresh()
        consumeDirtyAssets()
        scheduleRender()
    }

    fun clearForDimensionChange() {
        interacting = false
        cancelAssetRefresh()
        clearCanvasAndCache()
    }

    fun assetsChanged() {
        assetsDirty = true
        if (interacting || renderedState.loading || renderedState.displayedSelection == null) return
        scheduleAssetRefresh()
    }

    fun close() {
        BrowserConsole.debug("Closing the Canvas renderer.")
        cancelRender()
        cancelAssetRefresh()
        canvas.remove()
        clearTileCache()
        canvasViewport = null
    }

    private fun scheduleRender() {
        generation++
        val currentGeneration = generation
        animationFrame?.let { frame -> browserWindow.cancelAnimationFrame(frame) }
        removeReplacementCanvas()
        animationFrame = browserWindow.requestAnimationFrame { _: Double ->
            animationFrame = null
            beginRender(currentGeneration)
        }.unsafeCast<Int>()
    }

    private fun beginRender(currentGeneration: Long) {
        if (currentGeneration != generation) return
        val displayedSelection = renderedState.displayedSelection ?: return
        val width = map.getSize().x.unsafeCast<Double>().coerceAtLeast(1.0)
        val height = map.getSize().y.unsafeCast<Double>().coerceAtLeast(1.0)
        val devicePixelRatio = max(browserWindow.devicePixelRatio.unsafeCast<Double>(), 1.0)
        val physicalWidth = ceil(width * devicePixelRatio).toInt()
        val physicalHeight = ceil(height * devicePixelRatio).toInt()
        val topLeft = map.containerPointToLatLng(Leaflet.point(0.0, 0.0))
        val bottomRight = map.containerPointToLatLng(Leaflet.point(width, height))
        val newCanvasViewport = CanvasViewport(
            topLeftLatitude = topLeft.lat.unsafeCast<Double>(),
            topLeftLongitude = topLeft.lng.unsafeCast<Double>(),
            bottomRightLatitude = bottomRight.lat.unsafeCast<Double>(),
            bottomRightLongitude = bottomRight.lng.unsafeCast<Double>(),
            width = width,
            height = height,
            physicalWidth = physicalWidth,
            physicalHeight = physicalHeight,
            devicePixelRatio = devicePixelRatio,
        )
        val working: dynamic = browserDocument.createElement("canvas")
        working.width = physicalWidth
        working.height = physicalHeight
        val workingContext: dynamic = working.getContext("2d")
        workingContext.imageSmoothingEnabled = false
        workingContext.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0)
        val predictionBounds = currentPredictionBounds()
        val tasks = visibleChunkTasks(displayedSelection, renderedState.surfaces, width, height, predictionBounds)
        val blockPixels = pixelsPerBlock(map.getZoom().unsafeCast<Int>())
        val renderMetrics = RenderMetrics(browserWindow.performance.now().unsafeCast<Double>())
        val newReplacementCanvas: dynamic = browserDocument.createElement("canvas")
        newReplacementCanvas.className = "world-map-canvas world-map-replacement-canvas"
        newReplacementCanvas.setAttribute("aria-hidden", "true")
        newReplacementCanvas.width = physicalWidth
        newReplacementCanvas.height = physicalHeight
        newReplacementCanvas.style.width = "${width}px"
        newReplacementCanvas.style.height = "${height}px"
        newReplacementCanvas.style.transform = "none"
        map.getContainer().appendChild(newReplacementCanvas)
        replacementCanvas = newReplacementCanvas
        BrowserConsole.debug(
            "Render generation $currentGeneration started with ${tasks.size} visible Chunk tasks and ${tileCache.size} cached tiles.",
        )
        renderBatch(
            currentGeneration = currentGeneration,
            dimensionId = displayedSelection.dimensionId,
            blockPixels = blockPixels,
            tasks = tasks,
            nextIndex = 0,
            working = working,
            workingContext = workingContext,
            canvasViewport = newCanvasViewport,
            renderMetrics = renderMetrics,
        )
    }

    private fun renderBatch(
        currentGeneration: Long,
        dimensionId: DimensionId,
        blockPixels: Int,
        tasks: List<ChunkRenderTask>,
        nextIndex: Int,
        working: dynamic,
        workingContext: dynamic,
        canvasViewport: CanvasViewport,
        renderMetrics: RenderMetrics,
    ) {
        if (currentGeneration != generation) return
        val startedAt = browserWindow.performance.now().unsafeCast<Double>()
        var index = nextIndex
        while (index < tasks.size && index - nextIndex < MAX_CHUNKS_PER_BATCH) {
            drawChunkTask(workingContext, dimensionId, blockPixels, tasks[index], renderMetrics)
            index++
            if (browserWindow.performance.now().unsafeCast<Double>() - startedAt >= FRAME_BUDGET_MILLISECONDS) break
        }
        publishReplacementBatch(working, tasks.subList(nextIndex, index), canvasViewport)
        if (nextIndex == 0) {
            renderMetrics.firstBatchMilliseconds =
                browserWindow.performance.now().unsafeCast<Double>() - renderMetrics.startedAt
        }
        if (index < tasks.size) {
            animationFrame = browserWindow.requestAnimationFrame { _: Double ->
                animationFrame = null
                renderBatch(
                    currentGeneration,
                    dimensionId,
                    blockPixels,
                    tasks,
                    index,
                    working,
                    workingContext,
                    canvasViewport,
                    renderMetrics,
                )
            }.unsafeCast<Int>()
        } else {
            publishWorkingCanvas(working, canvasViewport)
            val elapsedMilliseconds = browserWindow.performance.now().unsafeCast<Double>() - renderMetrics.startedAt
            BrowserConsole.debug(
                "Render generation $currentGeneration published its first batch after ${renderMetrics.firstBatchMilliseconds.roundToInt()} ms and completed ${tasks.size} Chunk tasks after ${elapsedMilliseconds.roundToInt()} ms with ${renderMetrics.cacheHits} tile hits, ${renderMetrics.cacheMisses} tile misses, ${renderMetrics.spriteHits} sprite hits, and ${renderMetrics.spriteMisses} sprite misses (sample: ${renderMetrics.leadingMissingSprites()}); the tile cache contains ${tileCache.size} entries using ${tileCacheBytes / KIBIBYTE} KiB.",
            )
            if (assetsDirty && !interacting && !renderedState.loading) scheduleAssetRefresh()
        }
    }

    private fun drawChunkTask(
        context: dynamic,
        dimensionId: DimensionId,
        blockPixels: Int,
        task: ChunkRenderTask,
        renderMetrics: RenderMetrics,
    ) {
        val rectangle = task.rectangle
        context.clearRect(rectangle.left, rectangle.top, rectangle.width, rectangle.height)
        val chunkSurface = task.chunkSurface ?: return
        val revision = assetSession?.assetRevision ?: COLOR_ASSET_REVISION
        val tileKey = ChunkTileKey(dimensionId, task.chunkCoordinate, chunkSurface, revision, blockPixels)
        val cachedChunkTile = tileCache[tileKey]
        val tile = cachedChunkTile ?: renderChunkTile(
            task.chunkCoordinate,
            chunkSurface,
            blockPixels,
            renderMetrics
        ).also { renderedTile ->
            tileCache[tileKey] = renderedTile
            tileCacheBytes += renderedTile.byteSize
            trimTileCache()
        }
        if (cachedChunkTile == null) renderMetrics.cacheMisses++ else renderMetrics.cacheHits++
        context.imageSmoothingEnabled = false
        context.drawImage(tile.canvas, rectangle.left, rectangle.top, rectangle.width, rectangle.height)
    }

    private fun renderChunkTile(
        chunkCoordinate: ChunkCoordinate,
        chunkSurface: ChunkSurface,
        blockPixels: Int,
        renderMetrics: RenderMetrics,
    ): CachedChunkTile {
        val chunkPixelSide = CHUNK_SIDE * blockPixels
        val tile: dynamic = browserDocument.createElement("canvas")
        tile.width = chunkPixelSide
        tile.height = chunkPixelSide
        val context: dynamic = tile.getContext("2d")
        context.imageSmoothingEnabled = false
        var complete = true
        val blockXRange = MinecraftCoordinates.blockXRange(chunkCoordinate.chunkPosition)
        val blockZRange = MinecraftCoordinates.blockZRange(chunkCoordinate.chunkPosition)
        for (localZ in 0 until CHUNK_SIDE) {
            for (localX in 0 until CHUNK_SIDE) {
                val surfaceBlockState = chunkSurface[localX, localZ] ?: continue
                val pixelX = localX * blockPixels
                val pixelY = localZ * blockPixels
                val blockX = MinecraftCoordinates.offsetBlockCoordinate(blockXRange.first, localX)
                val blockZ = MinecraftCoordinates.offsetBlockCoordinate(blockZRange.first, localZ)
                context.fillStyle = deterministicBlockColor(surfaceBlockState)
                context.fillRect(pixelX, pixelY, blockPixels, blockPixels)
                val sprite = assetSession?.requestSprite(surfaceBlockState, blockX, blockZ)
                if (sprite == null) {
                    complete = false
                    renderMetrics.recordMissingSprite(surfaceBlockState.name.toString())
                } else {
                    renderMetrics.spriteHits++
                    context.drawImage(
                        sprite,
                        pixelX,
                        pixelY,
                        blockPixels,
                        blockPixels,
                    )
                }
            }
        }
        return CachedChunkTile(
            canvas = tile.unsafeCast<Any>(),
            byteSize = chunkPixelSide.toLong() * chunkPixelSide * RGBA_COMPONENT_COUNT,
            complete = complete,
        )
    }

    private fun visibleChunkTasks(
        viewportSelection: ViewportSelection,
        surfaces: Map<ChunkCoordinate, ChunkSurface>,
        canvasWidth: Double,
        canvasHeight: Double,
        predictionBounds: CanvasRectangle?,
    ): List<ChunkRenderTask> {
        val center = map.getCenter()
        val centerChunkX = MinecraftCoordinates.chunkCoordinate(
            MinecraftCoordinates.blockCoordinate(center.lng.unsafeCast<Double>()),
        )
        val centerChunkZ = MinecraftCoordinates.chunkCoordinate(
            MinecraftCoordinates.blockCoordinate(-center.lat.unsafeCast<Double>()),
        )
        return buildList {
            viewportSelection.chunkViewport.chunkRange.forEach { chunkPosition ->
                val chunkCoordinate = ChunkCoordinate.from(chunkPosition)
                val rectangle = chunkRectangle(chunkCoordinate)
                if (
                    rectangle.right >= 0.0 &&
                    rectangle.bottom >= 0.0 &&
                    rectangle.left <= canvasWidth &&
                    rectangle.top <= canvasHeight
                ) {
                    add(
                        ChunkRenderTask(
                            chunkCoordinate = chunkCoordinate,
                            chunkSurface = surfaces[chunkCoordinate],
                            rectangle = rectangle,
                            replacementPriority = if (predictionBounds?.contains(rectangle) == true) 1 else 0,
                            centerDistance = abs(chunkCoordinate.chunkX - centerChunkX) + abs(chunkCoordinate.chunkZ - centerChunkZ),
                        ),
                    )
                }
            }
        }.sortedWith(
            compareBy(
                ChunkRenderTask::replacementPriority,
                ChunkRenderTask::centerDistance,
                { task -> task.chunkCoordinate.chunkZ },
                { task -> task.chunkCoordinate.chunkX },
            ),
        )
    }

    private fun chunkRectangle(chunkCoordinate: ChunkCoordinate): CanvasRectangle {
        val chunkPosition = chunkCoordinate.chunkPosition
        val blockXRange = MinecraftCoordinates.blockXRange(chunkPosition)
        val blockZRange = MinecraftCoordinates.blockZRange(chunkPosition)
        val minimumBlockX = blockXRange.first.toDouble()
        val minimumBlockZ = blockZRange.first.toDouble()
        val maximumBlockX = MinecraftCoordinates.offsetBlockCoordinate(blockXRange.last, 1).toDouble()
        val maximumBlockZ = MinecraftCoordinates.offsetBlockCoordinate(blockZRange.last, 1).toDouble()
        val firstPoint = map.latLngToContainerPoint(Leaflet.latLng(-minimumBlockZ, minimumBlockX))
        val secondPoint = map.latLngToContainerPoint(Leaflet.latLng(-maximumBlockZ, maximumBlockX))
        val firstX = firstPoint.x.unsafeCast<Double>()
        val firstY = firstPoint.y.unsafeCast<Double>()
        val secondX = secondPoint.x.unsafeCast<Double>()
        val secondY = secondPoint.y.unsafeCast<Double>()
        return CanvasRectangle(
            left = min(firstX, secondX),
            top = min(firstY, secondY),
            right = max(firstX, secondX),
            bottom = max(firstY, secondY),
        )
    }

    private fun publishWorkingCanvas(working: dynamic, newCanvasViewport: CanvasViewport) {
        canvas.width = newCanvasViewport.physicalWidth
        canvas.height = newCanvasViewport.physicalHeight
        canvas.style.width = "${newCanvasViewport.width}px"
        canvas.style.height = "${newCanvasViewport.height}px"
        val context: dynamic = canvas.getContext("2d")
        context.setTransform(1, 0, 0, 1, 0, 0)
        context.imageSmoothingEnabled = false
        context.drawImage(working, 0, 0)
        canvasViewport = newCanvasViewport
        removeReplacementCanvas()
        updateLocalTransform()
    }

    private fun publishReplacementBatch(
        working: dynamic,
        tasks: List<ChunkRenderTask>,
        currentCanvasViewport: CanvasViewport,
    ) {
        val currentReplacementCanvas = replacementCanvas ?: return
        val context: dynamic = currentReplacementCanvas.getContext("2d")
        context.imageSmoothingEnabled = false
        tasks.forEach { task ->
            val left = floor(max(task.rectangle.left, 0.0) * currentCanvasViewport.devicePixelRatio).toInt()
            val top = floor(max(task.rectangle.top, 0.0) * currentCanvasViewport.devicePixelRatio).toInt()
            val right = ceil(
                min(
                    task.rectangle.right,
                    currentCanvasViewport.width
                ) * currentCanvasViewport.devicePixelRatio
            ).toInt()
            val bottom = ceil(
                min(
                    task.rectangle.bottom,
                    currentCanvasViewport.height
                ) * currentCanvasViewport.devicePixelRatio
            ).toInt()
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                context.clearRect(left, top, width, height)
                context.drawImage(working, left, top, width, height, left, top, width, height)
            }
        }
    }

    private fun currentPredictionBounds(): CanvasRectangle? {
        val currentCanvasViewport = canvasViewport ?: return null
        val topLeft = map.latLngToContainerPoint(
            Leaflet.latLng(currentCanvasViewport.topLeftLatitude, currentCanvasViewport.topLeftLongitude),
        )
        val bottomRight = map.latLngToContainerPoint(
            Leaflet.latLng(currentCanvasViewport.bottomRightLatitude, currentCanvasViewport.bottomRightLongitude),
        )
        val firstX = topLeft.x.unsafeCast<Double>()
        val firstY = topLeft.y.unsafeCast<Double>()
        val secondX = bottomRight.x.unsafeCast<Double>()
        val secondY = bottomRight.y.unsafeCast<Double>()
        return CanvasRectangle(
            left = min(firstX, secondX),
            top = min(firstY, secondY),
            right = max(firstX, secondX),
            bottom = max(firstY, secondY),
        )
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

    private fun clearCanvasAndCache() {
        cancelRender()
        canvas.width = 0
        canvas.height = 0
        canvas.style.width = "0px"
        canvas.style.height = "0px"
        canvas.style.transform = "none"
        canvasViewport = null
        clearTileCache()
    }

    private fun cancelRender() {
        val cancelledGeneration = animationFrame != null || replacementCanvas != null
        generation++
        animationFrame?.let { frame -> browserWindow.cancelAnimationFrame(frame) }
        animationFrame = null
        removeReplacementCanvas()
        if (cancelledGeneration) BrowserConsole.debug("Cancelled the in-browser Canvas replacement batch.")
    }

    private fun removeReplacementCanvas() {
        replacementCanvas?.remove()
        replacementCanvas = null
    }

    private fun cancelAssetRefresh() {
        assetRefreshTimer?.let { timer -> browserWindow.clearTimeout(timer) }
        assetRefreshTimer = null
    }

    private fun scheduleAssetRefresh() {
        cancelAssetRefresh()
        assetRefreshTimer = browserWindow.setTimeout({
            assetRefreshTimer = null
            if (replacementCanvas == null && animationFrame == null) {
                consumeDirtyAssets()
                scheduleRender()
            }
        }, ASSET_REFRESH_DEBOUNCE_MILLISECONDS).unsafeCast<Int>()
    }

    private fun trimTileCache() {
        while (tileCache.size > MAXIMUM_CACHED_TILES || tileCacheBytes > MAXIMUM_TILE_CACHE_BYTES) {
            val oldestKey = tileCache.keys.firstOrNull() ?: return
            tileCache.remove(oldestKey)?.let { cachedChunkTile -> tileCacheBytes -= cachedChunkTile.byteSize }
        }
    }

    private fun clearTileCache() {
        tileCache.clear()
        tileCacheBytes = 0L
    }

    private fun consumeDirtyAssets() {
        if (!assetsDirty) return
        assetsDirty = false
        val incompleteKeys = tileCache.filterValues { cachedChunkTile -> !cachedChunkTile.complete }.keys.toList()
        incompleteKeys.forEach { chunkTileKey ->
            tileCache.remove(chunkTileKey)?.let { cachedChunkTile -> tileCacheBytes -= cachedChunkTile.byteSize }
        }
    }
}

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
)

private data class ChunkTileKey(
    val dimensionId: DimensionId,
    val chunkCoordinate: ChunkCoordinate,
    val chunkSurface: ChunkSurface,
    val assetRevision: String,
    val blockPixels: Int,
)

private data class CachedChunkTile(
    val canvas: Any,
    val byteSize: Long,
    val complete: Boolean,
)

private data class RenderMetrics(
    val startedAt: Double,
    var firstBatchMilliseconds: Double = 0.0,
    var cacheHits: Int = 0,
    var cacheMisses: Int = 0,
    var spriteHits: Int = 0,
    var spriteMisses: Int = 0,
    val missingSpriteCounts: MutableMap<String, Int> = mutableMapOf(),
) {
    fun recordMissingSprite(name: String) {
        spriteMisses++
        if (
            spriteMisses <= MISSING_SPRITE_SAMPLE_LIMIT &&
            (name in missingSpriteCounts || missingSpriteCounts.size < MISSING_SPRITE_SUMMARY_LIMIT)
        ) {
            missingSpriteCounts[name] = missingSpriteCounts.getOrElse(name) { 0 } + 1
        }
    }

    fun leadingMissingSprites(): String = missingSpriteCounts.entries.sortedByDescending { entry -> entry.value }
        .take(MISSING_SPRITE_SUMMARY_LIMIT)
        .joinToString { entry -> "${entry.key}=${entry.value}" }
        .ifEmpty { "none" }
}

private data class ChunkRenderTask(
    val chunkCoordinate: ChunkCoordinate,
    val chunkSurface: ChunkSurface?,
    val rectangle: CanvasRectangle,
    val replacementPriority: Int,
    val centerDistance: Int,
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

    fun contains(canvasRectangle: CanvasRectangle): Boolean =
        canvasRectangle.left >= left &&
                canvasRectangle.top >= top &&
                canvasRectangle.right <= right &&
                canvasRectangle.bottom <= bottom
}

private fun deterministicBlockColor(surfaceBlockState: SurfaceBlockState): String {
    val hash = stableAssetHash(surfaceBlockState.canonicalAssetKey())
    val hue = hash % 360
    val saturation = 36 + hash / 360 % 34
    val lightness = 32 + hash / (360 * 34) % 30
    return "hsl($hue $saturation% $lightness%)"
}

private val browserWindow: dynamic = js("window")
private val browserDocument: dynamic = js("document")
private const val COLOR_ASSET_REVISION: String = "deterministic-colors"
private const val MAXIMUM_CACHED_TILES: Int = 2_048
private const val MAXIMUM_TILE_CACHE_BYTES: Long = 64L * 1024L * 1024L
private const val MAX_CHUNKS_PER_BATCH: Int = 128
private const val FRAME_BUDGET_MILLISECONDS: Double = 6.0
private const val ASSET_REFRESH_DEBOUNCE_MILLISECONDS: Int = 250
private const val RGBA_COMPONENT_COUNT: Long = 4L
private const val KIBIBYTE: Long = 1024L
private const val MISSING_SPRITE_SUMMARY_LIMIT: Int = 8
private const val MISSING_SPRITE_SAMPLE_LIMIT: Int = 4_096
