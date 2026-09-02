package com.hiczp.minecraft.world.format

/** One mutable Point of Interest record at an absolute Block position. */
class PoiRecord(
    val type: String,
    val blockPosition: BlockPosition,
    freeTickets: Int,
) {
    init {
        require(type.isNotBlank()) { "A POI type must not be blank" }
    }

    var freeTickets: Int = freeTickets

    val sectionPosition: SectionPosition
        get() = blockPosition.sectionPosition

    val chunkPosition: ChunkPosition
        get() = blockPosition.chunkPosition

    val regionPosition: RegionPosition
        get() = blockPosition.regionPosition

    val hasSpace: Boolean
        get() = freeTickets > 0

    fun snapshot(): PoiRecord = PoiRecord(type, blockPosition, freeTickets)
}

/** A mutable POI Section at one absolute Section Y coordinate within its owning [PoiChunk]. */
class PoiSection(
    val sectionY: Int,
    valid: Boolean,
    records: Collection<PoiRecord> = emptyList(),
) {
    private val recordsByPosition = records.associateByTo(linkedMapOf(), PoiRecord::blockPosition)

    init {
        require(recordsByPosition.size == records.size) { "A POI Section contains duplicate Block positions" }
        recordsByPosition.values.forEach(::requireRecordMembership)
    }

    var valid: Boolean = valid

    val records: Collection<PoiRecord>
        get() = recordsByPosition.values.toList()

    val recordCount: Int
        get() = recordsByPosition.size

    val isEmpty: Boolean
        get() = recordsByPosition.isEmpty()

    fun record(blockPosition: BlockPosition): PoiRecord? = recordsByPosition[blockPosition]

    fun hasRecord(blockPosition: BlockPosition): Boolean = blockPosition in recordsByPosition

    fun addRecord(poiRecord: PoiRecord) {
        requireRecordMembership(poiRecord)
        require(poiRecord.blockPosition !in recordsByPosition) {
            "A POI is already registered at ${poiRecord.blockPosition}"
        }
        recordsByPosition[poiRecord.blockPosition] = poiRecord
    }

    fun replaceRecord(poiRecord: PoiRecord): PoiRecord? {
        requireRecordMembership(poiRecord)
        return recordsByPosition.put(poiRecord.blockPosition, poiRecord)
    }

    fun removeRecord(blockPosition: BlockPosition): PoiRecord? = recordsByPosition.remove(blockPosition)

    fun snapshot(): PoiSection = PoiSection(sectionY, valid, recordsByPosition.values.map(PoiRecord::snapshot))

    private fun requireRecordMembership(poiRecord: PoiRecord) {
        require(poiRecord.sectionPosition.y == sectionY) {
            "POI ${poiRecord.blockPosition} belongs to Section Y ${poiRecord.sectionPosition.y}, not Section Y $sectionY"
        }
    }
}

/** A mutable selected-release POI Chunk stored at one absolute X/Z position. */
class PoiChunk(
    val chunkPosition: ChunkPosition,
    val dataVersion: Int,
    sections: Collection<PoiSection> = emptyList(),
) {
    private val sectionsByY = sections.associateByTo(linkedMapOf(), PoiSection::sectionY)

    init {
        require(sectionsByY.size == sections.size) { "A POI Chunk contains duplicate Section Y coordinates" }
        requireStoredRecordMembership()
    }

    val regionPosition: RegionPosition
        get() = chunkPosition.regionPosition

    val sections: Collection<PoiSection>
        get() = sectionsByY.values.toList()

    val records: Sequence<PoiRecord>
        get() = sectionsByY.values.asSequence().flatMap { poiSection -> poiSection.records.asSequence() }

    val recordCount: Int
        get() = sectionsByY.values.sumOf(PoiSection::recordCount)

    val isEmpty: Boolean
        get() = sectionsByY.isEmpty()

    fun section(sectionY: Int): PoiSection? = sectionsByY[sectionY]

    fun section(sectionPosition: SectionPosition): PoiSection? {
        require(sectionPosition.chunkPosition == chunkPosition) {
            "Section $sectionPosition does not belong to POI Chunk $chunkPosition"
        }
        return section(sectionPosition.y)
    }

    fun section(blockPosition: BlockPosition): PoiSection? = section(blockPosition.sectionPosition)

    fun hasSection(sectionY: Int): Boolean = sectionY in sectionsByY

    fun hasSection(sectionPosition: SectionPosition): Boolean {
        require(sectionPosition.chunkPosition == chunkPosition) {
            "Section $sectionPosition does not belong to POI Chunk $chunkPosition"
        }
        return hasSection(sectionPosition.y)
    }

    fun hasSection(blockPosition: BlockPosition): Boolean = hasSection(blockPosition.sectionPosition)

    fun getOrCreateSection(sectionY: Int, valid: Boolean = true): PoiSection =
        sectionsByY.getOrPut(sectionY) { PoiSection(sectionY, valid) }

    fun getOrCreateSection(sectionPosition: SectionPosition, valid: Boolean = true): PoiSection {
        require(sectionPosition.chunkPosition == chunkPosition) {
            "Section $sectionPosition does not belong to POI Chunk $chunkPosition"
        }
        return getOrCreateSection(sectionPosition.y, valid)
    }

    fun getOrCreateSection(blockPosition: BlockPosition, valid: Boolean = true): PoiSection =
        getOrCreateSection(blockPosition.sectionPosition, valid)

    fun record(blockPosition: BlockPosition): PoiRecord? =
        sectionsByY[blockPosition.sectionPosition.y]?.record(blockPosition)

    fun hasRecord(blockPosition: BlockPosition): Boolean = record(blockPosition) != null

    fun addRecord(poiRecord: PoiRecord, sectionValid: Boolean = true) {
        requireRecordMembership(poiRecord)
        getOrCreateSection(poiRecord.sectionPosition.y, sectionValid).addRecord(poiRecord)
    }

    fun replaceRecord(poiRecord: PoiRecord, sectionValid: Boolean = true): PoiRecord? {
        requireRecordMembership(poiRecord)
        return getOrCreateSection(poiRecord.sectionPosition.y, sectionValid).replaceRecord(poiRecord)
    }

    fun removeRecord(blockPosition: BlockPosition): PoiRecord? =
        sectionsByY[blockPosition.sectionPosition.y]?.removeRecord(blockPosition)

    fun addSection(poiSection: PoiSection) {
        requireSectionMembership(poiSection)
        require(poiSection.sectionY !in sectionsByY) {
            "A POI Section already exists at Y ${poiSection.sectionY}"
        }
        sectionsByY[poiSection.sectionY] = poiSection
    }

    fun replaceSection(poiSection: PoiSection): PoiSection? {
        requireSectionMembership(poiSection)
        return sectionsByY.put(poiSection.sectionY, poiSection)
    }

    fun removeSection(sectionY: Int): PoiSection? = sectionsByY.remove(sectionY)

    fun snapshot(): PoiChunk = PoiChunk(chunkPosition, dataVersion, sectionsByY.values.map(PoiSection::snapshot))

    internal fun requireStoredRecordMembership() {
        sectionsByY.values.forEach(::requireSectionMembership)
    }

    private fun requireSectionMembership(poiSection: PoiSection) {
        poiSection.records.forEach(::requireRecordMembership)
    }

    private fun requireRecordMembership(poiRecord: PoiRecord) {
        require(poiRecord.chunkPosition == chunkPosition) {
            "POI ${poiRecord.blockPosition} belongs to Chunk ${poiRecord.chunkPosition}, not POI Chunk $chunkPosition"
        }
    }
}
