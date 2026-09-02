package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaBlockState
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class BlockAssetIndex(
    private val transparentBlockStates: Set<SurfaceBlockState>,
    private val serverAssetIndex: ServerAssetIndex,
) : SurfaceBlockTransparency {
    private val blockRenderResourceSlotsMutex = Mutex()
    private val blockRenderResourceSlots = mutableMapOf<SurfaceBlockState, BlockRenderResourceCacheSlot>()

    override fun isTransparent(surfaceBlockState: SurfaceBlockState): Boolean =
        surfaceBlockState in transparentBlockStates

    suspend fun blockRenderResource(surfaceBlockState: SurfaceBlockState): BlockRenderResource? {
        val blockRenderResourceCacheSlot = blockRenderResourceSlotsMutex.withLock {
            blockRenderResourceSlots.getOrPut(surfaceBlockState, ::BlockRenderResourceCacheSlot)
        }
        return blockRenderResourceCacheSlot.mutex.withLock {
            if (!blockRenderResourceCacheSlot.initialized) {
                blockRenderResourceCacheSlot.resource = serverAssetIndex.blockRenderResource(surfaceBlockState)
                blockRenderResourceCacheSlot.initialized = true
            }
            blockRenderResourceCacheSlot.resource
        }
    }

    fun animationFrame(texture: Identifier): Int = serverAssetIndex.animationFrame(texture)

    companion object {
        fun create(
            resources: Map<String, ByteArray>,
            vanillaBlockStates: List<VanillaBlockState>,
        ): BlockAssetIndex {
            val serverAssetIndex = ServerAssetIndex(resources)
            val transparentBlockStates = vanillaBlockStates.map { vanillaBlockState ->
                SurfaceBlockState(vanillaBlockState.blockId, vanillaBlockState.properties)
            }.filterTo(linkedSetOf(), serverAssetIndex::isTransparent)
            return BlockAssetIndex(transparentBlockStates, serverAssetIndex)
        }
    }
}

private class BlockRenderResourceCacheSlot {
    val mutex = Mutex()
    var initialized: Boolean = false
    var resource: BlockRenderResource? = null
}

class ServerAssetIndex(
    resources: Map<String, ByteArray>,
) {
    private val blockStateDefinitions: Map<Identifier, AssetBlockStateDefinition> =
        resources.mapNotNull { (path, bytes) ->
            path.assetIdentifier("blockstates", ".json")?.let { identifier ->
                identifier to MinecraftAssetJsonParser.parseBlockState(ASSET_JSON.parseToJsonElement(bytes.decodeToString()))
            }
        }.toMap()
    private val models: Map<Identifier, AssetModel> = resources.mapNotNull { (path, bytes) ->
        path.assetIdentifier("models", ".json")?.let { identifier ->
            identifier to MinecraftAssetJsonParser.parseModel(
                ASSET_JSON.parseToJsonElement(bytes.decodeToString()),
                identifier.namespace,
            )
        }
    }.toMap()
    private val resolvedModels: Map<Identifier, ResolvedAssetModel> = buildMap {
        models.keys.forEach { identifier -> resolveModel(identifier, mutableSetOf())?.let { put(identifier, it) } }
    }
    private val definitelyOpaqueTextures: Set<Identifier> = resources.mapNotNullTo(linkedSetOf()) { (path, bytes) ->
        path.assetIdentifier("textures", ".png")?.takeIf { PngTransparency.definitelyOpaque(bytes) }
    }
    private val animationFrames: Map<Identifier, Int> = resources.mapNotNull { (path, bytes) ->
        path.assetIdentifier("textures", ".png.mcmeta")?.let { identifier ->
            identifier to MinecraftAssetJsonParser.firstAnimationFrame(
                ASSET_JSON.parseToJsonElement(bytes.decodeToString()),
            )
        }
    }.toMap()
    private val opaqueModels = mutableMapOf<AssetModelReference, Boolean>()

    fun isTransparent(surfaceBlockState: SurfaceBlockState): Boolean {
        val modelChoices = blockStateDefinitions[surfaceBlockState.name]?.modelChoices(surfaceBlockState).orEmpty()
        if (modelChoices.isEmpty()) return false
        val hasGuaranteedOpaqueChoice = modelChoices.any { assetModelReferences ->
            assetModelReferences.all(::isOpaque)
        }
        return !hasGuaranteedOpaqueChoice
    }

    fun blockRenderResource(surfaceBlockState: SurfaceBlockState): BlockRenderResource? {
        val modelChoices = blockStateDefinitions[surfaceBlockState.name]?.modelChoices(surfaceBlockState).orEmpty()
        if (modelChoices.isEmpty()) return null
        return BlockRenderResource(
            modelChoices = modelChoices.map { assetModelReferences ->
                SurfaceModelChoice(
                    models = assetModelReferences.map { assetModelReference ->
                        surfaceModelResource(surfaceBlockState, assetModelReference)
                    },
                )
            },
        )
    }

    fun animationFrame(texture: Identifier): Int = animationFrames[texture] ?: 0

    private fun surfaceModelResource(
        surfaceBlockState: SurfaceBlockState,
        assetModelReference: AssetModelReference,
    ): SurfaceModelResource {
        val resolvedAssetModel = resolvedModels[assetModelReference.model]
            ?: return SurfaceModelResource(assetModelReference.weight, emptyList(), particleLayer = null)
        val sourceDirection = sourceTopDirection(assetModelReference.xRotation)
        val topLayers = resolvedAssetModel.elements.sortedBy { assetModelElement ->
            projectedHeight(assetModelElement, sourceDirection)
        }.mapNotNull { assetModelElement ->
            val assetModelFace = assetModelElement.faces[sourceDirection] ?: return@mapNotNull null
            val assetTextureSlot = resolveTextureReference(resolvedAssetModel.textures, assetModelFace.texture)
                ?: return@mapNotNull null
            val projectedRectangle = projectedRawRectangle(assetModelElement, sourceDirection)
            val textureUv = assetModelFace.uv?.toSurfaceTextureUv() ?: projectedRectangle.textureUv
            SurfaceSpriteLayer(
                texture = parseAssetIdentifier(assetTextureSlot.sprite, assetModelReference.model.namespace),
                destination = projectedRectangle.normalized,
                uv = textureUv.rectangle,
                yRotation = assetModelReference.yRotation,
                textureRotation = assetModelFace.rotation,
                flipTextureX = textureUv.flipX,
                flipTextureY = textureUv.flipY,
                tintColor = assetModelFace.tintIndex?.let { tintColor(surfaceBlockState.name) },
            )
        }
        val particleLayer =
            resolveTextureReference(resolvedAssetModel.textures, "#particle")?.let { particleTextureSlot ->
                SurfaceSpriteLayer(
                    texture = parseAssetIdentifier(particleTextureSlot.sprite, assetModelReference.model.namespace),
                    destination = FULL_SPRITE_RECTANGLE,
                    uv = FULL_SPRITE_RECTANGLE,
                    yRotation = 0,
                    textureRotation = 0,
                    tintColor = tintColor(surfaceBlockState.name).takeIf { usesBuiltInTint(surfaceBlockState.name) },
                )
            }
        return SurfaceModelResource(
            weight = assetModelReference.weight,
            topLayers = topLayers,
            particleLayer = particleLayer,
        )
    }

    private fun isOpaque(assetModelReference: AssetModelReference): Boolean =
        opaqueModels.getOrPut(assetModelReference) {
            val resolvedAssetModel = resolvedModels[assetModelReference.model] ?: return@getOrPut false
            val sourceDirection = sourceTopDirection(assetModelReference.xRotation)
            val coverage = BooleanArray(SPRITE_SIDE * SPRITE_SIDE)
            var hasTopFace = false
            resolvedAssetModel.elements.forEach { assetModelElement ->
                val assetModelFace = assetModelElement.faces[sourceDirection] ?: return@forEach
                hasTopFace = true
                val assetTextureSlot = resolveTextureReference(resolvedAssetModel.textures, assetModelFace.texture)
                    ?: return@forEach
                if (assetTextureSlot.forceTranslucent) return@forEach
                val texture = parseAssetIdentifier(assetTextureSlot.sprite, assetModelReference.model.namespace)
                if (texture !in definitelyOpaqueTextures) return@forEach
                val projectedRectangle = projectedRawRectangle(assetModelElement, sourceDirection).normalized
                for (pixelZ in 0 until SPRITE_SIDE) {
                    for (pixelX in 0 until SPRITE_SIDE) {
                        if (
                            pixelX + 0.5 >= projectedRectangle.left &&
                            pixelX + 0.5 < projectedRectangle.right &&
                            pixelZ + 0.5 >= projectedRectangle.top &&
                            pixelZ + 0.5 < projectedRectangle.bottom
                        ) {
                            coverage[pixelZ * SPRITE_SIDE + pixelX] = true
                        }
                    }
                }
            }
            if (coverage.all { it }) return@getOrPut true
            if (hasTopFace) return@getOrPut false
            val particleTextureSlot = resolveTextureReference(resolvedAssetModel.textures, "#particle")
                ?: return@getOrPut false
            if (particleTextureSlot.forceTranslucent) return@getOrPut false
            parseAssetIdentifier(
                particleTextureSlot.sprite,
                assetModelReference.model.namespace
            ) in definitelyOpaqueTextures
        }

    private fun MutableMap<Identifier, ResolvedAssetModel>.resolveModel(
        identifier: Identifier,
        visiting: MutableSet<Identifier>,
    ): ResolvedAssetModel? {
        get(identifier)?.let { return it }
        if (!visiting.add(identifier)) return null
        val model = models[identifier] ?: return null
        val parent = model.parent?.let { parentIdentifier -> resolveModel(parentIdentifier, visiting) }
        visiting.remove(identifier)
        if (model.parent != null && parent == null) return null
        val textures = linkedMapOf<String, AssetTextureSlot>()
        parent?.textures?.let(textures::putAll)
        textures.putAll(model.textures)
        return ResolvedAssetModel(
            textures = textures,
            elements = model.elements ?: parent?.elements.orEmpty(),
        ).also { resolvedAssetModel -> put(identifier, resolvedAssetModel) }
    }
}

private object PngTransparency {
    fun definitelyOpaque(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size + PNG_CHUNK_HEADER_BYTES + PNG_IHDR_BYTES) return false
        if (!bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return false
        var offset = PNG_SIGNATURE.size
        var colorType: Int? = null
        var hasTransparencyChunk = false
        while (offset <= bytes.size - PNG_CHUNK_HEADER_BYTES) {
            val length = bytes.readBigEndianInt(offset)
            if (length < 0) return false
            val typeOffset = offset + INT_BYTES
            val dataOffset = typeOffset + PNG_CHUNK_TYPE_BYTES
            val nextOffset = dataOffset.toLong() + length.toLong() + PNG_CHUNK_CRC_BYTES
            if (nextOffset > bytes.size.toLong()) return false
            val chunkType = bytes.copyOfRange(typeOffset, dataOffset).decodeToString()
            when (chunkType) {
                "IHDR" -> {
                    if (length != PNG_IHDR_BYTES) return false
                    colorType = bytes[dataOffset + PNG_COLOR_TYPE_OFFSET].toInt() and 0xff
                }

                "tRNS" -> hasTransparencyChunk = true
                "IEND" -> break
            }
            offset = nextOffset.toInt()
        }
        return colorType != null && colorType in OPAQUE_PNG_COLOR_TYPES && !hasTransparencyChunk
    }
}

private fun sourceTopDirection(xRotation: Int): AssetDirection = when (xRotation) {
    0 -> AssetDirection.UP
    90 -> AssetDirection.NORTH
    180 -> AssetDirection.DOWN
    270 -> AssetDirection.SOUTH
    else -> error("Unsupported model X rotation: $xRotation")
}

private fun projectedHeight(assetModelElement: AssetModelElement, assetDirection: AssetDirection): Float =
    when (assetDirection) {
        AssetDirection.UP -> assetModelElement.to.y
        AssetDirection.DOWN -> MODEL_SIDE - assetModelElement.from.y
        AssetDirection.NORTH -> MODEL_SIDE - assetModelElement.from.z
        AssetDirection.SOUTH -> assetModelElement.to.z
        AssetDirection.WEST -> MODEL_SIDE - assetModelElement.from.x
        AssetDirection.EAST -> assetModelElement.to.x
    }

private fun projectedRawRectangle(assetModelElement: AssetModelElement, assetDirection: AssetDirection): RawRectangle =
    when (assetDirection) {
        AssetDirection.UP, AssetDirection.DOWN -> RawRectangle(
            assetModelElement.from.x,
            assetModelElement.from.z,
            assetModelElement.to.x,
            assetModelElement.to.z,
        )

        AssetDirection.NORTH, AssetDirection.SOUTH -> RawRectangle(
            assetModelElement.from.x,
            MODEL_SIDE - assetModelElement.to.y,
            assetModelElement.to.x,
            MODEL_SIDE - assetModelElement.from.y,
        )

        AssetDirection.WEST, AssetDirection.EAST -> RawRectangle(
            assetModelElement.from.z,
            MODEL_SIDE - assetModelElement.to.y,
            assetModelElement.to.z,
            MODEL_SIDE - assetModelElement.from.y,
        )
    }

private fun List<Float>.toSurfaceTextureUv(): SurfaceTextureUv {
    return RawRectangle(this[0], this[1], this[2], this[3]).textureUv
}

private data class RawRectangle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val normalized: SurfaceSpriteRectangle
        get() = SurfaceSpriteRectangle(
            left = minOf(left, right),
            top = minOf(top, bottom),
            right = maxOf(left, right),
            bottom = maxOf(top, bottom),
        )

    val textureUv: SurfaceTextureUv
        get() = SurfaceTextureUv(
            rectangle = normalized,
            flipX = right < left,
            flipY = bottom < top,
        )
}

private data class SurfaceTextureUv(
    val rectangle: SurfaceSpriteRectangle,
    val flipX: Boolean,
    val flipY: Boolean,
)

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

private fun String.assetIdentifier(directory: String, suffix: String): Identifier? {
    val segments = split('/')
    if (
        segments.size < 4 ||
        segments[0] != "assets" ||
        segments[2] != directory ||
        !endsWith(suffix)
    ) {
        return null
    }
    val path = segments.drop(3).joinToString("/").removeSuffix(suffix)
    return Identifier(segments[1], path)
}

private fun ByteArray.readBigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

private val ASSET_JSON: Json = Json { ignoreUnknownKeys = true }
private val PNG_SIGNATURE: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
private val OPAQUE_PNG_COLOR_TYPES: Set<Int> = setOf(0, 2, 3)
private const val INT_BYTES: Int = 4
private const val PNG_CHUNK_TYPE_BYTES: Int = 4
private const val PNG_CHUNK_CRC_BYTES: Int = 4
private const val PNG_CHUNK_HEADER_BYTES: Int = INT_BYTES + PNG_CHUNK_TYPE_BYTES
private const val PNG_IHDR_BYTES: Int = 13
private const val PNG_COLOR_TYPE_OFFSET: Int = 9
private const val MODEL_SIDE: Float = 16f
private const val SPRITE_SIDE: Int = 16
private val FULL_SPRITE_RECTANGLE: SurfaceSpriteRectangle = SurfaceSpriteRectangle(0f, 0f, MODEL_SIDE, MODEL_SIDE)
