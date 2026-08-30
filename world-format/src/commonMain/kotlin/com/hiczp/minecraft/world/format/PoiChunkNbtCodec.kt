package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.io.Sink
import kotlinx.io.Source

/** A selected-release POI Chunk NBT error. */
class PoiChunkNbtFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Conversion between unnamed-root POI Chunk NBT and a positioned semantic [PoiChunk]. */
class PoiChunkNbtCodec(
    val nbtFormat: NbtFormat = NbtFormat(NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED)),
) {
    init {
        require(nbtFormat.nbtFormatConfiguration.nbtRootEncoding == NbtRootEncoding.UNNAMED) {
            "Region POI Chunk NBT requires NbtRootEncoding.UNNAMED"
        }
    }

    fun decodeFromSource(source: Source, chunkPosition: ChunkPosition): PoiChunk =
        decodeDocument(nbtFormat.decodeDocumentFromSource(source), chunkPosition)

    fun encodeToSink(poiChunk: PoiChunk, sink: Sink) {
        nbtFormat.encodeDocumentToSink(encodeDocument(poiChunk), sink)
    }

    fun decodeDocument(nbtDocument: NbtDocument, chunkPosition: ChunkPosition): PoiChunk {
        val root = nbtDocument.root
        root.rejectUnknownPoiKeys(POI_CHUNK_ROOT_FIELDS, "POI Chunk")
        val dataVersion = root.requirePoiInt(DATA_VERSION, "POI Chunk")
        val sections = root.requirePoiCompound(SECTIONS, "POI Chunk").value.map { (sectionYName, nbtTag) ->
            val sectionY = sectionYName.toIntOrNull()
                ?: throw PoiChunkNbtFormatException("POI Section key is not an integer: $sectionYName")
            decodeSection(
                sectionY,
                nbtTag as? NbtCompound
                    ?: wrongPoiType("POI Section $sectionY", "TAG_Compound", nbtTag),
            )
        }
        return try {
            PoiChunk(chunkPosition, dataVersion, sections)
        } catch (failure: IllegalArgumentException) {
            throw PoiChunkNbtFormatException("Invalid POI Chunk $chunkPosition", failure)
        }
    }

    fun encodeDocument(poiChunk: PoiChunk): NbtDocument {
        val sections = linkedMapOf<String, NbtTag>()
        poiChunk.sections.sortedBy(PoiSection::sectionY).forEach { poiSection ->
            poiSection.records.firstOrNull { poiRecord -> poiRecord.chunkPosition != poiChunk.chunkPosition }
                ?.let { poiRecord ->
                    throw PoiChunkNbtFormatException(
                        "POI ${poiRecord.blockPosition} belongs to Chunk ${poiRecord.chunkPosition}, expected ${poiChunk.chunkPosition}",
                    )
                }
            sections[poiSection.sectionY.toString()] = encodeSection(poiSection)
        }
        return NbtDocument(
            NbtCompound(
                linkedMapOf(
                    DATA_VERSION to NbtInt(poiChunk.dataVersion),
                    SECTIONS to NbtCompound(sections),
                ),
            ),
        )
    }

    private fun decodeSection(sectionY: Int, nbtCompound: NbtCompound): PoiSection {
        nbtCompound.rejectUnknownPoiKeys(POI_SECTION_FIELDS, "POI Section $sectionY")
        val valid = nbtCompound.optionalPoiBoolean(VALID, "POI Section $sectionY") ?: false
        val records = nbtCompound.requirePoiList(RECORDS, "POI Section $sectionY").value.mapIndexed { index, nbtTag ->
            decodeRecord(
                nbtTag as? NbtCompound
                    ?: wrongPoiType("POI record $index in Section $sectionY", "TAG_Compound", nbtTag),
            )
        }
        return try {
            PoiSection(sectionY, valid, records)
        } catch (failure: IllegalArgumentException) {
            throw PoiChunkNbtFormatException("Invalid POI Section $sectionY", failure)
        }
    }

    private fun encodeSection(poiSection: PoiSection): NbtCompound {
        val value = linkedMapOf<String, NbtTag>()
        if (poiSection.valid) value[VALID] = NbtByte(1)
        value[RECORDS] = NbtList(poiSection.records.map(::encodeRecord))
        return NbtCompound(value)
    }

    private fun decodeRecord(nbtCompound: NbtCompound): PoiRecord {
        nbtCompound.rejectUnknownPoiKeys(POI_RECORD_FIELDS, "POI record")
        val position = nbtCompound.requirePoiBlockPosition(POSITION)
        val type = nbtCompound.requirePoiString(TYPE, "POI record")
        val freeTickets = nbtCompound.optionalPoiInt(FREE_TICKETS, "POI record") ?: 0
        return try {
            PoiRecord(type, position, freeTickets)
        } catch (failure: IllegalArgumentException) {
            throw PoiChunkNbtFormatException("Invalid POI record at $position", failure)
        }
    }

    private fun encodeRecord(poiRecord: PoiRecord): NbtCompound {
        val value = linkedMapOf<String, NbtTag>()
        value[POSITION] = NbtIntArray(
            intArrayOf(
                poiRecord.blockPosition.x,
                poiRecord.blockPosition.y,
                poiRecord.blockPosition.z,
            ),
        )
        value[TYPE] = NbtString(poiRecord.type)
        if (poiRecord.freeTickets != 0) value[FREE_TICKETS] = NbtInt(poiRecord.freeTickets)
        return NbtCompound(value)
    }
}

private fun NbtCompound.rejectUnknownPoiKeys(known: Set<String>, description: String) {
    val unknown = value.keys - known
    if (unknown.isNotEmpty()) {
        throw PoiChunkNbtFormatException("$description contains unmodeled fields: ${unknown.sorted().joinToString()}")
    }
}

private fun NbtCompound.requirePoiInt(name: String, description: String): Int =
    (this[name] as? NbtInt)?.value ?: wrongPoiFieldType(description, name, "TAG_Int")

private fun NbtCompound.optionalPoiInt(name: String, description: String): Int? {
    val nbtTag = this[name] ?: return null
    return (nbtTag as? NbtInt)?.value ?: wrongPoiFieldType(description, name, "TAG_Int")
}

private fun NbtCompound.requirePoiString(name: String, description: String): String =
    (this[name] as? NbtString)?.value ?: wrongPoiFieldType(description, name, "TAG_String")

private fun NbtCompound.requirePoiList(name: String, description: String): NbtList =
    this[name] as? NbtList ?: wrongPoiFieldType(description, name, "TAG_List")

private fun NbtCompound.requirePoiCompound(name: String, description: String): NbtCompound =
    this[name] as? NbtCompound ?: wrongPoiFieldType(description, name, "TAG_Compound")

private fun NbtCompound.optionalPoiBoolean(name: String, description: String): Boolean? {
    val nbtTag = this[name] ?: return null
    return when (nbtTag) {
        is NbtByte -> nbtTag.value.toInt() != 0
        else -> wrongPoiFieldType(description, name, "TAG_Byte")
    }
}

private fun NbtCompound.requirePoiBlockPosition(name: String): BlockPosition {
    val values = this[name] as? NbtIntArray ?: wrongPoiFieldType("POI record", name, "three-entry TAG_Int_Array")
    if (values.size != 3) throw PoiChunkNbtFormatException("POI record position must contain three integers")
    return BlockPosition(values[0], values[1], values[2])
}

private fun NbtCompound.wrongPoiFieldType(description: String, name: String, expected: String): Nothing {
    val actual = this[name]?.let { nbtTag -> nbtTag::class.simpleName } ?: "missing"
    throw PoiChunkNbtFormatException("$description field $name must be $expected, got $actual")
}

private fun wrongPoiType(description: String, expected: String, actual: NbtTag): Nothing =
    throw PoiChunkNbtFormatException("$description must be $expected, got ${actual::class.simpleName}")

private const val DATA_VERSION = "DataVersion"
private const val SECTIONS = "Sections"
private const val VALID = "Valid"
private const val RECORDS = "Records"
private const val POSITION = "pos"
private const val TYPE = "type"
private const val FREE_TICKETS = "free_tickets"
private val POI_CHUNK_ROOT_FIELDS = setOf(DATA_VERSION, SECTIONS)
private val POI_SECTION_FIELDS = setOf(VALID, RECORDS)
private val POI_RECORD_FIELDS = setOf(POSITION, TYPE, FREE_TICKETS)
