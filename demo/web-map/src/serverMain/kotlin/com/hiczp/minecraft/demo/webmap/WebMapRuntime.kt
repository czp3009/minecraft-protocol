package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.datapack.resolveMinecraftChunkContexts
import com.hiczp.minecraft.protocol.datapack.vanilla.toVanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.RegionPosition
import com.hiczp.minecraft.world.format.SavedDataId
import com.hiczp.minecraft.world.format.data.SavedDataFile
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData
import com.hiczp.minecraft.world.io.LiveMinecraftWorldAccess
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okio.Path

class WebMapRuntime(
    val webMapService: WebMapService,
    private val coroutineScope: CoroutineScope,
    private val officialAssetRepository: OfficialAssetRepository,
) {
    fun close() {
        officialAssetRepository.close()
        coroutineScope.cancel()
    }

    companion object {
        fun open(worldDirectory: Path, logger: KLogger): WebMapRuntime {
            val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val officialAssetRepository = OfficialAssetRepository(
                minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
                parentCoroutineScope = coroutineScope,
                logger = logger,
            )
            val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldDirectory)
            val worldDataPackLoadResult = liveMinecraftWorldAccess.dataPacks.readEnabled()
            val worldGenSettingsData = checkNotNull(
                liveMinecraftWorldAccess.data.read<SavedDataFile<WorldGenSettingsData>>(WORLD_GEN_SETTINGS_ID),
            ) {
                "World has no root world_gen_settings saved data"
            }.data
            val resolvedProtocolData = worldDataPackLoadResult.toVanillaProtocolData()
            val minecraftChunkContexts = resolvedProtocolData.resolveMinecraftChunkContexts(worldGenSettingsData)
            val surfaceRegionReader = LiveSurfaceRegionReader(
                liveMinecraftWorldAccess = liveMinecraftWorldAccess,
                minecraftChunkContexts = minecraftChunkContexts,
                surfaceChunkCache = SurfaceChunkCache(),
            ) { regionPosition: RegionPosition, chunkPosition, failure ->
                val location = chunkPosition?.let { value -> "Chunk $value in Region $regionPosition" }
                    ?: "Region $regionPosition"
                logger.warn(failure) { "$location could not be read from the live world" }
            }
            val surfaceChunkProjectors = minecraftChunkContexts.mapValues { (_, minecraftChunkContext) ->
                ProtocolSurfaceChunkProjector(minecraftChunkContext)
            }
            val surfaceQueryEngine = SurfaceQueryEngine(
                surfaceChunkProjectors = surfaceChunkProjectors,
                surfaceRegionReader = surfaceRegionReader,
                loadBlockTransparency = { officialAssetRepository.awaitLoaded().blockAssets },
            )
            val worldMetadata = WorldMetadata(
                minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
                dimensionIds = worldGenSettingsData.dimensions.keys.sortedBy(DimensionId::toString),
            )
            return WebMapRuntime(
                webMapService = DefaultWebMapService(worldMetadata, surfaceQueryEngine, officialAssetRepository),
                coroutineScope = coroutineScope,
                officialAssetRepository = officialAssetRepository,
            )
        }
    }
}

private class DefaultWebMapService(
    private val metadata: WorldMetadata,
    private val surfaceQueryEngine: SurfaceQueryEngine,
    private val officialAssetRepository: OfficialAssetRepository,
) : WebMapService {
    override suspend fun worldMetadata(): WorldMetadata = metadata

    override fun assetLoading(): Flow<AssetLoadStatus> = officialAssetRepository.status

    override fun blockRenderResources(
        blockRenderResourceRequest: BlockRenderResourceRequest,
    ): Flow<BlockRenderResourceResult> = channelFlow {
        blockRenderResourceRequest.blockStates.map { surfaceBlockState ->
            async(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                send(
                    BlockRenderResourceResult(
                        blockState = surfaceBlockState,
                        resource = officialAssetRepository.blockRenderResource(
                            assetRevision = blockRenderResourceRequest.assetRevision,
                            surfaceBlockState = surfaceBlockState,
                        ),
                    ),
                )
            }
        }.awaitAll()
    }

    override suspend fun textureResource(textureResourceRequest: TextureResourceRequest): TextureResource? =
        officialAssetRepository.textureResource(
            assetRevision = textureResourceRequest.assetRevision,
            texture = textureResourceRequest.texture,
        )

    override fun querySurface(surfaceRequest: SurfaceRequest): Flow<SurfaceQueryUpdate> =
        surfaceQueryEngine.query(surfaceRequest)
}

private val WORLD_GEN_SETTINGS_ID: SavedDataId = SavedDataId("world_gen_settings")
