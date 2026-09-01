@file:Suppress("UnsafeCastFromDynamic")

package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.world.format.DimensionId
import io.ktor.client.*
import io.ktor.client.engine.js.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlin.math.max
import kotlin.math.min

fun main() {
    BrowserConsole.info("Starting the browser client.")
    val coroutineScope = MainScope()
    val startupPhaseElement: dynamic = browserDocument.getElementById("startup-phase")
    val startupPhaseLabelElement: dynamic = browserDocument.getElementById("startup-phase-label")
    val startupTitleElement: dynamic = browserDocument.getElementById("startup-title")
    val startupStepElement: dynamic = browserDocument.getElementById("asset-load-step")
    val startupActionElement: dynamic = browserDocument.getElementById("asset-load-action")
    val startupCountElement: dynamic = browserDocument.getElementById("asset-load-count")
    val startupProgressElement: dynamic = browserDocument.getElementById("asset-load-progress")
    val retryAssetsButton: dynamic = browserDocument.getElementById("retry-assets")
    val mapElement: dynamic = browserDocument.getElementById("map")
    val mapStatusElement: dynamic = browserDocument.getElementById("map-status")
    val coordinateElement: dynamic = browserDocument.getElementById("coordinate-text")
    val httpClient = HttpClient(Js) {
        installKrpc {
            serialization {
                json(WebMapJson)
            }
        }
    }
    var rpcClient: KtorRpcClient? = null
    var viewportController: ViewportController? = null
    var renderer: ChunkBatchRenderer? = null
    var assetSessionManager: OfficialAssetSessionManager? = null
    var map: dynamic = null
    var selectedDimensionId: DimensionId? = null
    val assetProgressConsoleReporter = AssetProgressConsoleReporter()
    val viewportConsoleReporter = ViewportConsoleReporter()

    fun showStartupMessage(
        phaseLabel: String,
        title: String,
        step: String,
        action: String,
        detail: String,
        indeterminate: Boolean,
    ) {
        startupPhaseElement.hidden = false
        mapElement.hidden = true
        mapStatusElement.hidden = true
        startupPhaseLabelElement.textContent = phaseLabel
        startupTitleElement.textContent = title
        startupStepElement.textContent = step
        startupActionElement.textContent = action
        startupCountElement.textContent = detail
        retryAssetsButton.hidden = true
        startupProgressElement.max = OFFICIAL_ASSET_LOAD_STEPS
        if (indeterminate) {
            startupProgressElement.removeAttribute("value")
        } else if (!startupProgressElement.hasAttribute("value").unsafeCast<Boolean>()) {
            startupProgressElement.value = 0
        }
    }

    fun updateCoordinate() {
        val currentMap = map ?: return
        val center = currentMap.getCenter()
        val zoom = currentMap.getZoom().unsafeCast<Int>()
        coordinateElement.textContent = "Center X ${
            center.lng.unsafeCast<Double>().toInt()
        } · Z ${(-center.lat.unsafeCast<Double>()).toInt()} · Scale ${pixelsPerBlock(zoom)}×"
    }

    fun selectCurrentViewport(restartDebounce: Boolean = false) {
        val currentMap = map ?: return
        val currentDimensionId = selectedDimensionId ?: return
        val currentViewportController = viewportController ?: return
        val mapSize = currentMap.getSize()
        val first = currentMap.containerPointToLatLng(Leaflet.point(0.0, 0.0))
        val second = currentMap.containerPointToLatLng(
            Leaflet.point(mapSize.x.unsafeCast<Double>(), mapSize.y.unsafeCast<Double>()),
        )
        val firstBlockX = first.lng.unsafeCast<Double>()
        val secondBlockX = second.lng.unsafeCast<Double>()
        val firstBlockZ = -first.lat.unsafeCast<Double>()
        val secondBlockZ = -second.lat.unsafeCast<Double>()
        val visibleBlockBounds = VisibleBlockBounds(
            minBlockX = min(firstBlockX, secondBlockX),
            minBlockZ = min(firstBlockZ, secondBlockZ),
            maxBlockX = max(firstBlockX, secondBlockX),
            maxBlockZ = max(firstBlockZ, secondBlockZ),
        )
        currentViewportController.select(
            ViewportSelection(currentDimensionId, visibleBlockBounds.toChunkViewport()),
            restartDebounce = restartDebounce,
        )
        updateCoordinate()
    }

    fun enterMapPhase(worldMetadata: WorldMetadata, webMapService: WebMapService) {
        val initialDimensionId =
            worldMetadata.dimensionIds.firstOrNull { dimensionId -> dimensionId == DimensionId.Overworld }
                ?: worldMetadata.dimensionIds.first()
        selectedDimensionId = initialDimensionId
        startupPhaseElement.hidden = true
        mapElement.hidden = false
        mapStatusElement.hidden = false
        BrowserConsole.info("Initializing the interactive map.")
        val mapOptions = js("({})")
        mapOptions.crs = Leaflet.CRS.Simple
        mapOptions.minZoom = MIN_MAP_ZOOM
        mapOptions.maxZoom = MAX_MAP_ZOOM
        mapOptions.zoom = 2
        mapOptions.zoomSnap = 1
        mapOptions.zoomDelta = 1
        mapOptions.scrollWheelZoom = "center"
        mapOptions.doubleClickZoom = false
        mapOptions.inertia = false
        mapOptions.zoomControl = true
        mapOptions.attributionControl = false
        val newMap = Leaflet.map("map", mapOptions).setView(Leaflet.latLng(0.0, 0.0), 2)
        map = newMap
        val newRenderer = ChunkBatchRenderer(newMap)
        renderer = newRenderer
        val newViewportController = ViewportController(
            webMapService = BrowserLoggingWebMapService(webMapService),
            coroutineScope = coroutineScope,
            requestCancelled = { viewportSelection ->
                BrowserConsole.info("Cancelled the previous viewport generation for ${viewportSelection.consoleDescription()}.")
            },
            stateChanged = { viewportRenderState ->
                viewportConsoleReporter.report(viewportRenderState)
                newRenderer.render(viewportRenderState, assetSessionManager?.session)
            },
        )
        viewportController = newViewportController
        installDimensionControl(
            map = newMap,
            dimensionIds = worldMetadata.dimensionIds,
            initialDimensionId = initialDimensionId,
            dimensionSelected = { dimensionId ->
                newRenderer.clearForDimensionChange()
                selectedDimensionId = dimensionId
                BrowserConsole.info("Selected dimension $dimensionId and cleared the rendered Canvas cache.")
                selectCurrentViewport()
            },
        )
        var resizeSettleTimer: Int? = null
        newMap.on("dragstart zoomstart") { _: dynamic ->
            newRenderer.interactionStarted()
            newViewportController.interactionStarted()
        }
        newMap.on("move zoom") { _: dynamic ->
            newRenderer.mapTransformed()
            updateCoordinate()
        }
        newMap.on("dragend zoomend") { _: dynamic ->
            newRenderer.viewportSettled()
            selectCurrentViewport()
        }
        newMap.on("resize") { _: dynamic ->
            newRenderer.mapTransformed()
            updateCoordinate()
            selectCurrentViewport(restartDebounce = true)
            resizeSettleTimer?.let { timer -> browserWindow.clearTimeout(timer) }
            resizeSettleTimer = browserWindow.setTimeout({
                resizeSettleTimer = null
                newRenderer.viewportSettled()
            }, VIEWPORT_DEBOUNCE_MILLISECONDS).unsafeCast<Int>()
        }
        newMap.invalidateSize()
        newRenderer.assetsChanged()
        selectCurrentViewport()
        BrowserConsole.info("Interactive map initialized.")
    }

    fun connectMapPhase() {
        showStartupMessage(
            phaseLabel = "Phase 2 of 2 · World map",
            title = "Opening the Minecraft world map",
            step = "Connecting to the map service",
            action = "Opening the world-data WebSocket...",
            detail = "Official assets are ready",
            indeterminate = true,
        )
        coroutineScope.launch {
            var newRpcClient: KtorRpcClient? = null
            try {
                val rpcScheme = if (browserWindow.location.protocol.unsafeCast<String>() == "https:") "wss" else "ws"
                val rpcUrl = "$rpcScheme://${browserWindow.location.host}/rpc"
                BrowserConsole.info("Connecting to the map service at $rpcUrl.")
                val connectedRpcClient = httpClient.rpc(rpcUrl)
                newRpcClient = connectedRpcClient
                BrowserConsole.debug("The cold map RPC client was created.")
                val webMapService: WebMapService = connectedRpcClient.withService()
                BrowserConsole.info("Requesting world metadata from the map service.")
                val worldMetadata = webMapService.worldMetadata()
                check(worldMetadata.minecraftVersion == MinecraftProtocol.MINECRAFT_VERSION) {
                    "The map service targets ${worldMetadata.minecraftVersion}, but the browser assets target ${MinecraftProtocol.MINECRAFT_VERSION}"
                }
                BrowserConsole.info(
                    "World metadata loaded for Minecraft ${worldMetadata.minecraftVersion}; dimensions: ${worldMetadata.dimensionIds.joinToString()}.",
                )
                rpcClient = connectedRpcClient
                enterMapPhase(worldMetadata, webMapService)
            } catch (cancellationException: CancellationException) {
                newRpcClient?.close()
                throw cancellationException
            } catch (failure: Throwable) {
                newRpcClient?.close()
                rpcClient = null
                BrowserConsole.error("Map startup failed.", failure)
                showStartupMessage(
                    phaseLabel = "Phase 2 of 2 · World map",
                    title = "Map startup failed",
                    step = "Connection failed",
                    action = failure.message ?: failure::class.simpleName ?: "Unknown startup failure",
                    detail = "See the browser console for details",
                    indeterminate = false,
                )
            }
        }
    }

    val newAssetSessionManager = OfficialAssetSessionManager(
        minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
        coroutineScope = coroutineScope,
        progressChanged = { progress ->
            assetProgressConsoleReporter.report(progress)
            startupPhaseElement.hidden = false
            mapElement.hidden = true
            mapStatusElement.hidden = true
            retryAssetsButton.hidden = true
            startupPhaseLabelElement.textContent = "Phase 1 of 2 · Official assets"
            startupTitleElement.textContent = "Preparing official Minecraft assets"
            startupStepElement.textContent = progress.stepLabel
            startupActionElement.textContent = progress.action
            startupProgressElement.max = progress.totalSteps
            startupProgressElement.value = progress.completedSteps.toDouble() + progress.fraction
            startupCountElement.textContent = progress.detail
        },
        loadFailed = { message ->
            BrowserConsole.error("Official asset loading failed: $message")
            showStartupMessage(
                phaseLabel = "Phase 1 of 2 · Official assets",
                title = "Official asset loading failed",
                step = "Asset loading paused",
                action = message,
                detail = "Retry uses the same official Mojang source",
                indeterminate = false,
            )
            retryAssetsButton.hidden = false
        },
        sessionReady = { officialAssetSession ->
            BrowserConsole.info("Official assets are ready at revision ${officialAssetSession.assetRevision}.")
            connectMapPhase()
        },
        resourceFailure = { message ->
            BrowserConsole.warn("An official asset could not be used: $message")
        },
        assetsChanged = {
            val currentRenderer = renderer
            if (currentRenderer != null) currentRenderer.assetsChanged()
        },
    )
    assetSessionManager = newAssetSessionManager
    retryAssetsButton.addEventListener("click", { _: dynamic ->
        BrowserConsole.info("Retrying the official asset load.")
        assetProgressConsoleReporter.reset()
        newAssetSessionManager.loadOrRetry()
    })
    showStartupMessage(
        phaseLabel = "Phase 1 of 2 · Official assets",
        title = "Preparing official Minecraft assets",
        step = "Preparing asset loader",
        action = "Preparing the official Mojang download endpoint...",
        detail = "No asset pages requested yet",
        indeterminate = true,
    )
    newAssetSessionManager.loadOrRetry()

    browserWindow.addEventListener("beforeunload", { _: dynamic ->
        BrowserConsole.info("Closing the browser client.")
        viewportController?.close()
        newAssetSessionManager.close()
        renderer?.close()
        rpcClient?.close()
        httpClient.close()
    })
}

private fun installDimensionControl(
    map: dynamic,
    dimensionIds: List<DimensionId>,
    initialDimensionId: DimensionId,
    dimensionSelected: (DimensionId) -> Unit,
) {
    val control: dynamic = browserDocument.createElement("div")
    control.className = "leaflet-control dimension-control"
    val toggleButton: dynamic = browserDocument.createElement("button")
    toggleButton.type = "button"
    toggleButton.className = "dimension-control-toggle"
    toggleButton.setAttribute("aria-haspopup", "menu")
    toggleButton.setAttribute("aria-expanded", "false")
    val menu: dynamic = browserDocument.createElement("div")
    menu.className = "dimension-control-menu"
    menu.setAttribute("role", "menu")
    menu.hidden = true
    val optionButtons = linkedMapOf<DimensionId, dynamic>()
    var selectedDimensionId = initialDimensionId

    fun setExpanded(expanded: Boolean) {
        menu.hidden = !expanded
        toggleButton.setAttribute("aria-expanded", expanded.toString())
    }

    fun updateSelection() {
        toggleButton.textContent = dimensionShortLabel(selectedDimensionId)
        optionButtons.forEach { (dimensionId, optionButton) ->
            optionButton.setAttribute("aria-checked", (dimensionId == selectedDimensionId).toString())
        }
    }

    dimensionIds.forEach { dimensionId ->
        val optionButton: dynamic = browserDocument.createElement("button")
        optionButton.type = "button"
        optionButton.className = "dimension-control-option"
        optionButton.setAttribute("role", "menuitemradio")
        optionButton.textContent = dimensionLabel(dimensionId)
        optionButton.addEventListener("click", { event: dynamic ->
            event.preventDefault()
            val changed = selectedDimensionId != dimensionId
            selectedDimensionId = dimensionId
            updateSelection()
            setExpanded(false)
            if (changed) dimensionSelected(dimensionId)
        })
        optionButtons[dimensionId] = optionButton
        menu.appendChild(optionButton)
    }
    toggleButton.addEventListener("click", { event: dynamic ->
        event.preventDefault()
        setExpanded(menu.hidden.unsafeCast<Boolean>())
    })
    listOf("click", "dblclick", "mousedown", "pointerdown", "wheel").forEach { eventName ->
        control.addEventListener(eventName, { event: dynamic -> event.stopPropagation() })
    }
    control.appendChild(toggleButton)
    control.appendChild(menu)
    updateSelection()
    val topLeftCorner: dynamic = map.getContainer().querySelector(".leaflet-top.leaflet-left")
    check(topLeftCorner != null) { "Leaflet did not create its top-left control corner" }
    topLeftCorner.appendChild(control)
    map.on("click") { _: dynamic -> setExpanded(false) }
}

private class BrowserLoggingWebMapService(
    private val delegate: WebMapService,
) : WebMapService by delegate {
    override suspend fun querySurface(surfaceRequest: SurfaceRequest): SurfaceQueryResult {
        val description = surfaceRequest.consoleDescription()
        val startedAt = browserWindow.performance.now().unsafeCast<Double>()
        BrowserConsole.info("Sending surface RPC for $description.")
        return try {
            delegate.querySurface(surfaceRequest).also { surfaceQueryResult ->
                val elapsedMilliseconds = browserWindow.performance.now().unsafeCast<Double>() - startedAt
                when (surfaceQueryResult) {
                    is SurfaceQueryResult.Rejected -> BrowserConsole.warn(
                        "Surface RPC for $description was rejected as ${surfaceQueryResult.rejection} after ${elapsedMilliseconds.toInt()} ms.",
                    )

                    is SurfaceQueryResult.Success -> {
                        val successfulChunks = surfaceQueryResult.response.chunks.count { result ->
                            result is SurfaceChunkResult.Success
                        }
                        val failedChunks = surfaceQueryResult.response.chunks.size - successfulChunks
                        BrowserConsole.info(
                            "Surface RPC for $description completed after ${elapsedMilliseconds.toInt()} ms with $successfulChunks readable Chunks and $failedChunks failed Chunks; leading surface blocks: ${surfaceQueryResult.response.surfaceBlockSummary()}.",
                        )
                    }
                }
            }
        } catch (cancellationException: CancellationException) {
            BrowserConsole.info("Surface RPC for $description was cancelled.")
            throw cancellationException
        } catch (failure: Throwable) {
            BrowserConsole.error("Surface RPC for $description failed.", failure)
            throw failure
        }
    }
}

private fun SurfaceRequest.consoleDescription(): String =
    ViewportSelection(dimensionId, chunkViewport).consoleDescription()

private fun SurfaceResponse.surfaceBlockSummary(): String {
    val counts = mutableMapOf<String, Int>()
    chunks.forEach { surfaceChunkResult ->
        val chunkSurface = (surfaceChunkResult as? SurfaceChunkResult.Success)?.surface ?: return@forEach
        chunkSurface.cells.forEach { paletteIndex ->
            val name = paletteIndex?.let { index -> chunkSurface.palette[index].name.toString() } ?: "empty"
            counts[name] = counts.getOrElse(name) { 0 } + 1
        }
    }
    return counts.entries.sortedByDescending { entry -> entry.value }.take(SURFACE_SUMMARY_LIMIT)
        .joinToString { entry ->
            "${entry.key}=${entry.value}"
        }.ifEmpty { "none" }
}

private class AssetProgressConsoleReporter {
    private var previousAction: String? = null
    private var previousProgressBucket: Int? = null

    fun reset() {
        previousAction = null
        previousProgressBucket = null
    }

    fun report(officialAssetLoadProgress: OfficialAssetLoadProgress) {
        if ("failed on attempt" in officialAssetLoadProgress.action) {
            BrowserConsole.warn("Official assets: ${officialAssetLoadProgress.action}")
            return
        }
        val progressBucket = (officialAssetLoadProgress.fraction * PROGRESS_BUCKET_COUNT).toInt()
        if (
            officialAssetLoadProgress.action == previousAction &&
            progressBucket == previousProgressBucket
        ) {
            return
        }
        previousAction = officialAssetLoadProgress.action
        previousProgressBucket = progressBucket
        BrowserConsole.info("Official assets: ${officialAssetLoadProgress.action} ${officialAssetLoadProgress.detail}")
    }
}

private class ViewportConsoleReporter {
    private var previousSummary: String? = null

    fun report(viewportRenderState: ViewportRenderState) {
        val viewportSelection =
            viewportRenderState.requestedSelection ?: viewportRenderState.displayedSelection ?: return
        val phase = when {
            viewportRenderState.loading -> "request pending"
            viewportRenderState.rejection != null -> "rejected: ${viewportRenderState.rejection}"
            viewportRenderState.callFailure != null -> "request failed: ${viewportRenderState.callFailure}"
            viewportRenderState.readFailedCoordinates.isNotEmpty() ->
                "repairing ${viewportRenderState.readFailedCoordinates.size} Chunks"

            viewportRenderState.exhaustedCoordinates.isNotEmpty() ->
                "${viewportRenderState.exhaustedCoordinates.size} Chunks exhausted retries"

            else -> "ready with ${viewportRenderState.surfaces.size} Chunks"
        }
        val summary = "${viewportSelection.consoleDescription()}; $phase"
        if (summary == previousSummary) return
        previousSummary = summary
        when {
            viewportRenderState.rejection != null || viewportRenderState.callFailure != null ||
                    viewportRenderState.exhaustedCoordinates.isNotEmpty() -> BrowserConsole.warn("Viewport $summary.")

            else -> BrowserConsole.info("Viewport $summary.")
        }
    }
}

private fun ViewportSelection.consoleDescription(): String {
    val chunkViewport = chunkViewport
    return "dimension=$dimensionId, Chunks=(${chunkViewport.minChunkX}, ${chunkViewport.minChunkZ})..(${chunkViewport.maxChunkX}, ${chunkViewport.maxChunkZ})"
}

private val OfficialAssetLoadProgress.fraction: Double
    get() = when {
        totalBytes != null && totalBytes > 0 && loadedBytes != null -> loadedBytes.toDouble() / totalBytes
        totalFiles != null && totalFiles > 0 && indexedFiles != null -> indexedFiles.toDouble() / totalFiles
        else -> 0.0
    }.coerceIn(0.0, 1.0)

private val OfficialAssetLoadProgress.stepLabel: String
    get() = if (completedSteps >= totalSteps) {
        "Step $totalSteps of $totalSteps · Complete"
    } else {
        "Step ${completedSteps + 1} of $totalSteps"
    }

private val OfficialAssetLoadProgress.detail: String
    get() {
        val details = buildList {
            if (loadedPages != null && totalPages != null) add("$loadedPages of $totalPages pages")
            if (loadedBytes != null && totalBytes != null) add("${formatBytes(loadedBytes)} of ${formatBytes(totalBytes)} cached")
            if (indexedFiles != null && totalFiles != null) add("$indexedFiles of $totalFiles asset files")
        }
        return details.joinToString(" · ").ifEmpty { "Waiting for progress details" }
    }

private fun formatBytes(bytes: Int): String = when {
    bytes >= MEBIBYTE -> "${bytes / MEBIBYTE}.${bytes % MEBIBYTE * 10 / MEBIBYTE} MiB"
    bytes >= KIBIBYTE -> "${bytes / KIBIBYTE}.${bytes % KIBIBYTE * 10 / KIBIBYTE} KiB"
    else -> "$bytes bytes"
}

private fun dimensionLabel(dimensionId: DimensionId): String = when (dimensionId) {
    DimensionId.Overworld -> "Overworld · $dimensionId"
    DimensionId.Nether -> "Nether · $dimensionId"
    DimensionId.End -> "The End · $dimensionId"
    else -> dimensionId.toString()
}

private fun dimensionShortLabel(dimensionId: DimensionId): String = when (dimensionId) {
    DimensionId.Overworld -> "Overworld"
    DimensionId.Nether -> "Nether"
    DimensionId.End -> "The End"
    else -> dimensionId.path
}

private val browserWindow: dynamic = js("window")
private val browserDocument: dynamic = js("document")
private const val KIBIBYTE: Int = 1024
private const val MEBIBYTE: Int = 1024 * KIBIBYTE
private const val OFFICIAL_ASSET_LOAD_STEPS: Int = 5
private const val PROGRESS_BUCKET_COUNT: Int = 10
private const val VIEWPORT_DEBOUNCE_MILLISECONDS: Int = 200
private const val SURFACE_SUMMARY_LIMIT: Int = 8
