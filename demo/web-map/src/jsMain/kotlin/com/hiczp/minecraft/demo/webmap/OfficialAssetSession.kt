@file:Suppress("UnsafeCastFromDynamic")

package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.khronos.webgl.toUint8Array
import kotlin.js.Promise
import kotlin.math.PI

class OfficialAssetSession(
    val assetRevision: String,
    private val resourceFailure: (String) -> Unit,
) {
    private val sessionJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + sessionJob)
    private val connectedService = MutableStateFlow<WebMapService?>(null)
    private val blockRenderResources =
        mutableMapOf<SurfaceBlockState, CompletableDeferred<CachedResource<BlockRenderResource>>>()
    private val pendingSpriteRequests = mutableSetOf<SurfaceBlockRenderRequest>()
    private val decodedTextureValues = mutableListOf<DecodedAssetTexture>()
    private val decodedTextures = AsyncResourceCache(coroutineScope, ::loadTexture)
    private val sprites = AsyncResourceCache(coroutineScope, ::loadSprite)

    fun connected(webMapService: WebMapService) {
        connectedService.value = webMapService
    }

    fun disconnected() {
        connectedService.value = null
    }

    fun prefetch(surfaceBlockRenderRequests: Collection<SurfaceBlockRenderRequest>) {
        val distinctRequests = surfaceBlockRenderRequests.distinct()
        prefetchBlockRenderResources(distinctRequests.map(SurfaceBlockRenderRequest::surfaceBlockState))
        val newSpriteRequests = distinctRequests.filter(pendingSpriteRequests::add)
        if (newSpriteRequests.isEmpty()) return
        coroutineScope.launch {
            newSpriteRequests.forEach { surfaceBlockRenderRequest ->
                currentCoroutineContext().ensureActive()
                prefetchSprite(surfaceBlockRenderRequest)
            }
        }
    }

    private suspend fun prefetchSprite(surfaceBlockRenderRequest: SurfaceBlockRenderRequest) {
        try {
            val blockRenderResource = blockRenderResource(surfaceBlockRenderRequest.surfaceBlockState) ?: return
            blockRenderResource.sprite(
                surfaceBlockState = surfaceBlockRenderRequest.surfaceBlockState,
                blockX = surfaceBlockRenderRequest.blockX,
                blockZ = surfaceBlockRenderRequest.blockZ,
            )?.let(sprites::prefetch)
        } finally {
            pendingSpriteRequests -= surfaceBlockRenderRequest
        }
    }

    private fun prefetchBlockRenderResources(surfaceBlockStates: Collection<SurfaceBlockState>) {
        val missingBlockStates = surfaceBlockStates.distinct().filterNot(blockRenderResources::containsKey)
        missingBlockStates.forEach { surfaceBlockState ->
            blockRenderResources[surfaceBlockState] = CompletableDeferred(sessionJob)
        }
        missingBlockStates.chunked(MAX_BLOCK_RENDER_RESOURCE_BATCH_SIZE).forEach { blockStateBatch ->
            coroutineScope.launch {
                loadBlockRenderResourceBatch(blockStateBatch)
            }
        }
    }

    suspend fun sprite(surfaceBlockState: SurfaceBlockState, blockX: Int, blockZ: Int): Any? {
        prefetchBlockRenderResources(listOf(surfaceBlockState))
        val blockRenderResource = blockRenderResource(surfaceBlockState) ?: return null
        val surfaceSprite = blockRenderResource.sprite(surfaceBlockState, blockX, blockZ) ?: return null
        return sprites.get(surfaceSprite).availableOrNull()
    }

    fun close() {
        coroutineScope.cancel()
        decodedTextureValues.forEach(DecodedAssetTexture::close)
        decodedTextureValues.clear()
    }

    private suspend fun blockRenderResource(surfaceBlockState: SurfaceBlockState): BlockRenderResource? =
        blockRenderResources.getValue(surfaceBlockState).await().availableOrNull()

    private suspend fun loadBlockRenderResourceBatch(blockStateBatch: List<SurfaceBlockState>) {
        var previousService: WebMapService? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val pendingBlockStates = blockStateBatch.filter { surfaceBlockState ->
                blockRenderResources.getValue(surfaceBlockState).isActive
            }
            if (pendingBlockStates.isEmpty()) return
            val webMapService = awaitServiceAfter(previousService)
            try {
                val expectedBlockStates = pendingBlockStates.toSet()
                webMapService.blockRenderResources(
                    BlockRenderResourceRequest(assetRevision, pendingBlockStates),
                ).collect { blockRenderResourceResult ->
                    currentCoroutineContext().ensureActive()
                    val surfaceBlockState = blockRenderResourceResult.blockState
                    if (surfaceBlockState !in expectedBlockStates) return@collect
                    val deferred = blockRenderResources.getValue(surfaceBlockState)
                    if (!deferred.isActive) return@collect
                    val cachedResource = blockRenderResourceResult.resource?.let { blockRenderResource ->
                        CachedResource.Available(blockRenderResource)
                    }
                        ?: unavailable("Missing Block render resource for ${surfaceBlockState.canonicalAssetKey()}")
                    deferred.complete(cachedResource)
                }
                pendingBlockStates.forEach { surfaceBlockState ->
                    val deferred = blockRenderResources.getValue(surfaceBlockState)
                    if (deferred.isActive) {
                        deferred.complete(
                            unavailable("The backend omitted the Block render resource for ${surfaceBlockState.canonicalAssetKey()}"),
                        )
                    }
                }
                return
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                previousService = webMapService
            }
        }
    }

    private suspend fun loadSprite(surfaceSprite: SurfaceSprite): CachedResource<Any> = loadResource {
        val canvas: dynamic = browserDocument.createElement("canvas")
        canvas.width = SPRITE_SIDE
        canvas.height = SPRITE_SIDE
        val context: dynamic = canvas.getContext("2d")
        context.imageSmoothingEnabled = false
        val decodedTexturesByIdentifier = coroutineScope {
            surfaceSprite.layers.map(SurfaceSpriteLayer::texture).distinct().map { texture ->
                async { texture to decodedTextures.get(texture).availableOrNull() }
            }.awaitAll().toMap()
        }
        surfaceSprite.layers.forEach { surfaceSpriteLayer ->
            currentCoroutineContext().ensureActive()
            drawSurfaceSpriteLayer(
                context = context,
                surfaceSpriteLayer = surfaceSpriteLayer,
                decodedAssetTexture = decodedTexturesByIdentifier[surfaceSpriteLayer.texture],
            )
        }
        CachedResource.Available(canvas.unsafeCast<Any>())
    }

    private fun drawSurfaceSpriteLayer(
        context: dynamic,
        surfaceSpriteLayer: SurfaceSpriteLayer,
        decodedAssetTexture: DecodedAssetTexture?,
    ) {
        val destination = surfaceSpriteLayer.destination
        val centerX = (destination.left + destination.right) / 2.0
        val centerY = (destination.top + destination.bottom) / 2.0
        context.save()
        context.translate(SPRITE_SIDE / 2.0, SPRITE_SIDE / 2.0)
        context.rotate(surfaceSpriteLayer.yRotation * PI / 180.0)
        context.translate(-SPRITE_SIDE / 2.0, -SPRITE_SIDE / 2.0)
        context.translate(centerX, centerY)
        context.rotate(surfaceSpriteLayer.textureRotation * PI / 180.0)
        context.translate(-centerX, -centerY)
        if (surfaceSpriteLayer.flipTextureX || surfaceSpriteLayer.flipTextureY) {
            context.translate(centerX, centerY)
            context.scale(
                if (surfaceSpriteLayer.flipTextureX) -1.0 else 1.0,
                if (surfaceSpriteLayer.flipTextureY) -1.0 else 1.0,
            )
            context.translate(-centerX, -centerY)
        }
        if (decodedAssetTexture == null) {
            context.fillStyle = MISSING_TEXTURE_COLOR
            context.fillRect(
                destination.left,
                destination.top,
                destination.right - destination.left,
                destination.bottom - destination.top,
            )
        } else {
            val uv = surfaceSpriteLayer.uv
            val sourceSide = decodedAssetTexture.frameSide.toDouble()
            val sourceX = uv.left / MODEL_SIDE * sourceSide
            val sourceY = decodedAssetTexture.frameOffset + uv.top / MODEL_SIDE * sourceSide
            val sourceWidth = (uv.right - uv.left) / MODEL_SIDE * sourceSide
            val sourceHeight = (uv.bottom - uv.top) / MODEL_SIDE * sourceSide
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
            surfaceSpriteLayer.tintColor?.let { tintColor ->
                context.globalCompositeOperation = "source-atop"
                context.fillStyle = tintColor
                context.fillRect(
                    destination.left,
                    destination.top,
                    destination.right - destination.left,
                    destination.bottom - destination.top,
                )
            }
        }
        context.restore()
    }

    private suspend fun loadTexture(identifier: Identifier): CachedResource<DecodedAssetTexture> = loadResource {
        val textureResource = requestTextureResource(identifier)
            ?: return@loadResource CachedResource.Unavailable("Missing texture: $identifier")
        if (textureResource.png.size > MAXIMUM_TEXTURE_BYTES) {
            return@loadResource CachedResource.Unavailable("Texture is too large: $identifier")
        }
        val blob = createPngBlob(textureResource.png.toPngUint8Array())
        val bitmap = browserWindow.createImageBitmap(blob).unsafeCast<Promise<dynamic>>().await()
        var retained = false
        try {
            val width = bitmap.width.unsafeCast<Int>()
            val height = bitmap.height.unsafeCast<Int>()
            if (width !in 1..height || height % width != 0) {
                return@loadResource CachedResource.Unavailable("Texture has invalid dimensions: $identifier")
            }
            val frameCount = height / width
            val decodedAssetTexture = DecodedAssetTexture(
                bitmap = bitmap.unsafeCast<Any>(),
                frameSide = width,
                frameOffset = textureResource.animationFrame.coerceIn(0, frameCount - 1) * width,
            )
            currentCoroutineContext().ensureActive()
            decodedTextureValues += decodedAssetTexture
            retained = true
            CachedResource.Available(decodedAssetTexture)
        } finally {
            if (!retained) bitmap.close()
        }
    }

    private suspend fun requestTextureResource(identifier: Identifier): TextureResource? {
        var previousService: WebMapService? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val webMapService = awaitServiceAfter(previousService)
            try {
                return webMapService.textureResource(TextureResourceRequest(assetRevision, identifier))
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                previousService = webMapService
            }
        }
    }

    private suspend fun awaitServiceAfter(previousService: WebMapService?): WebMapService =
        connectedService.filterNotNull().first { webMapService ->
            previousService == null || webMapService !== previousService
        }

    private suspend fun <V> loadResource(block: suspend () -> CachedResource<V>): CachedResource<V> {
        val cachedResource = try {
            block()
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (failure: Throwable) {
            CachedResource.Unavailable(failure.message ?: failure::class.simpleName ?: "Asset loading failed")
        }
        if (cachedResource is CachedResource.Unavailable) resourceFailure(cachedResource.message)
        return cachedResource
    }

    private fun unavailable(message: String): CachedResource.Unavailable =
        CachedResource.Unavailable(message).also { resourceFailure(message) }
}

data class SurfaceBlockRenderRequest(
    val surfaceBlockState: SurfaceBlockState,
    val blockX: Int,
    val blockZ: Int,
)

private data class DecodedAssetTexture(
    val bitmap: Any,
    val frameSide: Int,
    val frameOffset: Int,
) {
    fun close() {
        bitmap.unsafeCast<dynamic>().close()
    }
}

private fun <V> CachedResource<V>.availableOrNull(): V? = (this as? CachedResource.Available)?.value

@OptIn(ExperimentalUnsignedTypes::class)
private fun ByteArray.toPngUint8Array(): dynamic = asUByteArray().toUint8Array()

private val createPngBlob: dynamic = js("(bytes) => new Blob([bytes], { type: 'image/png' })")
private val browserWindow: dynamic = js("window")
private val browserDocument: dynamic = js("document")
private const val MAXIMUM_TEXTURE_BYTES: Int = 64 * 1024 * 1024
private const val MODEL_SIDE: Float = 16f
private const val SPRITE_SIDE: Int = 16
private const val MISSING_TEXTURE_COLOR: String = "#000000"
