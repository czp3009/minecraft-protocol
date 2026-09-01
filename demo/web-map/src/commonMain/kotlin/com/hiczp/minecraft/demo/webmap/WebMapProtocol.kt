package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.CHUNK_SIDE
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.ChunkRange
import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val MAX_VIEWPORT_CHUNK_WIDTH: Long = 256
const val MAX_VIEWPORT_CHUNK_HEIGHT: Long = 256
const val MAX_VIEWPORT_CHUNK_COUNT: Long = 32_768

val WebMapJson: Json = Json {
    classDiscriminator = "status"
    encodeDefaults = true
    explicitNulls = false
}

@Rpc
interface WebMapService {
    suspend fun worldMetadata(): WorldMetadata

    suspend fun querySurface(surfaceRequest: SurfaceRequest): SurfaceQueryResult
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
sealed interface SurfaceQueryResult {
    @Serializable
    @SerialName("success")
    data class Success(val response: SurfaceResponse) : SurfaceQueryResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(val rejection: SurfaceQueryRejection) : SurfaceQueryResult
}

@Serializable
enum class SurfaceQueryRejection {
    @SerialName("unknown_dimension")
    UNKNOWN_DIMENSION,

    @SerialName("range_too_large")
    RANGE_TOO_LARGE,
}

@Serializable
data class SurfaceResponse(
    val minChunkX: Int,
    val minChunkZ: Int,
    val maxChunkX: Int,
    val maxChunkZ: Int,
    val chunks: List<SurfaceChunkResult>,
) {
    val chunkViewport: ChunkViewport
        get() = ChunkViewport(minChunkX, minChunkZ, maxChunkX, maxChunkZ)

    init {
        require(chunkViewport == chunkViewport.normalized) { "A surface response viewport must be normalized" }
        require(chunks.map(SurfaceChunkResult::coordinate).distinct().size == chunks.size) {
            "A surface response contains duplicate Chunk coordinates"
        }
        require(chunks.all { surfaceChunkResult -> surfaceChunkResult.coordinate in chunkViewport }) {
            "A surface response contains a Chunk outside its viewport"
        }
    }
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
data class ChunkSurface(
    val palette: List<SurfaceBlockState>,
    val cells: List<Int?>,
) {
    init {
        require(cells.size == SURFACE_CELL_COUNT) { "A Chunk surface must contain $SURFACE_CELL_COUNT cells" }
        require(cells.all { paletteIndex -> paletteIndex == null || paletteIndex in palette.indices }) {
            "A Chunk surface contains an invalid palette index"
        }
    }

    operator fun get(localX: Int, localZ: Int): SurfaceBlockState? {
        require(localX in 0 until CHUNK_SIDE && localZ in 0 until CHUNK_SIDE) {
            "Surface coordinates must be inside one Chunk"
        }
        return cells[localZ * CHUNK_SIDE + localX]?.let(palette::get)
    }
}

const val SURFACE_CELL_COUNT: Int = CHUNK_SIDE * CHUNK_SIDE
