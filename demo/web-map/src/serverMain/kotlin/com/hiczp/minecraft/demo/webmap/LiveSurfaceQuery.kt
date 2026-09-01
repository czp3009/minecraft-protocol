package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.protocol.datapack.MinecraftChunkContext
import com.hiczp.minecraft.protocol.model.type.ProtocolBlockState
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryEntry
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.io.LiveMinecraftWorldAccess
import kotlinx.coroutines.*
import okio.IOException

internal sealed interface ChunkReadOutcome {
    data object Missing : ChunkReadOutcome

    data class Present(
        val chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>,
    ) : ChunkReadOutcome

    data class Failed(val failure: Throwable) : ChunkReadOutcome
}

internal sealed interface RegionReadOutcome {
    data class Success(
        val chunks: Map<ChunkPosition, ChunkReadOutcome>,
    ) : RegionReadOutcome

    data class Failed(val failure: Throwable) : RegionReadOutcome
}

internal fun interface SurfaceRegionReader {
    suspend fun readRegion(
        dimensionId: DimensionId,
        regionPosition: RegionPosition,
        chunkPositions: List<ChunkPosition>,
    ): RegionReadOutcome
}

internal fun interface SurfaceChunkProjector {
    suspend fun project(chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>): ChunkSurface
}

internal class ProtocolSurfaceChunkProjector(
    private val minecraftChunkContext: MinecraftChunkContext,
) : SurfaceChunkProjector {
    override suspend fun project(chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>): ChunkSurface =
        SurfaceProjectionPolicy.project(
            dimensionId = minecraftChunkContext.dimensionId,
            chunk = chunk,
            dimensionTypeLayout = minecraftChunkContext.dimensionTypeLayout,
            blockStateRegistry = minecraftChunkContext.chunkCodecContext.chunkDataRegistries.blockStates,
        )
}

internal class LiveSurfaceRegionReader(
    private val liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    private val minecraftChunkContexts: Map<DimensionId, MinecraftChunkContext>,
    private val readFailure: (RegionPosition, ChunkPosition?, Throwable) -> Unit,
) : SurfaceRegionReader {
    override suspend fun readRegion(
        dimensionId: DimensionId,
        regionPosition: RegionPosition,
        chunkPositions: List<ChunkPosition>,
    ): RegionReadOutcome {
        if (chunkPositions.isEmpty()) return RegionReadOutcome.Success(emptyMap())
        return try {
            val minecraftChunkContext = checkNotNull(minecraftChunkContexts[dimensionId]) {
                "No Minecraft Chunk context for dimension $dimensionId"
            }
            val workerCount = minOf(REGION_READ_WORKER_COUNT, chunkPositions.size)
            val chunkBatchSize = (chunkPositions.size + workerCount - 1) / workerCount
            val chunks = coroutineScope {
                chunkPositions.chunked(chunkBatchSize).map { chunkBatch ->
                    async {
                        val coroutineContext = currentCoroutineContext()
                        liveMinecraftWorldAccess.dimensions[minecraftChunkContext.dimensionId]
                            .openRegion(regionPosition)
                            .use { liveRegionHandle ->
                                liveRegionHandle.withReadScope(minecraftChunkContext.chunkNbtCodec) {
                                    buildMap {
                                        chunkBatch.forEach { chunkPosition ->
                                            coroutineContext.ensureActive()
                                            val chunkReadOutcome = try {
                                                readChunk(chunkPosition)?.let(ChunkReadOutcome::Present)
                                                    ?: ChunkReadOutcome.Missing
                                            } catch (failure: Throwable) {
                                                failure.requireExpectedLiveReadFailure()
                                                readFailure(regionPosition, chunkPosition, failure)
                                                ChunkReadOutcome.Failed(failure)
                                            }
                                            put(chunkPosition, chunkReadOutcome)
                                        }
                                    }
                                }
                            }
                    }
                }.awaitAll().flatMap { chunkOutcomes -> chunkOutcomes.entries }.associate { entry -> entry.toPair() }
            }
            RegionReadOutcome.Success(chunks)
        } catch (failure: Throwable) {
            failure.requireExpectedLiveReadFailure()
            readFailure(regionPosition, null, failure)
            RegionReadOutcome.Failed(failure)
        }
    }
}

internal class SurfaceQueryEngine(
    private val surfaceChunkProjectors: Map<DimensionId, SurfaceChunkProjector>,
    private val surfaceRegionReader: SurfaceRegionReader,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun query(surfaceRequest: SurfaceRequest): SurfaceQueryResult = withContext(coroutineDispatcher) {
        val surfaceChunkProjector = surfaceChunkProjectors[surfaceRequest.dimensionId]
            ?: return@withContext SurfaceQueryResult.Rejected(SurfaceQueryRejection.UNKNOWN_DIMENSION)
        val chunkViewport = surfaceRequest.chunkViewport.normalized
        if (!chunkViewport.isWithinQueryLimits) {
            return@withContext SurfaceQueryResult.Rejected(SurfaceQueryRejection.RANGE_TOO_LARGE)
        }
        val chunkRange = chunkViewport.chunkRange
        val surfaceChunkResults = coroutineScope {
            val regionJobs = chunkRange.regionPositions().map { regionPosition ->
                async {
                    val targetChunkPositions = (chunkRange intersect regionPosition.chunkRange).toList()
                    val regionReadOutcome = surfaceRegionReader.readRegion(
                        surfaceRequest.dimensionId,
                        regionPosition,
                        targetChunkPositions,
                    )
                    projectRegion(surfaceChunkProjector, targetChunkPositions, regionReadOutcome)
                }
            }.toList()
            regionJobs.awaitAll().flatten()
        }
        SurfaceQueryResult.Success(
            SurfaceResponse(
                minChunkX = chunkViewport.minChunkX,
                minChunkZ = chunkViewport.minChunkZ,
                maxChunkX = chunkViewport.maxChunkX,
                maxChunkZ = chunkViewport.maxChunkZ,
                chunks = surfaceChunkResults,
            ),
        )
    }

    private suspend fun projectRegion(
        surfaceChunkProjector: SurfaceChunkProjector,
        targetChunkPositions: List<ChunkPosition>,
        regionReadOutcome: RegionReadOutcome,
    ): List<SurfaceChunkResult> = when (regionReadOutcome) {
        is RegionReadOutcome.Failed -> targetChunkPositions.map { chunkPosition ->
            SurfaceChunkResult.ReadFailed(chunkPosition.x, chunkPosition.z)
        }

        is RegionReadOutcome.Success -> coroutineScope {
            val workerCount = minOf(PROJECTION_WORKER_COUNT, targetChunkPositions.size)
            (0 until workerCount).map { workerIndex ->
                async {
                    val indexedResults = mutableListOf<IndexedValue<SurfaceChunkResult>>()
                    var chunkIndex = workerIndex
                    while (chunkIndex < targetChunkPositions.size) {
                        currentCoroutineContext().ensureActive()
                        val chunkPosition = targetChunkPositions[chunkIndex]
                        projectChunk(
                            surfaceChunkProjector,
                            chunkPosition,
                            regionReadOutcome.chunks.getValue(chunkPosition)
                        )
                            ?.let { surfaceChunkResult ->
                                indexedResults += IndexedValue(
                                    chunkIndex,
                                    surfaceChunkResult
                                )
                            }
                        chunkIndex += workerCount
                    }
                    indexedResults
                }
            }.awaitAll().flatten().sortedBy { indexedValue -> indexedValue.index }
                .map { indexedValue -> indexedValue.value }
        }
    }

    private suspend fun projectChunk(
        surfaceChunkProjector: SurfaceChunkProjector,
        chunkPosition: ChunkPosition,
        chunkReadOutcome: ChunkReadOutcome,
    ): SurfaceChunkResult? = when (chunkReadOutcome) {
        is ChunkReadOutcome.Missing -> null
        is ChunkReadOutcome.Failed -> SurfaceChunkResult.ReadFailed(chunkPosition.x, chunkPosition.z)
        is ChunkReadOutcome.Present -> {
            val chunk = chunkReadOutcome.chunk
            if (chunk.chunkMetadata.chunkStorageMetadata?.isFullyGenerated != true) {
                SurfaceChunkResult.ReadFailed(chunkPosition.x, chunkPosition.z)
            } else {
                SurfaceChunkResult.Success(
                    chunkX = chunkPosition.x,
                    chunkZ = chunkPosition.z,
                    surface = surfaceChunkProjector.project(chunk),
                )
            }
        }
    }
}

private fun Throwable.requireExpectedLiveReadFailure() {
    if (this is CancellationException) throw this
    if (
        this !is IOException &&
        this !is AnvilFormatException &&
        this !is CompressionFormatException &&
        this !is NbtDecodingException &&
        this !is ChunkNbtFormatException
    ) {
        throw this
    }
}

private const val PROJECTION_WORKER_COUNT: Int = 8
private const val REGION_READ_WORKER_COUNT: Int = 4
