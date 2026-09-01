@file:Suppress("UnsafeCastFromDynamic")

package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.math.roundToInt

@JsModule("minecraft-web-map-zip-http-range-reader")
@JsNonModule
private external class DelegatingRangeReader(
    size: Int,
    readUint8Array: (Int, Int) -> Promise<Uint8Array>,
    createReadable: (dynamic) -> dynamic,
)

@JsModule("minecraft-web-map-zip-reader")
@JsNonModule
private external class ZipReader(reader: dynamic, options: dynamic = definedExternally) {
    fun getEntries(options: dynamic = definedExternally): Promise<Array<ZipEntry>>

    fun close(): Promise<Unit>
}

@JsModule("minecraft-web-map-zip-blob-writer")
@JsNonModule
private external class BlobWriter(contentType: String = definedExternally)

private external interface ZipEntry {
    val filename: String
    val directory: Boolean
    val offset: Int
    val compressedSize: Int
    val uncompressedSize: Double

    fun getData(writer: BlobWriter): Promise<dynamic>
}

internal data class OfficialAssetLoadProgress(
    val action: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val indexedFiles: Int? = null,
    val totalFiles: Int? = null,
    val loadedPages: Int? = null,
    val totalPages: Int? = null,
    val loadedBytes: Int? = null,
    val totalBytes: Int? = null,
) {
    init {
        require(action.isNotBlank()) { "Official asset progress action must not be blank" }
        require(totalSteps > 0 && completedSteps in 0..totalSteps) { "Official asset progress steps are invalid" }
        require(indexedFiles == null || indexedFiles >= 0) { "Indexed file count must not be negative" }
        require(totalFiles == null || totalFiles >= 0) { "Total file count must not be negative" }
        require(loadedPages == null || loadedPages >= 0) { "Loaded page count must not be negative" }
        require(totalPages == null || totalPages >= 0) { "Total page count must not be negative" }
        require(loadedPages == null || totalPages == null || loadedPages <= totalPages) {
            "Loaded page count must not exceed the total page count"
        }
        require(loadedBytes == null || loadedBytes >= 0) { "Loaded byte count must not be negative" }
        require(totalBytes == null || totalBytes >= 0) { "Total byte count must not be negative" }
    }
}

internal class OfficialAssetSession private constructor(
    val assetRevision: String,
    private val abortController: dynamic,
    private val boundedHttpRangeReader: BoundedHttpRangeReader,
    private val zipReader: ZipReader,
    entries: Map<String, ZipEntry>,
    private val resourceFailure: (String) -> Unit,
    private val assetsChanged: () -> Unit,
) {
    private val sessionJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + sessionJob)
    private val entries = entries.toMap()
    private val decodedTextureValues = mutableListOf<DecodedAssetTexture>()
    private val jsonResources = AsyncResourceCache<String, JsonElement>(coroutineScope, ::loadJsonResource)
    private val blockStateDefinitions = AsyncResourceCache<Identifier, AssetBlockStateDefinition>(
        coroutineScope,
        ::loadBlockStateDefinition,
    )
    private val models = AsyncResourceCache<Identifier, AssetModel>(coroutineScope, ::loadModel)
    private val resolvedModels = AsyncResourceCache<Identifier, ResolvedAssetModel>(coroutineScope, ::loadResolvedModel)
    private val decodedTextures = AsyncResourceCache<Identifier, DecodedAssetTexture>(coroutineScope, ::loadTexture)
    private val sprites = AsyncResourceCache<BakedSpriteKey, Any>(coroutineScope, ::loadSprite)

    fun requestSprite(surfaceBlockState: SurfaceBlockState, blockX: Int, blockZ: Int): Any? {
        val assetBlockStateDefinition =
            when (val cachedResource = blockStateDefinitions.completed(surfaceBlockState.name)) {
                is CachedResource.Available -> cachedResource.value
                is CachedResource.Unavailable -> return null
                null -> {
                    blockStateDefinitions.start(surfaceBlockState.name)
                    return null
                }
            }
        val modelReferences = assetBlockStateDefinition.select(surfaceBlockState, blockX, blockZ)
        if (modelReferences.isEmpty()) return null
        val bakedSpriteKey = BakedSpriteKey(surfaceBlockState.name, modelReferences)
        return when (val cachedResource = sprites.completed(bakedSpriteKey)) {
            is CachedResource.Available -> cachedResource.value
            is CachedResource.Unavailable -> null
            null -> {
                sprites.start(bakedSpriteKey)
                null
            }
        }
    }

    suspend fun close() {
        coroutineScope.cancel()
        abortController.abort()
        decodedTextureValues.forEach { decodedAssetTexture -> decodedAssetTexture.close() }
        decodedTextureValues.clear()
        boundedHttpRangeReader.cancel()
        zipReader.close().await()
    }

    private suspend fun loadSprite(bakedSpriteKey: BakedSpriteKey): CachedResource<Any> {
        val cachedResource = cacheFailures {
            val blockName = bakedSpriteKey.blockName
            val canvas: dynamic = browserDocument.createElement("canvas")
            canvas.width = SPRITE_SIDE
            canvas.height = SPRITE_SIDE
            val context: dynamic = canvas.getContext("2d")
            context.imageSmoothingEnabled = false
            val resolvedSelections = coroutineScope {
                bakedSpriteKey.modelReferences.map { modelReference ->
                    async {
                        resolvedModels.get(modelReference.model).availableOrNull()?.let { resolvedAssetModel ->
                            modelReference to resolvedAssetModel
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            preloadSelectedTextures(resolvedSelections)
            var drewFallback = false
            for ((modelReference, resolvedModel) in resolvedSelections) {
                if (drawResolvedModelFallback(context, blockName, modelReference, resolvedModel)) {
                    drewFallback = true
                    break
                }
            }
            var drewFace = false
            for ((modelReference, resolvedModel) in resolvedSelections) {
                drewFace = drawResolvedModel(context, blockName, modelReference, resolvedModel) || drewFace
            }
            if (!drewFallback && !drewFace) {
                return@cacheFailures CachedResource.Unavailable("Selected models have neither a usable top face nor a particle texture for $blockName")
            }
            CachedResource.Available(canvas.unsafeCast<Any>())
        }
        cachedResource.reportFailure()
        assetsChanged()
        return cachedResource
    }

    private suspend fun preloadSelectedTextures(
        resolvedSelections: List<Pair<AssetModelReference, ResolvedAssetModel>>,
    ) = coroutineScope {
        resolvedSelections.flatMap { (modelReference, resolvedAssetModel) ->
            val sourceDirection = sourceTopDirection(modelReference.xRotation)
            buildList {
                resolvedAssetModel.textures["particle"]?.let(::add)
                resolvedAssetModel.elements.forEach { element ->
                    element.faces[sourceDirection]?.texture?.let(::add)
                }
            }.mapNotNull { textureValue ->
                resolveTextureReference(resolvedAssetModel.textures, textureValue)?.let { resolvedTexture ->
                    parseAssetIdentifier(resolvedTexture, modelReference.model.namespace)
                }
            }
        }.distinct().map { textureLocation ->
            async { decodedTextures.get(textureLocation) }
        }.awaitAll()
        Unit
    }

    private suspend fun loadBlockStateDefinition(identifier: Identifier): CachedResource<AssetBlockStateDefinition> {
        val cachedResource = cacheFailures {
            val blockStateJson = jsonResources.get(identifier.blockStateEntryName()).availableOrNull()
                ?: return@cacheFailures CachedResource.Unavailable("No block-state definition for $identifier")
            CachedResource.Available(MinecraftAssetJsonParser.parseBlockState(blockStateJson))
        }
        cachedResource.reportFailure()
        assetsChanged()
        return cachedResource
    }

    private suspend fun drawResolvedModel(
        context: dynamic,
        blockName: Identifier,
        modelReference: AssetModelReference,
        resolvedAssetModel: ResolvedAssetModel,
    ): Boolean {
        val sourceDirection = sourceTopDirection(modelReference.xRotation)
        var drewFace = false
        resolvedAssetModel.elements.sortedBy { element -> projectedHeight(element, sourceDirection) }
            .forEach { element ->
                val face = element.faces[sourceDirection] ?: return@forEach
                val textureValue = resolveTextureReference(resolvedAssetModel.textures, face.texture) ?: return@forEach
                val textureLocation = parseAssetIdentifier(textureValue, modelReference.model.namespace)
                val decodedAssetTexture = decodedTextures.get(textureLocation).availableOrNull() ?: return@forEach
                drawTextureFace(
                    context = context,
                    blockName = blockName,
                    modelReference = modelReference,
                    element = element,
                    direction = sourceDirection,
                    face = face,
                    decodedAssetTexture = decodedAssetTexture,
                )
                drewFace = true
            }
        return drewFace
    }

    private suspend fun drawResolvedModelFallback(
        context: dynamic,
        blockName: Identifier,
        modelReference: AssetModelReference,
        resolvedAssetModel: ResolvedAssetModel,
    ): Boolean {
        val sourceDirection = sourceTopDirection(modelReference.xRotation)
        val textureValues = buildList {
            resolvedAssetModel.textures["particle"]?.let(::add)
            resolvedAssetModel.elements.sortedByDescending { element -> projectedHeight(element, sourceDirection) }
                .mapNotNullTo(this) { element -> element.faces[sourceDirection]?.texture }
        }
        var decodedAssetTexture: DecodedAssetTexture? = null
        for (textureValue in textureValues) {
            val resolvedTexture = resolveTextureReference(resolvedAssetModel.textures, textureValue) ?: continue
            val textureLocation = parseAssetIdentifier(resolvedTexture, modelReference.model.namespace)
            decodedAssetTexture = decodedTextures.get(textureLocation).availableOrNull()
            if (decodedAssetTexture != null) break
        }
        val fallbackTexture = decodedAssetTexture ?: return false
        val tinted = usesBuiltInTint(blockName) || resolvedAssetModel.elements.any { element ->
            element.faces.values.any { face -> face.tintIndex != null }
        }
        context.save()
        context.fillStyle = if (tinted) tintColor(blockName) else fallbackTexture.averageColor
        context.fillRect(0, 0, SPRITE_SIDE, SPRITE_SIDE)
        if (!tinted) {
            context.drawImage(
                fallbackTexture.bitmap,
                0,
                fallbackTexture.frameOffset,
                fallbackTexture.frameSide,
                fallbackTexture.frameSide,
                0,
                0,
                SPRITE_SIDE,
                SPRITE_SIDE,
            )
        }
        context.restore()
        return true
    }

    private fun drawTextureFace(
        context: dynamic,
        blockName: Identifier,
        modelReference: AssetModelReference,
        element: AssetModelElement,
        direction: AssetDirection,
        face: AssetModelFace,
        decodedAssetTexture: DecodedAssetTexture,
    ) {
        val destination = projectedRectangle(element, direction)
        val uv = face.uv ?: defaultFaceUv(element, direction)
        val sourceSide = decodedAssetTexture.frameSide.toDouble()
        val sourceX = uv[0] / MODEL_SIDE * sourceSide
        val sourceY = decodedAssetTexture.frameOffset.toDouble() + uv[1] / MODEL_SIDE * sourceSide
        val sourceWidth = (uv[2] - uv[0]) / MODEL_SIDE * sourceSide
        val sourceHeight = (uv[3] - uv[1]) / MODEL_SIDE * sourceSide
        val centerX = (destination.left + destination.right) / 2.0
        val centerY = (destination.top + destination.bottom) / 2.0
        context.save()
        context.translate(SPRITE_SIDE / 2.0, SPRITE_SIDE / 2.0)
        context.rotate(modelReference.yRotation * PI / 180.0)
        context.translate(-SPRITE_SIDE / 2.0, -SPRITE_SIDE / 2.0)
        context.translate(centerX, centerY)
        context.rotate(face.rotation * PI / 180.0)
        context.translate(-centerX, -centerY)
        context.drawImage(
            decodedAssetTexture.bitmap,
            sourceX,
            sourceY,
            sourceWidth,
            sourceHeight,
            destination.left,
            destination.top,
            destination.right - destination.left,
            destination.bottom - destination.top,
        )
        if (face.tintIndex != null) {
            context.globalCompositeOperation = "multiply"
            context.fillStyle = tintColor(blockName)
            context.fillRect(
                destination.left,
                destination.top,
                destination.right - destination.left,
                destination.bottom - destination.top,
            )
        }
        context.restore()
    }

    private suspend fun loadResolvedModel(identifier: Identifier): CachedResource<ResolvedAssetModel> = cacheFailures {
        val hierarchy = mutableListOf<AssetModel>()
        val visited = mutableSetOf<Identifier>()
        var current: Identifier? = identifier
        while (current != null) {
            if (!visited.add(current)) {
                return@cacheFailures CachedResource.Unavailable("Circular model parent chain at $current")
            }
            val model = models.get(current).availableOrNull()
                ?: return@cacheFailures CachedResource.Unavailable("Missing model in parent chain: $current")
            hierarchy += model
            current = model.parent
        }
        val textures = linkedMapOf<String, String>()
        hierarchy.asReversed().forEach { model -> textures.putAll(model.textures) }
        val elements = hierarchy.firstNotNullOfOrNull(AssetModel::elements).orEmpty()
        CachedResource.Available(ResolvedAssetModel(textures, elements))
    }

    private suspend fun loadModel(identifier: Identifier): CachedResource<AssetModel> = cacheFailures {
        val jsonElement = jsonResources.get(identifier.modelEntryName()).availableOrNull()
            ?: return@cacheFailures CachedResource.Unavailable("Missing model: $identifier")
        CachedResource.Available(MinecraftAssetJsonParser.parseModel(jsonElement, identifier.namespace))
    }

    private suspend fun loadTexture(identifier: Identifier): CachedResource<DecodedAssetTexture> = cacheFailures {
        val entryName = identifier.textureEntryName()
        val entry = entries[entryName]
            ?: return@cacheFailures CachedResource.Unavailable("Missing texture: $identifier")
        if (entry.uncompressedSize > MAX_TEXTURE_BYTES) {
            return@cacheFailures CachedResource.Unavailable("Texture is too large: $entryName")
        }
        val blob = entry.getData(BlobWriter("image/png")).await()
        val bitmap = browserWindow.createImageBitmap(blob).unsafeCast<Promise<dynamic>>().await()
        val width = bitmap.width.unsafeCast<Int>()
        val height = bitmap.height.unsafeCast<Int>()
        if (width <= 0 || height < width) {
            bitmap.close()
            return@cacheFailures CachedResource.Unavailable("Texture has invalid dimensions: $entryName")
        }
        val metadata = jsonResources.get("$entryName.mcmeta").availableOrNull()
        val requestedFrame = MinecraftAssetJsonParser.firstAnimationFrame(metadata)
        val frameCount = height / width
        val frameIndex = requestedFrame.coerceIn(0, maxOf(frameCount - 1, 0))
        val decodedAssetTexture = DecodedAssetTexture(
            bitmap = bitmap.unsafeCast<Any>(),
            frameSide = width,
            frameOffset = frameIndex * width,
            averageColor = averageFrameColor(bitmap, width, frameIndex * width),
        )
        decodedTextureValues += decodedAssetTexture
        CachedResource.Available(decodedAssetTexture)
    }

    private suspend fun loadJsonResource(entryName: String): CachedResource<JsonElement> = cacheFailures {
        val entry =
            entries[entryName] ?: return@cacheFailures CachedResource.Unavailable("Missing resource: $entryName")
        if (entry.uncompressedSize > MAX_JSON_BYTES) {
            return@cacheFailures CachedResource.Unavailable("JSON resource is too large: $entryName")
        }
        val blob = entry.getData(BlobWriter("application/json")).await()
        val text = blob.text().unsafeCast<Promise<String>>().await()
        CachedResource.Available(AssetJson.parseToJsonElement(text))
    }

    private suspend fun <V> cacheFailures(block: suspend () -> CachedResource<V>): CachedResource<V> = try {
        block()
    } catch (cancellationException: CancellationException) {
        throw cancellationException
    } catch (failure: Throwable) {
        CachedResource.Unavailable(failure.message ?: failure::class.simpleName ?: "Asset loading failed")
    }

    private fun CachedResource<*>.reportFailure() {
        if (this is CachedResource.Unavailable) resourceFailure(message)
    }

    companion object {
        suspend fun open(
            minecraftVersion: String,
            abortController: dynamic,
            progressChanged: (OfficialAssetLoadProgress) -> Unit,
            resourceFailure: (String) -> Unit,
            assetsChanged: () -> Unit,
        ): OfficialAssetSession {
            progressChanged(
                OfficialAssetLoadProgress(
                    "Reading the official Mojang version manifest…",
                    0,
                    ASSET_LOAD_STEPS
                )
            )
            val manifestBytes = fetchBytes(OFFICIAL_VERSION_MANIFEST_URL, MAX_METADATA_BYTES, abortController.signal)
            val manifest = AssetJson.decodeFromString<PistonVersionManifest>(decodeUtf8(manifestBytes))
            val versionReference = manifest.versions.singleOrNull { version -> version.id == minecraftVersion }
                ?: error("The official Mojang version manifest does not contain $minecraftVersion")
            check(versionReference.url.startsWith("https://")) { "The official version metadata URL is not HTTPS" }
            progressChanged(OfficialAssetLoadProgress("Verifying the official version metadata…", 1, ASSET_LOAD_STEPS))
            val metadataBytes = fetchBytes(versionReference.url, MAX_METADATA_BYTES, abortController.signal)
            val metadataSha1 = sha1(metadataBytes.buffer)
            check(metadataSha1.equals(versionReference.sha1, ignoreCase = true)) {
                "The official version metadata SHA-1 does not match"
            }
            val versionMetadata = AssetJson.decodeFromString<PistonVersionMetadata>(decodeUtf8(metadataBytes))
            val clientDownload = versionMetadata.downloads.client
            check(clientDownload.url.startsWith("https://")) { "The official client JAR URL is not HTTPS" }
            progressChanged(
                OfficialAssetLoadProgress(
                    "Checking the official client JAR Range endpoint…",
                    2,
                    ASSET_LOAD_STEPS
                )
            )
            validateClientDownload(clientDownload, abortController.signal)
            progressChanged(
                OfficialAssetLoadProgress(
                    "Opening the official client JAR Range session…",
                    3,
                    ASSET_LOAD_STEPS
                )
            )
            val zipOptions = js("({})")
            zipOptions.chunkSize = MAXIMUM_RANGE_BYTES
            check(clientDownload.size <= Int.MAX_VALUE) { "The official client JAR is too large for this browser demo" }
            var rangeLoadStep = 3
            var loadedAssetPages: Int? = null
            var totalAssetPages: Int? = null
            var loadedAssetBytes: Int? = null
            var totalAssetBytes: Int? = null
            val boundedHttpRangeReader = BoundedHttpRangeReader(
                url = clientDownload.url,
                archiveSize = clientDownload.size.toInt(),
                abortSignal = abortController.signal,
                pageRetrying = { pageIndex, failedAttempts, message ->
                    progressChanged(
                        OfficialAssetLoadProgress(
                            action = "Range page ${pageIndex + 1} failed on attempt $failedAttempts; retrying in 2 seconds: $message",
                            completedSteps = rangeLoadStep,
                            totalSteps = ASSET_LOAD_STEPS,
                            loadedPages = loadedAssetPages,
                            totalPages = totalAssetPages,
                            loadedBytes = loadedAssetBytes,
                            totalBytes = totalAssetBytes,
                        ),
                    )
                },
            )
            val zipReader = ZipReader(boundedHttpRangeReader.reader, zipOptions)
            return try {
                val entryOptions = js("({})")
                entryOptions.onprogress = { indexedFiles: Int, totalFiles: Int, _: dynamic ->
                    if (indexedFiles == totalFiles || indexedFiles % INDEX_PROGRESS_INTERVAL == 0) {
                        progressChanged(
                            OfficialAssetLoadProgress(
                                action = "Indexing official client JAR assets…",
                                completedSteps = 3,
                                totalSteps = ASSET_LOAD_STEPS,
                                indexedFiles = indexedFiles,
                                totalFiles = totalFiles,
                            ),
                        )
                    }
                }
                val entries = zipReader.getEntries(entryOptions).await()
                val entriesByName = linkedMapOf<String, ZipEntry>()
                entries.forEach { entry ->
                    if (!entry.directory) {
                        check(entry.filename.isSafeZipEntryName()) { "Official client JAR has an unsafe entry: ${entry.filename}" }
                        check(
                            entriesByName.put(
                                entry.filename,
                                entry
                            ) == null
                        ) { "Official client JAR has a duplicate entry: ${entry.filename}" }
                    }
                }
                entriesByName.requireOfficialAssetEntries()
                val assetArchivePlan = entries.toAssetArchivePlan(clientDownload.size.toInt())
                rangeLoadStep = 4
                val assetRangePrefetchResult = boundedHttpRangeReader.prefetch(assetArchivePlan.spans) {
                        loadedPages,
                        totalPages,
                        loadedBytes,
                        totalBytes,
                    ->
                    loadedAssetPages = loadedPages
                    totalAssetPages = totalPages
                    loadedAssetBytes = loadedBytes
                    totalAssetBytes = totalBytes
                    progressChanged(
                        OfficialAssetLoadProgress(
                            action = "Caching compressed official asset ranges…",
                            completedSteps = 4,
                            totalSteps = ASSET_LOAD_STEPS,
                            indexedFiles = assetArchivePlan.fileCount,
                            totalFiles = assetArchivePlan.fileCount,
                            loadedPages = loadedPages,
                            totalPages = totalPages,
                            loadedBytes = loadedBytes,
                            totalBytes = totalBytes,
                        ),
                    )
                }
                progressChanged(
                    OfficialAssetLoadProgress(
                        action = "Official Minecraft assets are ready",
                        completedSteps = ASSET_LOAD_STEPS,
                        totalSteps = ASSET_LOAD_STEPS,
                        indexedFiles = assetArchivePlan.fileCount,
                        totalFiles = assetArchivePlan.fileCount,
                        loadedPages = assetRangePrefetchResult.pageCount,
                        totalPages = assetRangePrefetchResult.pageCount,
                        loadedBytes = assetRangePrefetchResult.byteCount,
                        totalBytes = assetRangePrefetchResult.byteCount,
                    ),
                )
                OfficialAssetSession(
                    assetRevision = clientDownload.sha1,
                    abortController = abortController,
                    boundedHttpRangeReader = boundedHttpRangeReader,
                    zipReader = zipReader,
                    entries = entriesByName,
                    resourceFailure = resourceFailure,
                    assetsChanged = assetsChanged,
                )
            } catch (failure: Throwable) {
                abortController.abort()
                boundedHttpRangeReader.cancel()
                withContext(NonCancellable) {
                    try {
                        zipReader.close().await()
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
        }

        private suspend fun validateClientDownload(clientDownload: PistonDownload, abortSignal: dynamic) {
            val headOptions = js("({})")
            headOptions.method = "HEAD"
            headOptions.cache = "no-store"
            headOptions.signal = abortSignal
            val headResponse = fetch(clientDownload.url, headOptions)
            check(headResponse.ok.unsafeCast<Boolean>()) { "The official client JAR HEAD request failed: HTTP ${headResponse.status}" }
            val contentLength = headResponse.headers.get("Content-Length")?.unsafeCast<String>()?.toLongOrNull()
            check(contentLength == clientDownload.size) {
                "The official client JAR Content-Length does not match the version metadata"
            }
            val rangeOptions = js("({})")
            rangeOptions.headers = js("({ Range: 'bytes=0-0' })")
            rangeOptions.cache = "no-store"
            rangeOptions.signal = abortSignal
            val rangeResponse = fetch(clientDownload.url, rangeOptions)
            check(rangeResponse.status.unsafeCast<Int>() == 206) { "The official client JAR does not support HTTP Range: HTTP ${rangeResponse.status}" }
            val contentRange = rangeResponse.headers.get("Content-Range")?.unsafeCast<String>()
            if (contentRange != null) {
                check(contentRange == "bytes 0-0/${clientDownload.size}") { "The official client JAR returned an invalid Content-Range" }
            }
            val rangeBytes = rangeResponse.arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
            check(rangeBytes.byteLength == 1) { "The official client JAR Range probe did not return one byte" }
        }
    }
}

private class BoundedHttpRangeReader(
    private val url: String,
    private val archiveSize: Int,
    private val abortSignal: dynamic,
    private val pageRetrying: (Int, Int, String) -> Unit,
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pages = mutableMapOf<Int, Deferred<Uint8Array>>()
    val reader = DelegatingRangeReader(archiveSize, ::readUint8Array, ::createReadable)

    private fun readUint8Array(index: Int, length: Int): Promise<Uint8Array> = coroutineScope.promise {
        require(index >= 0 && length >= 0 && index.toLong() + length.toLong() <= archiveSize.toLong()) {
            "Official client JAR read is outside the archive"
        }
        val result = Uint8Array(length)
        var offset = 0
        while (offset < length) {
            val archiveOffset = index + offset
            val pageIndex = archiveOffset / MAXIMUM_RANGE_BYTES
            val pageOffset = archiveOffset % MAXIMUM_RANGE_BYTES
            val page = page(pageIndex).await()
            val copyLength = minOf(page.length - pageOffset, length - offset)
            result.set(page.subarray(pageOffset, pageOffset + copyLength), offset)
            offset += copyLength
        }
        result
    }

    private fun createReadable(options: dynamic): dynamic {
        val rangeOffset = options.offset.unsafeCast<Int>()
        val rangeSize = options.size.unsafeCast<Int>()
        var emittedBytes = 0
        val underlyingSource = js("({})")
        underlyingSource.pull = { controller: dynamic ->
            coroutineScope.promise {
                if (emittedBytes >= rangeSize) {
                    controller.close()
                } else {
                    val length = minOf(MAXIMUM_RANGE_BYTES, rangeSize - emittedBytes)
                    val bytes = readUint8Array(rangeOffset + emittedBytes, length).await()
                    emittedBytes += bytes.length
                    controller.enqueue(bytes)
                    if (emittedBytes >= rangeSize) controller.close()
                }
            }
        }
        return createReadableStream(underlyingSource)
    }

    suspend fun prefetch(
        spans: List<AssetArchiveSpan>,
        progressChanged: (loadedPages: Int, totalPages: Int, loadedBytes: Int, totalBytes: Int) -> Unit,
    ): AssetRangePrefetchResult {
        val sortedPageIndices = assetArchivePageIndices(spans, MAXIMUM_RANGE_BYTES, archiveSize)
        val totalBytes = sortedPageIndices.sumOf(::pageLength)
        var loadedPages = 0
        var loadedBytes = 0
        progressChanged(loadedPages, sortedPageIndices.size, loadedBytes, totalBytes)
        sortedPageIndices.chunked(RANGE_PREFETCH_CONCURRENCY).forEach { batch ->
            batch.map { pageIndex ->
                coroutineScope.async {
                    page(pageIndex).await()
                    loadedPages++
                    loadedBytes += pageLength(pageIndex)
                    progressChanged(loadedPages, sortedPageIndices.size, loadedBytes, totalBytes)
                }
            }.awaitAll()
        }
        return AssetRangePrefetchResult(sortedPageIndices.size, totalBytes)
    }

    private fun page(pageIndex: Int): Deferred<Uint8Array> = pages.getOrPut(pageIndex) {
        coroutineScope.async { loadPage(pageIndex) }
    }

    private suspend fun loadPage(pageIndex: Int): Uint8Array {
        return retryAssetArchivePage(
            waitBeforeRetry = { delay(RANGE_RETRY_DELAY_MILLISECONDS) },
            retrying = { failedAttempts, failure ->
                pageRetrying(
                    pageIndex,
                    failedAttempts,
                    failure.message ?: failure::class.simpleName ?: "Range request failed"
                )
            },
            load = { loadPageOnce(pageIndex) },
        )
    }

    private suspend fun loadPageOnce(pageIndex: Int): Uint8Array {
        val offset = pageIndex * MAXIMUM_RANGE_BYTES
        val length = pageLength(pageIndex)
        val endOffset = offset + length - 1
        val requestOptions = js("({})")
        requestOptions.headers = js("({})")
        requestOptions.headers.Range = "bytes=$offset-$endOffset"
        requestOptions.cache = "no-store"
        requestOptions.signal = abortSignal
        val response = browserWindow.fetch(url, requestOptions).unsafeCast<Promise<dynamic>>().await()
        if (response.status.unsafeCast<Int>() != 206) {
            response.body?.cancel()?.unsafeCast<Promise<Unit>>()?.await()
            error("Official client JAR page request did not return HTTP 206")
        }
        val contentRange = response.headers.get("Content-Range")?.unsafeCast<String>()
        if (contentRange != null && contentRange != "bytes $offset-$endOffset/$archiveSize") {
            response.body?.cancel()?.unsafeCast<Promise<Unit>>()?.await()
            error("Official client JAR page request returned an invalid Content-Range")
        }
        val arrayBuffer = response.arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
        val bytes = Uint8Array(arrayBuffer)
        check(bytes.length == length) { "Official client JAR page request returned an invalid byte count" }
        return bytes
    }

    private fun pageLength(pageIndex: Int): Int {
        require(pageIndex >= 0 && pageIndex.toLong() * MAXIMUM_RANGE_BYTES < archiveSize.toLong()) {
            "Official client JAR page is outside the archive"
        }
        return minOf(MAXIMUM_RANGE_BYTES, archiveSize - pageIndex * MAXIMUM_RANGE_BYTES)
    }

    fun cancel() {
        coroutineScope.cancel()
    }
}

private fun createReadableStream(underlyingSource: dynamic): dynamic = js("new ReadableStream(underlyingSource)")

internal class OfficialAssetSessionManager(
    private val minecraftVersion: String,
    private val coroutineScope: CoroutineScope,
    private val progressChanged: (OfficialAssetLoadProgress) -> Unit,
    private val loadFailed: (String) -> Unit,
    private val sessionReady: (OfficialAssetSession) -> Unit,
    private val resourceFailure: (String) -> Unit,
    private val assetsChanged: () -> Unit,
) {
    var session: OfficialAssetSession? = null
        private set
    private var generation = 0L
    private var loadJob: Job? = null
    private var loadAbortController: dynamic = null

    fun loadOrRetry() {
        generation++
        val currentGeneration = generation
        loadJob?.cancel()
        loadAbortController?.abort()
        val oldSession = session
        session = null
        val abortController: dynamic = js("new AbortController()")
        loadAbortController = abortController
        progressChanged(
            OfficialAssetLoadProgress(
                "Preparing the official Mojang download endpoint…",
                0,
                ASSET_LOAD_STEPS
            )
        )
        loadJob = coroutineScope.launch {
            try {
                oldSession?.close()
                val newSession = OfficialAssetSession.open(
                    minecraftVersion = minecraftVersion,
                    abortController = abortController,
                    progressChanged = { progress ->
                        if (currentGeneration == generation) progressChanged(progress)
                    },
                    resourceFailure = { message ->
                        if (currentGeneration == generation) resourceFailure(message)
                    },
                    assetsChanged = {
                        if (currentGeneration == generation) assetsChanged()
                    },
                )
                if (currentGeneration == generation) {
                    session = newSession
                    sessionReady(newSession)
                    assetsChanged()
                } else {
                    newSession.close()
                }
            } catch (cancellationException: CancellationException) {
                abortController.abort()
                throw cancellationException
            } catch (failure: Throwable) {
                abortController.abort()
                if (currentGeneration == generation) {
                    loadFailed(failure.message ?: failure::class.simpleName ?: "Official asset loading failed")
                    assetsChanged()
                }
            } finally {
                if (currentGeneration == generation) {
                    loadJob = null
                    loadAbortController = null
                }
            }
        }
    }

    fun close() {
        generation++
        loadJob?.cancel()
        loadJob = null
        loadAbortController?.abort()
        loadAbortController = null
        val oldSession = session
        session = null
        coroutineScope.launch { oldSession?.close() }
    }
}

private data class DecodedAssetTexture(
    val bitmap: Any,
    val frameSide: Int,
    val frameOffset: Int,
    val averageColor: String,
) {
    fun close() {
        bitmap.unsafeCast<dynamic>().close()
    }
}

private data class BakedSpriteKey(
    val blockName: Identifier,
    val modelReferences: List<AssetModelReference>,
)

private data class AssetArchivePlan(
    val spans: List<AssetArchiveSpan>,
    val fileCount: Int,
)

private data class AssetRangePrefetchResult(
    val pageCount: Int,
    val byteCount: Int,
)

private data class ProjectedRectangle(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

@Serializable
private data class PistonVersionManifest(
    val versions: List<PistonVersionReference>,
)

@Serializable
private data class PistonVersionReference(
    val id: String,
    val url: String,
    val sha1: String,
)

@Serializable
private data class PistonVersionMetadata(
    val downloads: PistonDownloads,
)

@Serializable
private data class PistonDownloads(
    val client: PistonDownload,
)

@Serializable
private data class PistonDownload(
    val sha1: String,
    val size: Long,
    val url: String,
)

private suspend fun fetchBytes(url: String, maximumBytes: Int, abortSignal: dynamic): Uint8Array {
    val options = js("({})")
    options.signal = abortSignal
    val response = fetch(url, options)
    check(response.ok.unsafeCast<Boolean>()) { "The official metadata request failed: HTTP ${response.status}" }
    val arrayBuffer = response.arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
    val bytes = Uint8Array(arrayBuffer)
    check(bytes.length <= maximumBytes) { "The official metadata response exceeds the size limit" }
    return bytes
}

private suspend fun fetch(url: String, options: dynamic): dynamic =
    browserWindow.fetch(url, options).unsafeCast<Promise<dynamic>>().await()

private suspend fun sha1(arrayBuffer: ArrayBuffer): String {
    val digest = browserWindow.crypto.subtle.digest("SHA-1", arrayBuffer).unsafeCast<Promise<ArrayBuffer>>().await()
    val bytes = Uint8Array(digest)
    return (0 until bytes.length).joinToString("") { index -> bytes[index].toString(16).padStart(2, '0') }
}

private fun decodeUtf8(bytes: Uint8Array): String {
    val decoder: dynamic = js("new TextDecoder('utf-8', { fatal: true })")
    return decoder.decode(bytes).unsafeCast<String>()
}

private fun String.isSafeZipEntryName(): Boolean =
    isNotEmpty() && !startsWith('/') && '\\' !in this && split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".."
    }

private fun Map<String, ZipEntry>.requireOfficialAssetEntries() {
    check(keys.any { entryName -> entryName.isAssetEntry("blockstates", ".json") }) {
        "Official client JAR contains no block-state definitions"
    }
    check(keys.any { entryName -> entryName.isAssetEntry("models", ".json") }) {
        "Official client JAR contains no models"
    }
    check(keys.any { entryName -> entryName.isAssetEntry("textures", ".png") }) {
        "Official client JAR contains no textures"
    }
}

private fun Array<ZipEntry>.toAssetArchivePlan(archiveSize: Int): AssetArchivePlan {
    require(archiveSize > 0) { "Official client JAR size must be positive" }
    val sortedEntries = sortedBy(ZipEntry::offset)
    val spans = mutableListOf<AssetArchiveSpan>()
    var fileCount = 0
    var compressedBytes = 0L
    sortedEntries.forEachIndexed { index, entry ->
        check(entry.offset in 0 until archiveSize) { "Official client JAR entry offset is outside the archive: ${entry.filename}" }
        check(entry.compressedSize >= 0) { "Official client JAR entry has a negative compressed size: ${entry.filename}" }
        if (!entry.directory && entry.filename.isPreloadedAssetEntry()) {
            val nextOffset = sortedEntries.getOrNull(index + 1)?.offset ?: archiveSize
            check(nextOffset > entry.offset && nextOffset <= archiveSize) {
                "Official client JAR entry extent is invalid: ${entry.filename}"
            }
            check(entry.offset.toLong() + entry.compressedSize.toLong() <= archiveSize.toLong()) {
                "Official client JAR entry data is outside the archive: ${entry.filename}"
            }
            spans += AssetArchiveSpan(entry.offset, nextOffset - entry.offset)
            fileCount++
            compressedBytes += entry.compressedSize
        }
    }
    check(spans.isNotEmpty()) { "Official client JAR contains no preloadable assets" }
    check(compressedBytes <= MAX_PRELOADED_COMPRESSED_ASSET_BYTES) {
        "Official client JAR compressed assets exceed the browser preload limit: $compressedBytes bytes"
    }
    return AssetArchivePlan(mergeAssetArchiveSpans(spans), fileCount)
}

private fun String.isPreloadedAssetEntry(): Boolean =
    isAssetEntry("blockstates", ".json") ||
            isAssetEntry("models", ".json") ||
            isAssetEntry("textures", ".png") ||
            isAssetEntry("textures", ".png.mcmeta")

private fun String.isAssetEntry(directory: String, suffix: String): Boolean {
    val segments = split('/')
    return segments.size >= 4 && segments[0] == "assets" && segments[2] == directory && endsWith(suffix)
}

private fun <V> CachedResource<V>.availableOrNull(): V? = (this as? CachedResource.Available)?.value

private fun sourceTopDirection(xRotation: Int): AssetDirection = when (xRotation) {
    0 -> AssetDirection.UP
    90 -> AssetDirection.NORTH
    180 -> AssetDirection.DOWN
    270 -> AssetDirection.SOUTH
    else -> error("Unsupported model X rotation: $xRotation")
}

private fun projectedHeight(element: AssetModelElement, direction: AssetDirection): Float = when (direction) {
    AssetDirection.UP -> element.to.y
    AssetDirection.DOWN -> MODEL_SIDE - element.from.y
    AssetDirection.NORTH -> MODEL_SIDE - element.from.z
    AssetDirection.SOUTH -> element.to.z
    AssetDirection.WEST -> MODEL_SIDE - element.from.x
    AssetDirection.EAST -> element.to.x
}

private fun projectedRectangle(element: AssetModelElement, direction: AssetDirection): ProjectedRectangle =
    when (direction) {
        AssetDirection.UP, AssetDirection.DOWN -> ProjectedRectangle(
            element.from.x.toDouble(),
            element.from.z.toDouble(),
            element.to.x.toDouble(),
            element.to.z.toDouble(),
        )

        AssetDirection.NORTH, AssetDirection.SOUTH -> ProjectedRectangle(
            element.from.x.toDouble(),
            (MODEL_SIDE - element.to.y).toDouble(),
            element.to.x.toDouble(),
            (MODEL_SIDE - element.from.y).toDouble(),
        )

        AssetDirection.WEST, AssetDirection.EAST -> ProjectedRectangle(
            element.from.z.toDouble(),
            (MODEL_SIDE - element.to.y).toDouble(),
            element.to.z.toDouble(),
            (MODEL_SIDE - element.from.y).toDouble(),
        )
    }

private fun defaultFaceUv(element: AssetModelElement, direction: AssetDirection): List<Float> = when (direction) {
    AssetDirection.UP, AssetDirection.DOWN -> listOf(element.from.x, element.from.z, element.to.x, element.to.z)
    AssetDirection.NORTH, AssetDirection.SOUTH -> listOf(
        element.from.x,
        MODEL_SIDE - element.to.y,
        element.to.x,
        MODEL_SIDE - element.from.y,
    )

    AssetDirection.WEST, AssetDirection.EAST -> listOf(
        element.from.z,
        MODEL_SIDE - element.to.y,
        element.to.z,
        MODEL_SIDE - element.from.y,
    )
}

private fun tintColor(identifier: Identifier): String = when {
    "water" in identifier.path -> "#3f76e4"
    "redstone" in identifier.path -> "#b02e26"
    "grass" in identifier.path || "fern" in identifier.path -> "#78a84f"
    "leaves" in identifier.path || "vine" in identifier.path -> "#5f9f45"
    else -> "#ffffff"
}

private fun usesBuiltInTint(identifier: Identifier): Boolean =
    "water" in identifier.path ||
            "redstone" in identifier.path ||
            "grass" in identifier.path ||
            "fern" in identifier.path ||
            "leaves" in identifier.path ||
            "vine" in identifier.path

private fun averageFrameColor(bitmap: dynamic, frameSide: Int, frameOffset: Int): String {
    val sampleCanvas: dynamic = browserDocument.createElement("canvas")
    sampleCanvas.width = SPRITE_SIDE
    sampleCanvas.height = SPRITE_SIDE
    val context: dynamic = sampleCanvas.getContext("2d")
    context.imageSmoothingEnabled = true
    context.drawImage(bitmap, 0, frameOffset, frameSide, frameSide, 0, 0, SPRITE_SIDE, SPRITE_SIDE)
    val pixels: dynamic = context.getImageData(0, 0, SPRITE_SIDE, SPRITE_SIDE).data
    var alphaSum = 0.0
    var redSum = 0.0
    var greenSum = 0.0
    var blueSum = 0.0
    var index = 0
    while (index < SPRITE_SIDE * SPRITE_SIDE * RGBA_COMPONENT_COUNT) {
        val alpha = pixels[index + 3].unsafeCast<Int>() / 255.0
        redSum += pixels[index].unsafeCast<Int>() * alpha
        greenSum += pixels[index + 1].unsafeCast<Int>() * alpha
        blueSum += pixels[index + 2].unsafeCast<Int>() * alpha
        alphaSum += alpha
        index += RGBA_COMPONENT_COUNT
    }
    if (alphaSum == 0.0) return "#000000"
    return "rgb(${(redSum / alphaSum).roundToInt()} ${(greenSum / alphaSum).roundToInt()} ${(blueSum / alphaSum).roundToInt()})"
}

private val AssetJson: Json = Json { ignoreUnknownKeys = true }
private val browserWindow: dynamic = js("window")
private val browserDocument: dynamic = js("document")
private const val OFFICIAL_VERSION_MANIFEST_URL: String =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
private const val MAX_METADATA_BYTES: Int = 8 * 1024 * 1024
private const val MAX_JSON_BYTES: Double = 8.0 * 1024.0 * 1024.0
private const val MAX_TEXTURE_BYTES: Double = 64.0 * 1024.0 * 1024.0
private const val MAXIMUM_RANGE_BYTES: Int = 64 * 1024
private const val MAX_PRELOADED_COMPRESSED_ASSET_BYTES: Int = 32 * 1024 * 1024
private const val ASSET_LOAD_STEPS: Int = 5
private const val INDEX_PROGRESS_INTERVAL: Int = 128
private const val RANGE_PREFETCH_CONCURRENCY: Int = 12
private const val RANGE_RETRY_DELAY_MILLISECONDS: Long = 2_000L
private const val MODEL_SIDE: Float = 16f
private const val SPRITE_SIDE: Int = 16
private const val RGBA_COMPONENT_COUNT: Int = 4
