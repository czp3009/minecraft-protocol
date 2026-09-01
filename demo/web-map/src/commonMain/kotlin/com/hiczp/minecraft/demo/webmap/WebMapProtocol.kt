package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.CHUNK_SIDE
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.ChunkRange
import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

const val MAX_VIEWPORT_CHUNK_WIDTH: Long = 256
const val MAX_VIEWPORT_CHUNK_HEIGHT: Long = 256
const val MAX_VIEWPORT_CHUNK_COUNT: Long = 32_768
const val MAX_BLOCK_RENDER_RESOURCE_BATCH_SIZE: Int = 1_024

val WebMapJson: Json = Json {
    classDiscriminator = "status"
    encodeDefaults = true
    explicitNulls = false
}

@Rpc
interface WebMapService {
    suspend fun worldMetadata(): WorldMetadata

    fun assetLoading(): Flow<AssetLoadStatus>

    fun blockRenderResources(blockRenderResourceRequest: BlockRenderResourceRequest): Flow<BlockRenderResourceResult>

    suspend fun textureResource(textureResourceRequest: TextureResourceRequest): TextureResource?

    fun querySurface(surfaceRequest: SurfaceRequest): Flow<SurfaceQueryUpdate>
}

@Serializable
data class WorldMetadata(
    val minecraftVersion: String,
    val dimensionIds: List<DimensionId>,
) {
    init {
        require(minecraftVersion.isNotBlank()) { "Minecraft version must not be blank" }
        require(dimensionIds.isNotEmpty()) { "World metadata must contain at least one dimension" }
        require(dimensionIds.distinct().size == dimensionIds.size) { "World metadata contains duplicate dimensions" }
    }
}

@Serializable
sealed interface AssetLoadStatus {
    @Serializable
    @SerialName("loading")
    data class Loading(
        val action: String,
        val detail: String,
        val completedSteps: Int,
        val totalSteps: Int,
        val loadedFiles: Int? = null,
        val totalFiles: Int? = null,
        val loadedBytes: Long? = null,
        val totalBytes: Long? = null,
    ) : AssetLoadStatus {
        init {
            require(action.isNotBlank()) { "Asset-loading action must not be blank" }
            require(detail.isNotBlank()) { "Asset-loading detail must not be blank" }
            require(totalSteps > 0 && completedSteps in 0..totalSteps) { "Asset-loading steps are invalid" }
            require(loadedFiles == null || loadedFiles >= 0) { "Loaded asset-file count must not be negative" }
            require(totalFiles == null || totalFiles >= 0) { "Total asset-file count must not be negative" }
            require(loadedFiles == null || totalFiles == null || loadedFiles <= totalFiles) {
                "Loaded asset-file count must not exceed the total"
            }
            require(loadedBytes == null || loadedBytes >= 0L) { "Loaded asset bytes must not be negative" }
            require(totalBytes == null || totalBytes >= 0L) { "Total asset bytes must not be negative" }
            require(loadedBytes == null || totalBytes == null || loadedBytes <= totalBytes) {
                "Loaded asset bytes must not exceed the total"
            }
        }
    }

    @Serializable
    @SerialName("ready")
    data class Ready(
        val assetRevision: String,
        val fileCount: Int,
        val byteCount: Long,
    ) : AssetLoadStatus {
        init {
            require(assetRevision.isNotBlank()) { "Asset revision must not be blank" }
            require(fileCount > 0) { "Ready assets must contain at least one file" }
            require(byteCount > 0L) { "Ready assets must contain bytes" }
        }
    }

    @Serializable
    @SerialName("failed")
    data class Failed(
        val message: String,
        val retryDelayMillis: Long,
    ) : AssetLoadStatus {
        init {
            require(message.isNotBlank()) { "Asset-loading failure must not be blank" }
            require(retryDelayMillis > 0L) { "Asset-loading retry delay must be positive" }
        }
    }
}

@Serializable
data class BlockRenderResourceRequest(
    val assetRevision: String,
    val blockStates: List<SurfaceBlockState>,
) {
    init {
        requireAssetRevision(assetRevision)
        require(blockStates.isNotEmpty() && blockStates.size <= MAX_BLOCK_RENDER_RESOURCE_BATCH_SIZE) {
            "Block-resource batch size is outside the supported range"
        }
        require(blockStates.distinct().size == blockStates.size) { "A block-resource batch contains duplicates" }
    }
}

@Serializable
data class BlockRenderResourceResult(
    val blockState: SurfaceBlockState,
    val resource: BlockRenderResource?,
)

@Serializable
data class BlockRenderResource(
    val modelChoices: List<SurfaceModelChoice>,
) {
    init {
        require(modelChoices.isNotEmpty()) { "A Block render resource must contain at least one model choice" }
    }

    fun sprite(surfaceBlockState: SurfaceBlockState, blockX: Int, blockZ: Int): SurfaceSprite? {
        val positionSeed = "${surfaceBlockState.canonicalAssetKey()}@$blockX,$blockZ"
        val selectedModels = modelChoices.mapIndexed { index, surfaceModelChoice ->
            surfaceModelChoice.models.selectWeighted("$positionSeed#choice-$index")
        }
        val topLayers = selectedModels.flatMap(SurfaceModelResource::topLayers)
        val layers = topLayers.ifEmpty { selectedModels.mapNotNull(SurfaceModelResource::particleLayer) }
        return layers.takeIf { surfaceSpriteLayers -> surfaceSpriteLayers.isNotEmpty() }?.let(::SurfaceSprite)
    }
}

@Serializable
data class SurfaceModelChoice(
    val models: List<SurfaceModelResource>,
) {
    init {
        require(models.isNotEmpty()) { "A surface-model choice must contain at least one model" }
        require(models.sumOf(SurfaceModelResource::weight) > 0) { "A surface-model choice must have positive weight" }
    }
}

@Serializable
data class SurfaceModelResource(
    val weight: Int,
    val topLayers: List<SurfaceSpriteLayer>,
    val particleLayer: SurfaceSpriteLayer?,
) {
    init {
        require(weight > 0) { "A surface-model weight must be positive" }
    }
}

@Serializable
data class TextureResourceRequest(
    val assetRevision: String,
    val texture: Identifier,
) {
    init {
        requireAssetRevision(assetRevision)
    }
}

@Serializable
data class TextureResource(
    @Serializable(with = Base64ByteArraySerializer::class)
    val png: ByteArray,
    val animationFrame: Int,
) {
    init {
        require(png.isNotEmpty()) { "A texture resource must contain PNG bytes" }
        require(animationFrame >= 0) { "A texture animation frame must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TextureResource && png.contentEquals(other.png) && animationFrame == other.animationFrame

    override fun hashCode(): Int = 31 * png.contentHashCode() + animationFrame
}

object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "com.hiczp.minecraft.demo.webmap.Base64ByteArray",
        kind = PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.encode(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}

@Serializable
data class SurfaceSprite(
    val layers: List<SurfaceSpriteLayer>,
) {
    init {
        require(layers.isNotEmpty()) { "A surface sprite must contain at least one layer" }
    }
}

@Serializable
data class SurfaceSpriteLayer(
    val texture: Identifier,
    val destination: SurfaceSpriteRectangle,
    val uv: SurfaceSpriteRectangle,
    val yRotation: Int,
    val textureRotation: Int,
    val flipTextureX: Boolean = false,
    val flipTextureY: Boolean = false,
    val tintColor: String? = null,
) {
    init {
        require(yRotation in ASSET_ROTATIONS && textureRotation in ASSET_ROTATIONS) {
            "Surface-sprite rotations must be multiples of 90 degrees"
        }
        require(tintColor == null || tintColor.isNotBlank()) { "Surface-sprite tint color must not be blank" }
    }
}

@Serializable
data class SurfaceSpriteRectangle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Surface-sprite rectangle coordinates must be finite"
        }
        require(left <= right && top <= bottom) { "Surface-sprite rectangle must not be inverted" }
    }
}

@Serializable
data class SurfaceRequest(
    val dimensionId: DimensionId,
    val chunkViewport: ChunkViewport,
)

@Serializable
data class ChunkViewport(
    val minChunkX: Int,
    val minChunkZ: Int,
    val maxChunkX: Int,
    val maxChunkZ: Int,
) {
    val chunkRange: ChunkRange
        get() = ChunkRange.enclosing(
            ChunkPosition(minChunkX, minChunkZ),
            ChunkPosition(maxChunkX, maxChunkZ),
        )

    val normalized: ChunkViewport
        get() = chunkRange.toChunkViewport()

    val width: Long
        get() = normalized.maxChunkX.toLong() - normalized.minChunkX.toLong() + 1L

    val height: Long
        get() = normalized.maxChunkZ.toLong() - normalized.minChunkZ.toLong() + 1L

    val chunkCount: Long
        get() = width * height

    val isWithinQueryLimits: Boolean
        get() = width <= MAX_VIEWPORT_CHUNK_WIDTH &&
                height <= MAX_VIEWPORT_CHUNK_HEIGHT &&
                chunkCount <= MAX_VIEWPORT_CHUNK_COUNT

    operator fun contains(chunkCoordinate: ChunkCoordinate): Boolean =
        chunkCoordinate.chunkX in chunkRange.xRange && chunkCoordinate.chunkZ in chunkRange.zRange

    companion object {
        fun single(chunkCoordinate: ChunkCoordinate): ChunkViewport = ChunkViewport(
            minChunkX = chunkCoordinate.chunkX,
            minChunkZ = chunkCoordinate.chunkZ,
            maxChunkX = chunkCoordinate.chunkX,
            maxChunkZ = chunkCoordinate.chunkZ,
        )
    }
}

fun ChunkRange.toChunkViewport(): ChunkViewport {
    require(!isEmpty()) { "A web-map viewport cannot be empty" }
    return ChunkViewport(
        minChunkX = xRange.first,
        minChunkZ = zRange.first,
        maxChunkX = xRange.last,
        maxChunkZ = zRange.last,
    )
}

@Serializable
data class ChunkCoordinate(
    val chunkX: Int,
    val chunkZ: Int,
) : Comparable<ChunkCoordinate> {
    val chunkPosition: ChunkPosition
        get() = ChunkPosition(chunkX, chunkZ)

    override fun compareTo(other: ChunkCoordinate): Int =
        compareValuesBy(this, other, ChunkCoordinate::chunkZ, ChunkCoordinate::chunkX)

    companion object {
        fun from(chunkPosition: ChunkPosition): ChunkCoordinate = ChunkCoordinate(chunkPosition.x, chunkPosition.z)
    }
}

@Serializable
sealed interface SurfaceQueryUpdate {
    @Serializable
    @SerialName("chunk")
    data class Chunk(val result: SurfaceChunkResult) : SurfaceQueryUpdate

    @Serializable
    @SerialName("rejected")
    data class Rejected(val rejection: SurfaceQueryRejection) : SurfaceQueryUpdate
}

@Serializable
enum class SurfaceQueryRejection {
    @SerialName("unknown_dimension")
    UNKNOWN_DIMENSION,

    @SerialName("range_too_large")
    RANGE_TOO_LARGE,
}

@Serializable
sealed interface SurfaceChunkResult {
    val chunkX: Int
    val chunkZ: Int

    val coordinate: ChunkCoordinate
        get() = ChunkCoordinate(chunkX, chunkZ)

    @Serializable
    @SerialName("success")
    data class Success(
        override val chunkX: Int,
        override val chunkZ: Int,
        val timestampEpochSeconds: Int,
        val surface: ChunkSurface,
    ) : SurfaceChunkResult

    @Serializable
    @SerialName("read_failed")
    data class ReadFailed(
        override val chunkX: Int,
        override val chunkZ: Int,
    ) : SurfaceChunkResult
}

@Serializable
data class SurfaceBlockState(
    val name: Identifier,
    val properties: Map<String, String> = emptyMap(),
) {
    init {
        require(properties.all { (property, value) -> property.isNotBlank() && value.isNotBlank() }) {
            "Surface block-state property names and values must not be blank"
        }
    }
}

@Serializable
data class SurfaceColumn(
    /** Ordered from the highest visible Block downward to the first opaque Block, inclusive. */
    val blocks: List<SurfaceBlockState>,
) {
    init {
        require(blocks.isNotEmpty()) { "A non-empty surface column must contain at least one Block" }
    }
}

@Serializable
data class ChunkSurface(
    val palette: List<SurfaceColumn>,
    val cells: List<Int?>,
) {
    init {
        require(cells.size == SURFACE_CELL_COUNT) { "A Chunk surface must contain $SURFACE_CELL_COUNT cells" }
        require(cells.all { paletteIndex -> paletteIndex == null || paletteIndex in palette.indices }) {
            "A Chunk surface contains an invalid palette index"
        }
    }

    operator fun get(localX: Int, localZ: Int): SurfaceColumn? {
        require(localX in 0 until CHUNK_SIDE && localZ in 0 until CHUNK_SIDE) {
            "Surface coordinates must be inside one Chunk"
        }
        return cells[localZ * CHUNK_SIDE + localX]?.let(palette::get)
    }
}

const val SURFACE_CELL_COUNT: Int = CHUNK_SIDE * CHUNK_SIDE

private fun requireAssetRevision(assetRevision: String) {
    require(assetRevision.isNotBlank() && assetRevision.length <= MAXIMUM_ASSET_REVISION_LENGTH) {
        "Asset revision must have a supported length"
    }
    require(assetRevision.none { character ->
        character == '/' || character == '\\' || character.isISOControl()
    }) {
        "Asset revision contains an unsafe character"
    }
}

private val ASSET_ROTATIONS: Set<Int> = setOf(0, 90, 180, 270)
private const val MAXIMUM_ASSET_REVISION_LENGTH: Int = 128

private fun List<SurfaceModelResource>.selectWeighted(seed: String): SurfaceModelResource {
    val totalWeight = sumOf(SurfaceModelResource::weight)
    var selectedWeight = stableAssetHash(seed).mod(totalWeight)
    forEach { surfaceModelResource ->
        if (selectedWeight < surfaceModelResource.weight) return surfaceModelResource
        selectedWeight -= surfaceModelResource.weight
    }
    return last()
}
