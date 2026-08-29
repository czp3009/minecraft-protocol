package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.RegionPosition

/** Dimension selection under one mutable world lease. */
class MinecraftWorldDimensions internal constructor(
    private val minecraftWorldAccess: MinecraftWorldAccess,
) {
    val overworld: MinecraftWorldDimension = MinecraftWorldDimension(minecraftWorldAccess, DimensionId.Overworld)
    val nether: MinecraftWorldDimension = MinecraftWorldDimension(minecraftWorldAccess, DimensionId.Nether)
    val end: MinecraftWorldDimension = MinecraftWorldDimension(minecraftWorldAccess, DimensionId.End)

    operator fun get(dimensionId: DimensionId): MinecraftWorldDimension = when (dimensionId) {
        DimensionId.Overworld -> overworld
        DimensionId.Nether -> nether
        DimensionId.End -> end
        else -> MinecraftWorldDimension(minecraftWorldAccess, dimensionId)
    }
}

/** Mutable access bound to one selected-release dimension directory. */
class MinecraftWorldDimension internal constructor(
    private val minecraftWorldAccess: MinecraftWorldAccess,
    val dimensionId: DimensionId,
) {
    val data: MinecraftDimensionSavedData = MinecraftDimensionSavedData(minecraftWorldAccess, dimensionId)

    suspend fun listRegionPositions(): List<RegionPosition> =
        minecraftWorldAccess.listRegionPositions(RegionStorageDirectory.CHUNKS, dimensionId)

    suspend fun hasRegion(regionPosition: RegionPosition): Boolean =
        openRegion(regionPosition).use(RegionHandle::hasRegion)

    suspend fun openRegion(regionPosition: RegionPosition): RegionHandle =
        minecraftWorldAccess.openRegion(RegionStorageDirectory.CHUNKS, dimensionId, regionPosition)

    suspend fun listEntityRegionPositions(): List<RegionPosition> =
        minecraftWorldAccess.listRegionPositions(RegionStorageDirectory.ENTITIES, dimensionId)

    suspend fun hasEntityRegion(regionPosition: RegionPosition): Boolean =
        openEntityRegion(regionPosition).use(EntityRegionHandle::hasRegion)

    suspend fun openEntityRegion(regionPosition: RegionPosition): EntityRegionHandle = EntityRegionHandle(
        minecraftWorldAccess.openRegion(RegionStorageDirectory.ENTITIES, dimensionId, regionPosition),
    )

    suspend fun listPoiRegionPositions(): List<RegionPosition> =
        minecraftWorldAccess.listRegionPositions(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionId)

    suspend fun hasPoiRegion(regionPosition: RegionPosition): Boolean =
        openPoiRegion(regionPosition).use(PoiRegionHandle::hasRegion)

    suspend fun openPoiRegion(regionPosition: RegionPosition): PoiRegionHandle = PoiRegionHandle(
        minecraftWorldAccess.openRegion(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionId, regionPosition),
    )
}

/** Dimension selection for synchronous live read-only access. */
class LiveMinecraftWorldDimensions internal constructor(
    private val liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
) {
    val overworld: LiveMinecraftWorldDimension =
        LiveMinecraftWorldDimension(liveMinecraftWorldAccess, DimensionId.Overworld)
    val nether: LiveMinecraftWorldDimension = LiveMinecraftWorldDimension(liveMinecraftWorldAccess, DimensionId.Nether)
    val end: LiveMinecraftWorldDimension = LiveMinecraftWorldDimension(liveMinecraftWorldAccess, DimensionId.End)

    operator fun get(dimensionId: DimensionId): LiveMinecraftWorldDimension = when (dimensionId) {
        DimensionId.Overworld -> overworld
        DimensionId.Nether -> nether
        DimensionId.End -> end
        else -> LiveMinecraftWorldDimension(liveMinecraftWorldAccess, dimensionId)
    }
}

/** Synchronous live read-only access bound to one selected-release dimension directory. */
class LiveMinecraftWorldDimension internal constructor(
    private val liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    val dimensionId: DimensionId,
) {
    val data: LiveMinecraftDimensionSavedData =
        LiveMinecraftDimensionSavedData(liveMinecraftWorldAccess, dimensionId)

    fun listRegionPositions(): List<RegionPosition> =
        liveMinecraftWorldAccess.listRegionPositions(RegionStorageDirectory.CHUNKS, dimensionId)

    fun hasRegion(regionPosition: RegionPosition): Boolean =
        openRegion(regionPosition).use(LiveRegionHandle::hasRegion)

    fun openRegion(regionPosition: RegionPosition): LiveRegionHandle =
        liveMinecraftWorldAccess.openRegion(RegionStorageDirectory.CHUNKS, dimensionId, regionPosition)

    fun listEntityRegionPositions(): List<RegionPosition> =
        liveMinecraftWorldAccess.listRegionPositions(RegionStorageDirectory.ENTITIES, dimensionId)

    fun hasEntityRegion(regionPosition: RegionPosition): Boolean =
        openEntityRegion(regionPosition).use(LiveEntityRegionHandle::hasRegion)

    fun openEntityRegion(regionPosition: RegionPosition): LiveEntityRegionHandle = LiveEntityRegionHandle(
        liveMinecraftWorldAccess.openRegion(RegionStorageDirectory.ENTITIES, dimensionId, regionPosition),
    )

    fun listPoiRegionPositions(): List<RegionPosition> =
        liveMinecraftWorldAccess.listRegionPositions(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionId)

    fun hasPoiRegion(regionPosition: RegionPosition): Boolean =
        openPoiRegion(regionPosition).use(LivePoiRegionHandle::hasRegion)

    fun openPoiRegion(regionPosition: RegionPosition): LivePoiRegionHandle = LivePoiRegionHandle(
        liveMinecraftWorldAccess.openRegion(RegionStorageDirectory.POINTS_OF_INTEREST, dimensionId, regionPosition),
    )
}
