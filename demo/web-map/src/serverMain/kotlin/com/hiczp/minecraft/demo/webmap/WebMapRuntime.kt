package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.datapack.MinecraftChunkContext
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
import okio.Path

internal data class WebMapRuntime(
    val liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    val minecraftChunkContexts: Map<DimensionId, MinecraftChunkContext>,
    val webMapService: WebMapService,
) {
    companion object {
        fun open(worldDirectory: Path, logger: KLogger): WebMapRuntime {
            val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(worldDirectory)
            val worldDataPackLoadResult = liveMinecraftWorldAccess.dataPacks.readEnabled()
            val worldGenSettingsData = checkNotNull(
                liveMinecraftWorldAccess.data.read<SavedDataFile<WorldGenSettingsData>>(WORLD_GEN_SETTINGS_ID),
            ) {
                "World has no root world_gen_settings saved data"
            }.data
            val resolvedProtocolData = worldDataPackLoadResult.toVanillaProtocolData()
            val minecraftChunkContexts = resolvedProtocolData.resolveMinecraftChunkContexts(worldGenSettingsData)
            val surfaceRegionReader = LiveSurfaceRegionReader(liveMinecraftWorldAccess, minecraftChunkContexts) {
                    regionPosition: RegionPosition,
                    chunkPosition,
                    failure,
                ->
                val location = chunkPosition?.let { value -> "Chunk $value in Region $regionPosition" }
                    ?: "Region $regionPosition"
                logger.warn(failure) { "$location could not be read from the live world" }
            }
            val surfaceChunkProjectors = minecraftChunkContexts.mapValues { (_, minecraftChunkContext) ->
                ProtocolSurfaceChunkProjector(minecraftChunkContext)
            }
            val surfaceQueryEngine = SurfaceQueryEngine(surfaceChunkProjectors, surfaceRegionReader)
            val worldMetadata = WorldMetadata(
                minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
                dimensionIds = worldGenSettingsData.dimensions.keys.sortedBy(DimensionId::toString),
            )
            return WebMapRuntime(
                liveMinecraftWorldAccess = liveMinecraftWorldAccess,
                minecraftChunkContexts = minecraftChunkContexts,
                webMapService = DefaultWebMapService(worldMetadata, surfaceQueryEngine),
            )
        }
    }
}

private class DefaultWebMapService(
    private val metadata: WorldMetadata,
    private val surfaceQueryEngine: SurfaceQueryEngine,
) : WebMapService {
    override suspend fun worldMetadata(): WorldMetadata = metadata

    override suspend fun querySurface(surfaceRequest: SurfaceRequest): SurfaceQueryResult =
        surfaceQueryEngine.query(surfaceRequest)
}

private val WORLD_GEN_SETTINGS_ID: SavedDataId = SavedDataId("world_gen_settings")
