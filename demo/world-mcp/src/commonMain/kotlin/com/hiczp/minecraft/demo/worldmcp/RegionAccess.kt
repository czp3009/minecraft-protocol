package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.MinecraftWorldFormat
import com.hiczp.minecraft.world.format.RegionPosition
import com.hiczp.minecraft.world.io.RegionChunkInfo
import com.hiczp.minecraft.world.io.RegionReplacementScope

/** Common raw operations over the three independently owned Region handle families. The enclosing use owns closure. */
class RegionAccess(
    val positions: suspend () -> List<ChunkPosition>,
    val count: suspend () -> Int,
    val hasChunk: suspend (ChunkPosition) -> Boolean,
    val info: suspend (ChunkPosition) -> RegionChunkInfo?,
    private val readDocument: suspend (ChunkPosition) -> NbtDocument?,
    private val writeDocument: suspend (ChunkPosition, NbtDocument) -> Unit,
    val replace: suspend (RegionReplacementScope.() -> Unit) -> Unit,
    val inspectTerrain: suspend (ChunkPosition) -> TerrainInspection?,
    val inspectPoi: suspend (ChunkPosition) -> PoiInspection?,
) {
    suspend fun write(chunkPosition: ChunkPosition, nbtDocument: NbtDocument) {
        writeDocument(chunkPosition, nbtDocument.withCurrentDataVersion())
    }

    suspend fun read(chunkPosition: ChunkPosition): NbtDocument? {
        val document = readDocument(chunkPosition)
        check(document != null || !hasChunk(chunkPosition)) { "Chunk $chunkPosition is indexed but its payload is unavailable" }
        return document
    }
}

/** Like vanilla SerializableChunkData, EntityStorage and SectionStorage, stamp each saved record with the running build's version. */
internal fun NbtDocument.withCurrentDataVersion(): NbtDocument =
    copy(root = root.withFields("DataVersion" to NbtInt(MinecraftWorldFormat.WORLD_VERSION)))

internal suspend fun WorldSession.regionPositions(
    dimensionId: DimensionId,
    regionKind: RegionKind
): List<RegionPosition> {
    context(dimensionId)
    val dimension = minecraftWorldAccess.dimensions[dimensionId]
    return when (regionKind) {
        RegionKind.TERRAIN -> dimension.listRegionPositions()
        RegionKind.ENTITIES -> dimension.listEntityRegionPositions()
        RegionKind.POI -> dimension.listPoiRegionPositions()
    }
}

internal suspend fun <T> WorldSession.withRegion(
    dimensionId: DimensionId,
    regionKind: RegionKind,
    regionPosition: RegionPosition,
    block: suspend (RegionAccess) -> T,
): T {
    context(dimensionId)
    val dimension = minecraftWorldAccess.dimensions[dimensionId]
    return when (regionKind) {
        RegionKind.TERRAIN -> dimension.openRegion(regionPosition).use { regionHandle ->
            block(
                RegionAccess(
                    regionHandle::readChunkPositions,
                    regionHandle::readChunkCount,
                    regionHandle::hasChunk,
                    regionHandle::readChunkInfo,
                    regionHandle::readChunkNbtDocument,
                    { chunkPosition, nbtDocument -> regionHandle.writeChunkNbtDocument(chunkPosition, nbtDocument) },
                    regionHandle::replaceRegion,
                    { chunkPosition ->
                        regionHandle.withChunkNbtSource(chunkPosition) { _, source ->
                            ChunkInspection.terrain(
                                source
                            )
                        }
                    },
                    { error("POI inspection requires kind=poi") },
                )
            )
        }

        RegionKind.ENTITIES -> dimension.openEntityRegion(regionPosition).use { regionHandle ->
            block(
                RegionAccess(
                    regionHandle::readChunkPositions,
                    regionHandle::readChunkCount,
                    regionHandle::hasChunk,
                    regionHandle::readChunkInfo,
                    regionHandle::readChunkNbtDocument,
                    { chunkPosition, nbtDocument -> regionHandle.writeChunkNbtDocument(chunkPosition, nbtDocument) },
                    regionHandle::replaceRegion,
                    { error("Terrain inspection requires kind=terrain") },
                    { error("POI inspection requires kind=poi") },
                )
            )
        }

        RegionKind.POI -> dimension.openPoiRegion(regionPosition).use { regionHandle ->
            block(
                RegionAccess(
                    regionHandle::readChunkPositions,
                    regionHandle::readChunkCount,
                    regionHandle::hasChunk,
                    regionHandle::readChunkInfo,
                    regionHandle::readChunkNbtDocument,
                    { chunkPosition, nbtDocument -> regionHandle.writeChunkNbtDocument(chunkPosition, nbtDocument) },
                    regionHandle::replaceRegion,
                    { error("Terrain inspection requires kind=terrain") },
                    { chunkPosition ->
                        regionHandle.withChunkNbtSource(chunkPosition) { _, source ->
                            ChunkInspection.poi(
                                source
                            )
                        }
                    },
                )
            )
        }
    }
}
