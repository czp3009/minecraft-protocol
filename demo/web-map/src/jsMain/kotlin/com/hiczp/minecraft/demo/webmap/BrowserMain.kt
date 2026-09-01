@file:Suppress("UnsafeCastFromDynamic")

package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.world.format.DimensionId
import io.ktor.client.*
import io.ktor.client.engine.js.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    val coroutineScope = MainScope()
    val startupPhaseElement: dynamic = browserDocument.getElementById("startup-phase")
    val startupPhaseLabelElement: dynamic = browserDocument.getElementById("startup-phase-label")
    val startupTitleElement: dynamic = browserDocument.getElementById("startup-title")
    val startupStepElement: dynamic = browserDocument.getElementById("asset-load-step")
    val startupActionElement: dynamic = browserDocument.getElementById("asset-load-action")
    val startupCountElement: dynamic = browserDocument.getElementById("asset-load-count")
    val startupProgressElement: dynamic = browserDocument.getElementById("asset-load-progress")
    val mapElement: dynamic = browserDocument.getElementById("map")
    val mapStatusElement: dynamic = browserDocument.getElementById("map-status")
    val connectionElement: dynamic = browserDocument.getElementById("connection-text")
    val coordinateElement: dynamic = browserDocument.getElementById("coordinate-text")
    val viewportElement: dynamic = browserDocument.getElementById("viewport-text")
    val httpClient = HttpClient(Js) {
        installKrpc {
            serialization {
                json(WebMapJson)
            }
        }
    }
    var activeRpcClient: KtorRpcClient? = null
    var viewportController: ViewportController? = null
    var renderer: ChunkBatchRenderer? = null
    var officialAssetSession: OfficialAssetSession? = null
    var map: dynamic = null
    var selectedDimensionId: DimensionId? = null
    var chunkRenderProgress = ChunkRenderProgress()

    fun setConnectionMessage(message: String, warning: Boolean = false) {
        connectionElement.textContent = message
        connectionElement.classList.toggle("warning", warning)
    }

    fun showStartup(
        phaseLabel: String,
        title: String,
        step: String,
        action: String,
        detail: String,
        completed: Double? = null,
        total: Int = 1,
    ) {
        if (map != null) {
            startupPhaseElement.hidden = true
            mapElement.hidden = false
            mapStatusElement.hidden = false
            return
        }
        startupPhaseElement.hidden = false
        mapElement.hidden = true
        mapStatusElement.hidden = true
        startupPhaseLabelElement.textContent = phaseLabel
        startupTitleElement.textContent = title
        startupStepElement.textContent = step
        startupActionElement.textContent = action
        startupCountElement.textContent = detail
        startupProgressElement.max = total
        if (completed == null) {
            startupProgressElement.removeAttribute("value")
        } else {
            startupProgressElement.value = completed
        }
    }

    fun updateCoordinate() {
        val currentMap = map ?: return
        val center = currentMap.getCenter()
        val zoom = currentMap.getZoom().unsafeCast<Int>()
        coordinateElement.textContent =
            "Center [X: ${
                center.lng.unsafeCast<Double>().toInt()
            }, Z: ${(-center.lat.unsafeCast<Double>()).toInt()}] · Scale ${pixelsPerBlock(zoom)}×"
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
        renderer?.viewportSettled()
        currentViewportController.select(
            ViewportSelection(
                dimensionId = currentDimensionId,
                chunkViewport = VisibleBlockBounds(
                    minBlockX = min(firstBlockX, secondBlockX),
                    minBlockZ = min(firstBlockZ, secondBlockZ),
                    maxBlockX = max(firstBlockX, secondBlockX),
                    maxBlockZ = max(firstBlockZ, secondBlockZ),
                ).toChunkViewport(),
            ),
            restartDebounce = restartDebounce,
        )
        renderer?.render(currentViewportController.state, officialAssetSession)
        updateCoordinate()
    }

    fun updateViewportStatus(viewportRenderState: ViewportRenderState) {
        val viewportSelection = viewportRenderState.displayedSelection
        val renderedChunkCount = chunkRenderProgress.takeIf { progress ->
            progress.selection == viewportSelection
        }?.renderedChunkCount ?: 0
        viewportElement.textContent = buildList {
            viewportSelection?.let { selection ->
                add("Viewport ${selection.chunkViewport.chunkCount} chunks")
            }
            add("${viewportRenderState.surfaces.size} renderable")
            add("$renderedChunkCount rendered")
            if (viewportRenderState.loading) add("updating")
            if (viewportRenderState.readFailedCoordinates.isNotEmpty()) {
                add("${viewportRenderState.readFailedCoordinates.size} failed, retrying")
            }
            viewportRenderState.rejection?.let { rejection -> add("rejected: $rejection") }
            viewportRenderState.callFailure?.let { callFailure -> add("stream paused: $callFailure") }
        }.joinToString(" · ")
    }

    fun initializeMap(worldMetadata: WorldMetadata) {
        if (map != null) return
        val initialDimensionId =
            worldMetadata.dimensionIds.firstOrNull { dimensionId -> dimensionId == DimensionId.Overworld }
                ?: worldMetadata.dimensionIds.first()
        selectedDimensionId = initialDimensionId
        startupPhaseElement.hidden = true
        mapElement.hidden = false
        mapStatusElement.hidden = false
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
        val newRenderer = ChunkBatchRenderer(newMap) { newChunkRenderProgress ->
            chunkRenderProgress = newChunkRenderProgress
            updateViewportStatus(viewportController?.state ?: ViewportRenderState())
        }
        renderer = newRenderer
        val newViewportController = ViewportController(
            coroutineScope = coroutineScope,
            requestCancelled = { viewportSelection ->
                BrowserConsole.debug("Cancelled surface stream for ${viewportSelection.consoleDescription()}.")
            },
            stateChanged = { viewportRenderState ->
                updateViewportStatus(viewportRenderState)
                newRenderer.render(viewportRenderState, officialAssetSession)
            },
        )
        viewportController = newViewportController
        fun cancelViewportWork() {
            newRenderer.interactionStarted()
            newViewportController.interactionStarted()
        }
        installDimensionControl(
            map = newMap,
            dimensionIds = worldMetadata.dimensionIds,
            initialDimensionId = initialDimensionId,
            dimensionSelected = { dimensionId ->
                cancelViewportWork()
                selectedDimensionId = dimensionId
                selectCurrentViewport()
            },
        )
        newMap.on("dragstart zoomstart") { _: dynamic ->
            cancelViewportWork()
        }
        newMap.on("move zoom") { _: dynamic ->
            newRenderer.mapTransformed()
            updateCoordinate()
        }
        newMap.on("dragend zoomend") { _: dynamic ->
            selectCurrentViewport()
        }
        newMap.on("resize") { _: dynamic ->
            cancelViewportWork()
            selectCurrentViewport(restartDebounce = true)
        }
        newMap.invalidateSize()
        selectCurrentViewport()
    }

    suspend fun acceptReadyService(
        webMapService: WebMapService,
        assetLoadStatus: AssetLoadStatus.Ready,
    ) {
        val worldMetadata = webMapService.worldMetadata()
        check(worldMetadata.minecraftVersion == MinecraftProtocol.MINECRAFT_VERSION) {
            "The map service targets ${worldMetadata.minecraftVersion}, but the browser targets ${MinecraftProtocol.MINECRAFT_VERSION}"
        }
        val currentOfficialAssetSession = officialAssetSession
        if (currentOfficialAssetSession?.assetRevision != assetLoadStatus.assetRevision) {
            currentOfficialAssetSession?.close()
            officialAssetSession = OfficialAssetSession(
                assetRevision = assetLoadStatus.assetRevision,
                resourceFailure = { message -> BrowserConsole.warn("Backend asset unavailable: $message") },
            )
            renderer?.render(viewportController?.state ?: ViewportRenderState(), officialAssetSession)
        }
        officialAssetSession?.connected(webMapService)
        initializeMap(worldMetadata)
        setConnectionMessage("Connected · assets ${assetLoadStatus.fileCount} files · ${formatBytes(assetLoadStatus.byteCount)}")
        viewportController?.connected(BrowserLoggingWebMapService(webMapService))
    }

    fun handleAssetStatus(assetLoadStatus: AssetLoadStatus) {
        when (assetLoadStatus) {
            is AssetLoadStatus.Loading -> {
                val fraction = assetLoadStatus.progressFraction
                showStartup(
                    phaseLabel = "Backend preparation",
                    title = "Preparing Minecraft map assets",
                    step = "Step ${assetLoadStatus.completedSteps + 1} of ${assetLoadStatus.totalSteps}",
                    action = assetLoadStatus.action,
                    detail = assetLoadStatus.progressDetail,
                    completed = assetLoadStatus.completedSteps + fraction,
                    total = assetLoadStatus.totalSteps,
                )
                if (map != null) {
                    setConnectionMessage("Backend preparing assets · ${assetLoadStatus.action}", warning = true)
                    viewportController?.disconnected(assetLoadStatus.detail)
                }
            }

            is AssetLoadStatus.Failed -> {
                showStartup(
                    phaseLabel = "Backend preparation",
                    title = "Asset loading will retry automatically",
                    step = "Temporary backend failure",
                    action = assetLoadStatus.message,
                    detail = "Retrying in ${assetLoadStatus.retryDelayMillis} ms",
                    completed = 0.0,
                )
                setConnectionMessage("Asset loading failed; backend will retry", warning = true)
                viewportController?.disconnected(assetLoadStatus.message)
            }

            is AssetLoadStatus.Ready -> Unit
        }
    }

    suspend fun awaitRpcConnectionFailure(): RpcConnectionFailure {
        var rpcClient: KtorRpcClient? = null
        val readySignal = CompletableDeferred<Unit>()
        return try {
            val rpcScheme = if (browserWindow.location.protocol.unsafeCast<String>() == "https:") "wss" else "ws"
            val rpcUrl = "$rpcScheme://${browserWindow.location.host}/rpc"
            val connectedRpcClient = httpClient.rpc(rpcUrl)
            rpcClient = connectedRpcClient
            activeRpcClient = connectedRpcClient
            val webMapService: WebMapService = connectedRpcClient.withService()
            coroutineScope {
                launch {
                    webMapService.assetLoading().collect { assetLoadStatus ->
                        handleAssetStatus(assetLoadStatus)
                        if (assetLoadStatus is AssetLoadStatus.Ready) {
                            acceptReadyService(webMapService, assetLoadStatus)
                            readySignal.complete(Unit)
                        }
                    }
                    error("The backend asset progress stream ended")
                }
                connectedRpcClient.webSocketSession.await()
                connectedRpcClient.awaitCompletion()
                error("The RPC connection closed")
            }
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (failure: Throwable) {
            RpcConnectionFailure(failure, readySignal.isCompleted)
        } finally {
            if (activeRpcClient === rpcClient) activeRpcClient = null
            rpcClient?.close()
        }
    }

    showStartup(
        phaseLabel = "Map service",
        title = "Connecting to the Minecraft web map",
        step = "Opening RPC connection",
        action = "Waiting for the backend asset progress stream…",
        detail = "The map will remain available during later reconnects",
    )

    coroutineScope.launch {
        var reconnectDelay = INITIAL_RECONNECT_DELAY
        while (true) {
            if (map != null) setConnectionMessage("Connecting…", warning = true)
            val rpcConnectionFailure = awaitRpcConnectionFailure()
            val retryDelay = if (rpcConnectionFailure.reachedReady) INITIAL_RECONNECT_DELAY else reconnectDelay
            val failure = rpcConnectionFailure.failure
            val message = failure.message ?: failure::class.simpleName ?: "RPC connection failed"
            BrowserConsole.error("Map RPC connection lost.", failure)
            officialAssetSession?.disconnected()
            viewportController?.disconnected(message)
            if (map == null) {
                showStartup(
                    phaseLabel = "Map service",
                    title = "Reconnecting to the Minecraft web map",
                    step = "Connection interrupted",
                    action = message,
                    detail = "Retrying in ${retryDelay.inWholeMilliseconds} ms",
                )
            } else {
                setConnectionMessage(
                    "Disconnected · retrying in ${retryDelay.inWholeMilliseconds} ms",
                    warning = true,
                )
            }
            delay(retryDelay)
            reconnectDelay = if (rpcConnectionFailure.reachedReady) {
                INITIAL_RECONNECT_DELAY
            } else {
                minOf(retryDelay * 2, MAXIMUM_RECONNECT_DELAY)
            }
        }
    }

    browserWindow.addEventListener("beforeunload", { _: dynamic ->
        viewportController?.close()
        renderer?.close()
        officialAssetSession?.close()
        activeRpcClient?.close()
        coroutineScope.cancel()
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
    override fun querySurface(surfaceRequest: SurfaceRequest): Flow<SurfaceQueryUpdate> {
        val description = surfaceRequest.consoleDescription()
        val successfulChunkCoordinates = mutableSetOf<ChunkCoordinate>()
        val startedAt = browserWindow.performance.now().unsafeCast<Double>()
        return delegate.querySurface(surfaceRequest)
            .onStart { BrowserConsole.info("Opening surface stream for $description.") }
            .onEach { surfaceQueryUpdate ->
                val surfaceChunkResult = (surfaceQueryUpdate as? SurfaceQueryUpdate.Chunk)?.result
                if (surfaceChunkResult is SurfaceChunkResult.Success) {
                    successfulChunkCoordinates += surfaceChunkResult.coordinate
                }
            }
            .onCompletion { failure ->
                val elapsedMilliseconds = browserWindow.performance.now().unsafeCast<Double>() - startedAt
                if (failure == null) {
                    BrowserConsole.info(
                        "Surface stream for $description completed in ${elapsedMilliseconds.toInt()} ms with ${successfulChunkCoordinates.size} renderable chunks.",
                    )
                } else if (failure !is CancellationException) {
                    BrowserConsole.error("Surface stream for $description failed.", failure)
                }
            }
    }
}

private fun SurfaceRequest.consoleDescription(): String =
    ViewportSelection(dimensionId, chunkViewport).consoleDescription()

private fun ViewportSelection.consoleDescription(): String =
    "dimension=$dimensionId, chunks=(${chunkViewport.minChunkX}, ${chunkViewport.minChunkZ})..(${chunkViewport.maxChunkX}, ${chunkViewport.maxChunkZ})"

private data class RpcConnectionFailure(
    val failure: Throwable,
    val reachedReady: Boolean,
)

private val AssetLoadStatus.Loading.progressFraction: Double
    get() = when {
        totalBytes != null && totalBytes > 0L && loadedBytes != null -> loadedBytes.toDouble() / totalBytes
        totalFiles != null && totalFiles > 0 && loadedFiles != null -> loadedFiles.toDouble() / totalFiles
        else -> 0.0
    }.coerceIn(0.0, 1.0)

private val AssetLoadStatus.Loading.progressDetail: String
    get() = buildList {
        add(detail)
        if (loadedFiles != null && totalFiles != null) add("$loadedFiles/$totalFiles files")
        if (loadedBytes != null && totalBytes != null) add("${formatBytes(loadedBytes)}/${formatBytes(totalBytes)}")
    }.joinToString(" · ")

private fun formatBytes(bytes: Long): String = when {
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
private val INITIAL_RECONNECT_DELAY = 500.milliseconds
private val MAXIMUM_RECONNECT_DELAY = 8_000.milliseconds
private const val KIBIBYTE: Long = 1024L
private const val MEBIBYTE: Long = 1024L * KIBIBYTE
