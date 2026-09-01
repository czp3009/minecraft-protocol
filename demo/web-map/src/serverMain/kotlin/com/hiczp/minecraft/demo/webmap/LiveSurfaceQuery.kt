package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.nbt.serialization.NbtDecodingException
import com.hiczp.minecraft.protocol.datapack.MinecraftChunkContext
import com.hiczp.minecraft.protocol.model.type.ProtocolBlockState
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryEntry
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.io.DecodedChunkRegionReadScope
import com.hiczp.minecraft.world.io.LiveMinecraftWorldAccess
import com.hiczp.minecraft.world.io.LiveRegionHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface SurfaceChunkReadOutcome {
    data object Missing : SurfaceChunkReadOutcome

    data class Present(
        val timestampEpochSeconds: Int,
        val surface: ChunkSurface,
    ) : SurfaceChunkReadOutcome

    data object Failed : SurfaceChunkReadOutcome
}

sealed interface SurfaceRegionReadOutcome {
    data class Success(
        val chunks: Map<ChunkPosition, SurfaceChunkReadOutcome>,
    ) : SurfaceRegionReadOutcome

    data object Failed : SurfaceRegionReadOutcome
}

fun interface SurfaceRegionReader {
    suspend fun readRegion(
        dimensionId: DimensionId,
        regionPosition: RegionPosition,
        chunkPositions: List<ChunkPosition>,
        surfaceChunkProjector: SurfaceChunkProjector,
        surfaceBlockTransparency: SurfaceBlockTransparency,
    ): SurfaceRegionReadOutcome
}

fun interface SurfaceChunkProjector {
    fun project(
        chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>,
        surfaceBlockTransparency: SurfaceBlockTransparency,
    ): ChunkSurface
}

class ProtocolSurfaceChunkProjector(
    private val minecraftChunkContext: MinecraftChunkContext,
) : SurfaceChunkProjector {
    override fun project(
        chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>,
        surfaceBlockTransparency: SurfaceBlockTransparency,
    ): ChunkSurface = SurfaceProjectionPolicy.project(
        chunk = chunk,
        blockYRange = minecraftChunkContext.dimensionTypeLayout.logicalBlockYRange,
        blockStateRegistry = minecraftChunkContext.chunkCodecContext.chunkDataRegistries.blockStates,
        surfaceBlockTransparency = surfaceBlockTransparency,
    )
}

class SurfaceChunkCache {
    private val regionsMutex = Mutex()
    private val regions = mutableMapOf<SurfaceRegionCacheKey, SurfaceRegionCache>()

    suspend fun slots(
        dimensionId: DimensionId,
        regionPosition: RegionPosition,
        chunkPositions: List<ChunkPosition>,
    ): List<SurfaceChunkCacheSlot> {
        val surfaceRegionCache = regionsMutex.withLock {
            regions.getOrPut(SurfaceRegionCacheKey(dimensionId, regionPosition), ::SurfaceRegionCache)
        }
        return surfaceRegionCache.slots(chunkPositions)
    }
}

private data class SurfaceRegionCacheKey(
    val dimensionId: DimensionId,
    val regionPosition: RegionPosition,
)

private class SurfaceRegionCache {
    private val slotsMutex = Mutex()
    private val slots = mutableMapOf<ChunkPosition, SurfaceChunkCacheSlot>()

    suspend fun slots(chunkPositions: List<ChunkPosition>): List<SurfaceChunkCacheSlot> = slotsMutex.withLock {
        chunkPositions.map { chunkPosition -> slots.getOrPut(chunkPosition, ::SurfaceChunkCacheSlot) }
    }
}

class SurfaceChunkCacheSlot {
    val mutex = Mutex()
    var entry: CachedSurfaceChunk? = null
}

data class CachedSurfaceChunk(
    val timestampEpochSeconds: Int,
    val surface: ChunkSurface,
)

class LiveSurfaceRegionReader(
    private val liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    private val minecraftChunkContexts: Map<DimensionId, MinecraftChunkContext>,
    private val surfaceChunkCache: SurfaceChunkCache,
    private val readFailure: (RegionPosition, ChunkPosition?, Throwable) -> Unit,
) : SurfaceRegionReader {
    override suspend fun readRegion(
        dimensionId: DimensionId,
        regionPosition: RegionPosition,
        chunkPositions: List<ChunkPosition>,
        surfaceChunkProjector: SurfaceChunkProjector,
        surfaceBlockTransparency: SurfaceBlockTransparency,
    ): SurfaceRegionReadOutcome {
        if (chunkPositions.isEmpty()) return SurfaceRegionReadOutcome.Success(emptyMap())
        return try {
            val minecraftChunkContext = checkNotNull(minecraftChunkContexts[dimensionId]) {
                "No Minecraft Chunk context for dimension $dimensionId"
            }
            val chunkOutcomes = withContext(Dispatchers.Default) {
                readChunks(
                    minecraftChunkContext = minecraftChunkContext,
                    regionPosition = regionPosition,
                    chunkPositions = chunkPositions,
                    surfaceChunkProjector = surfaceChunkProjector,
                    surfaceBlockTransparency = surfaceBlockTransparency,
                )
            }
            SurfaceRegionReadOutcome.Success(chunkOutcomes)
        } catch (failure: Throwable) {
            failure.requireExpectedLiveReadFailure()
            readFailure(regionPosition, null, failure)
            SurfaceRegionReadOutcome.Failed
        }
    }

    private suspend fun readChunks(
        minecraftChunkContext: MinecraftChunkContext,
        regionPosition: RegionPosition,
        chunkPositions: List<ChunkPosition>,
        surfaceChunkProjector: SurfaceChunkProjector,
        surfaceBlockTransparency: SurfaceBlockTransparency,
    ): Map<ChunkPosition, SurfaceChunkReadOutcome> {
        val slots = surfaceChunkCache.slots(minecraftChunkContext.dimensionId, regionPosition, chunkPositions)
        val chunkSlots = chunkPositions.zip(slots)
        val workerCount = minOf(REGION_READ_WORKER_COUNT, chunkSlots.size)
        val liveRegionHandle = liveMinecraftWorldAccess.dimensions[minecraftChunkContext.dimensionId]
            .openRegion(regionPosition)
        return liveRegionHandle.useSuspending {
            coroutineScope {
                (0 until workerCount).map { workerIndex ->
                    async {
                        val coroutineContext = currentCoroutineContext()
                        buildMap {
                            var chunkIndex = workerIndex
                            while (chunkIndex < chunkSlots.size) {
                                coroutineContext.ensureActive()
                                val (chunkPosition, surfaceChunkCacheSlot) = chunkSlots[chunkIndex]
                                val surfaceChunkReadOutcome = surfaceChunkCacheSlot.mutex.withLock {
                                    coroutineContext.ensureActive()
                                    liveRegionHandle.withReadScope(minecraftChunkContext.chunkNbtCodec) {
                                        readSurfaceChunk(
                                            chunkPosition = chunkPosition,
                                            surfaceChunkCacheSlot = surfaceChunkCacheSlot,
                                            surfaceChunkProjector = surfaceChunkProjector,
                                            surfaceBlockTransparency = surfaceBlockTransparency,
                                            coroutineContext = coroutineContext,
                                        )
                                    }
                                }
                                put(chunkPosition, surfaceChunkReadOutcome)
                                chunkIndex += workerCount
                            }
                        }
                    }
                }.awaitAll().flatMap { chunkOutcomes -> chunkOutcomes.entries }
                    .associate { entry -> entry.toPair() }
            }
        }
    }

    private suspend fun <R> LiveRegionHandle.useSuspending(block: suspend (LiveRegionHandle) -> R): R {
        var primaryFailure: Throwable? = null
        try {
            return block(this)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                close()
            } catch (closeFailure: Throwable) {
                if (primaryFailure == null) {
                    throw closeFailure
                } else {
                    primaryFailure.addSuppressed(closeFailure)
                }
            }
        }
    }

    private fun DecodedChunkRegionReadScope<ProtocolBlockState, ProtocolRegistryEntry>.readSurfaceChunk(
        chunkPosition: ChunkPosition,
        surfaceChunkCacheSlot: SurfaceChunkCacheSlot,
        surfaceChunkProjector: SurfaceChunkProjector,
        surfaceBlockTransparency: SurfaceBlockTransparency,
        coroutineContext: CoroutineContext,
    ): SurfaceChunkReadOutcome {
        val regionChunkInfo = readChunkInfo(chunkPosition)
        if (regionChunkInfo == null) {
            surfaceChunkCacheSlot.entry = null
            return SurfaceChunkReadOutcome.Missing
        }
        surfaceChunkCacheSlot.entry?.takeIf { cachedSurfaceChunk ->
            cachedSurfaceChunk.timestampEpochSeconds == regionChunkInfo.timestampEpochSeconds
        }?.let { cachedSurfaceChunk ->
            return SurfaceChunkReadOutcome.Present(
                timestampEpochSeconds = cachedSurfaceChunk.timestampEpochSeconds,
                surface = cachedSurfaceChunk.surface,
            )
        }
        return try {
            val chunk = readChunk(chunkPosition) ?: return SurfaceChunkReadOutcome.Missing
            coroutineContext.ensureActive()
            if (chunk.chunkMetadata.chunkStorageMetadata?.isFullyGenerated != true) {
                SurfaceChunkReadOutcome.Missing
            } else {
                val surface = surfaceChunkProjector.project(chunk, surfaceBlockTransparency)
                coroutineContext.ensureActive()
                surfaceChunkCacheSlot.entry = CachedSurfaceChunk(regionChunkInfo.timestampEpochSeconds, surface)
                SurfaceChunkReadOutcome.Present(regionChunkInfo.timestampEpochSeconds, surface)
            }
        } catch (failure: Throwable) {
            failure.requireExpectedLiveReadFailure()
            readFailure(chunkPosition.regionPosition, chunkPosition, failure)
            SurfaceChunkReadOutcome.Failed
        }
    }
}

class SurfaceQueryEngine(
    private val surfaceChunkProjectors: Map<DimensionId, SurfaceChunkProjector>,
    private val surfaceRegionReader: SurfaceRegionReader,
    private val loadBlockTransparency: suspend () -> SurfaceBlockTransparency,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val readRetryDelay: Duration = 1.seconds,
) {
    fun query(surfaceRequest: SurfaceRequest): Flow<SurfaceQueryUpdate> = channelFlow {
        val surfaceChunkProjector = surfaceChunkProjectors[surfaceRequest.dimensionId]
        if (surfaceChunkProjector == null) {
            send(SurfaceQueryUpdate.Rejected(SurfaceQueryRejection.UNKNOWN_DIMENSION))
            return@channelFlow
        }
        val chunkViewport = surfaceRequest.chunkViewport.normalized
        if (!chunkViewport.isWithinQueryLimits) {
            send(SurfaceQueryUpdate.Rejected(SurfaceQueryRejection.RANGE_TOO_LARGE))
            return@channelFlow
        }
        val surfaceBlockTransparency = loadBlockTransparency()
        val chunkRange = chunkViewport.chunkRange
        chunkRange.regionPositions().map { regionPosition ->
            async(coroutineDispatcher) {
                var pendingChunkPositions = (chunkRange intersect regionPosition.chunkRange).toList()
                while (pendingChunkPositions.isNotEmpty()) {
                    val surfaceRegionReadOutcome = surfaceRegionReader.readRegion(
                        dimensionId = surfaceRequest.dimensionId,
                        regionPosition = regionPosition,
                        chunkPositions = pendingChunkPositions,
                        surfaceChunkProjector = surfaceChunkProjector,
                        surfaceBlockTransparency = surfaceBlockTransparency,
                    )
                    val surfaceChunkResults = surfaceRegionReadOutcome.toSurfaceChunkResults(pendingChunkPositions)
                    surfaceChunkResults.forEach { surfaceChunkResult ->
                        send(SurfaceQueryUpdate.Chunk(surfaceChunkResult))
                    }
                    pendingChunkPositions = surfaceChunkResults.mapNotNull { surfaceChunkResult ->
                        if (surfaceChunkResult is SurfaceChunkResult.ReadFailed) {
                            surfaceChunkResult.coordinate.chunkPosition
                        } else {
                            null
                        }
                    }
                    if (pendingChunkPositions.isNotEmpty()) delay(readRetryDelay)
                }
            }
        }.toList().awaitAll()
    }
}

private fun SurfaceRegionReadOutcome.toSurfaceChunkResults(
    targetChunkPositions: List<ChunkPosition>,
): List<SurfaceChunkResult> = when (this) {
    SurfaceRegionReadOutcome.Failed -> targetChunkPositions.map { chunkPosition ->
        SurfaceChunkResult.ReadFailed(chunkPosition.x, chunkPosition.z)
    }

    is SurfaceRegionReadOutcome.Success -> targetChunkPositions.mapNotNull { chunkPosition ->
        when (val surfaceChunkReadOutcome = chunks.getValue(chunkPosition)) {
            SurfaceChunkReadOutcome.Missing -> null
            SurfaceChunkReadOutcome.Failed -> SurfaceChunkResult.ReadFailed(chunkPosition.x, chunkPosition.z)
            is SurfaceChunkReadOutcome.Present -> SurfaceChunkResult.Success(
                chunkX = chunkPosition.x,
                chunkZ = chunkPosition.z,
                timestampEpochSeconds = surfaceChunkReadOutcome.timestampEpochSeconds,
                surface = surfaceChunkReadOutcome.surface,
            )
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

private const val REGION_READ_WORKER_COUNT: Int = 4
