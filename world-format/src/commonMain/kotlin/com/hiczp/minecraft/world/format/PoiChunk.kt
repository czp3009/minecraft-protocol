package com.hiczp.minecraft.world.format

/** Domain dimension and vertical bounds shared by a POI Chunk; independent of NBT codec configuration. */
data class PoiChunkContext(val dimensionId: DimensionId, val chunkLayout: ChunkLayout)

/** Mutable POI Sections in a nullable array with an explicit absolute Y origin for one Chunk; performs no discovery or ticket accounting. */
class PoiChunk(
    var chunkPosition: ChunkPosition,
    var poiChunkContext: PoiChunkContext,
    var sections: Array<PoiSection?>,
    var properties: DataProperties,
    var sectionMinY: Int = poiChunkContext.chunkLayout.minSectionY,
) {
    constructor(chunkPosition: ChunkPosition, poiChunkContext: PoiChunkContext) : this(
        chunkPosition, poiChunkContext, arrayOfNulls(poiChunkContext.chunkLayout.sectionCount), DataProperties(),
    )

    /** Absolute-Y access to the caller-owned Section array. */
    fun getSection(sectionY: Int): PoiSection? = sections.getOrNull(sectionY - sectionMinY)

    /** Assigns within the current array interval; replace [sections] and [sectionMinY] to represent another interval. */
    fun setSection(sectionY: Int, poiSection: PoiSection?) {
        sections[sectionY - sectionMinY] = poiSection
    }

    fun sectionEntries(): Sequence<Pair<Int, PoiSection>> = sections.asSequence().mapIndexedNotNull { index, section ->
        section?.let { sectionMinY + index to it }
    }
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
