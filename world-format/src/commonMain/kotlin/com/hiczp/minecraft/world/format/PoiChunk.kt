package com.hiczp.minecraft.world.format

/** Domain dimension and vertical bounds shared by a POI Chunk; independent of NBT codec configuration. */
data class PoiChunkContext(val dimensionId: DimensionId, val chunkLayout: ChunkLayout)

/** Mutable POI sections keyed by absolute Section Y for one Chunk; performs no discovery or ticket accounting. */
data class PoiChunk(
    var chunkPosition: ChunkPosition,
    var poiChunkContext: PoiChunkContext,
    var sections: MutableMap<Int, PoiSection>,
    var properties: DataProperties,
) {
    constructor(chunkPosition: ChunkPosition, poiChunkContext: PoiChunkContext) : this(
        chunkPosition, poiChunkContext, linkedMapOf(), DataProperties(),
    )
}

/** Current validity and records keyed by absolute block position; all supplied containers remain caller-owned. */
data class PoiSection(
    var isValid: Boolean,
    var records: MutableMap<BlockPosition, PoiRecord>,
    var properties: DataProperties,
) {
    constructor(isValid: Boolean) : this(isValid, linkedMapOf(), DataProperties())
}

/** Current type, available ticket count and open state for the position supplied by the enclosing map key. */
data class PoiRecord(var poiTypeId: PoiTypeId, var freeTickets: Int, var properties: DataProperties) {
    constructor(poiTypeId: PoiTypeId, freeTickets: Int) : this(poiTypeId, freeTickets, DataProperties())
}

/** Shared definition supplied by the caller. Instances store only their type identity and current ticket count. */
data class PoiType(val matchingStates: Set<BlockState>, val maxTickets: Int, val validRange: Int)
