package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtIntArray
import com.hiczp.minecraft.nbt.NbtList
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.Source

data class PoiChunkNbtMetadata(val dataVersion: Int)

data class PoiChunkNbtDecodeResult(val poiChunk: PoiChunk, val poiChunkNbtMetadata: PoiChunkNbtMetadata)

/** POI NBT has no Chunk position. The selected Region slot is supplied as part of this complete context. */
data class PoiChunkNbtDecoderContext(
    val poiChunkContext: PoiChunkContext,
    val chunkPosition: ChunkPosition,
    val nbtFormat: NbtFormat,
    val nbtPropertyReadMappings: NbtPropertyReadMappings,
)

/** Reads POI sections and records from decompressed NBT with explicit property mappings. */
class PoiChunkNbtDecoder(val poiChunkNbtDecoderContext: PoiChunkNbtDecoderContext) {
    private val nbtFormat = poiChunkNbtDecoderContext.nbtFormat.forWorldRecord()
    private val reader = PoiChunkReader(poiChunkNbtDecoderContext)

    /** Consumes one NBT record, leaving the source open; returns domain data and the stored DataVersion separately. */
    fun decode(source: Source): PoiChunkNbtDecodeResult = poiChunkNbtOperation {
        nbtFormat.decodeFromSource(reader, source)
    }

    /** Applies the same semantic reader to an already materialized NBT document. */
    fun decodeDocument(nbtDocument: NbtDocument): PoiChunkNbtDecodeResult = poiChunkNbtOperation {
        poiChunkNbtDecoderContext.nbtFormat.decodeFromNbtTag(reader, nbtDocument.root)
    }
}

private class PoiChunkReader(private val context: PoiChunkNbtDecoderContext) :
    WorldNbtReader<PoiChunkNbtDecodeResult>() {
    override fun begin(): WorldNbtReadState<PoiChunkNbtDecodeResult> =
        object : WorldNbtReadState<PoiChunkNbtDecodeResult> {
            private var dataVersion: Int? = null
            private var sections: MutableMap<Int, PoiSection>? = null
            private val properties = DataProperties()

            override fun read(name: String, worldNbtFieldInput: WorldNbtFieldInput) {
                when (name) {
                    "DataVersion" -> dataVersion = worldNbtFieldInput.tag().int()
                    "Sections" -> sections = worldNbtFieldInput.read(PoiSectionsReader(context))
                    else -> properties[name] =
                        context.nbtPropertyReadMappings.read(NbtPropertyScope.PoiChunk, name, worldNbtFieldInput.tag())
                }
            }

            override fun finish(): PoiChunkNbtDecodeResult = PoiChunkNbtDecodeResult(
                PoiChunk(
                    context.chunkPosition,
                    context.poiChunkContext,
                    requireNotNull(sections) { "Missing POI Sections" },
                    properties
                ),
                PoiChunkNbtMetadata(requireNotNull(dataVersion) { "Missing POI DataVersion" }),
            )
        }
}

private class PoiSectionsReader(private val context: PoiChunkNbtDecoderContext) :
    WorldNbtReader<MutableMap<Int, PoiSection>>() {
    override fun begin(): WorldNbtReadState<MutableMap<Int, PoiSection>> =
        object : WorldNbtReadState<MutableMap<Int, PoiSection>> {
            private val sections = linkedMapOf<Int, PoiSection>()

            override fun read(name: String, worldNbtFieldInput: WorldNbtFieldInput) {
                val y = name.toInt()
                require(y in context.poiChunkContext.chunkLayout) { "POI Section Y $y is outside the dimension" }
                val section = decodePoiSection(worldNbtFieldInput.tag().compound(), context.nbtPropertyReadMappings)
                section.records.keys.forEach { requirePoiPosition(it, y, context.chunkPosition) }
                require(sections.put(y, section) == null) { "Duplicate POI Section Y $y" }
            }

            override fun finish(): MutableMap<Int, PoiSection> = sections
        }
}

private fun decodePoiSection(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): PoiSection {
    val records = linkedMapOf<BlockPosition, PoiRecord>()
    nbtCompound.requiredTag<NbtList>("Records").forEach { tag ->
        val record = tag.compound()
        val position = record.requiredTag<NbtIntArray>("pos").toBlockPosition()
        val value = PoiRecord(
            PoiTypeId.parse(record.string("type")), record.int("free_tickets", 0),
            mappings.readProperties(record, NbtPropertyScope.PoiRecord, POI_RECORD_NBT_FIELDS)
        )
        require(records.put(position, value) == null) { "Duplicate POI at $position" }
    }
    return PoiSection(
        nbtCompound.boolean("Valid", false), records,
        mappings.readProperties(nbtCompound, NbtPropertyScope.PoiSection, POI_SECTION_NBT_FIELDS)
    )
}

internal fun requirePoiPosition(blockPosition: BlockPosition, y: Int, chunkPosition: ChunkPosition) {
    require(blockPosition.chunkPosition == chunkPosition && blockPosition.sectionPosition.y == y) {
        "POI position $blockPosition lies outside Section Y $y of Chunk $chunkPosition"
    }
}

class PoiChunkNbtFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal inline fun <T> poiChunkNbtOperation(block: () -> T): T = try {
    block()
} catch (failure: PoiChunkNbtFormatException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw PoiChunkNbtFormatException(failure.message ?: "Invalid POI Chunk NBT", failure)
}

internal val POI_CHUNK_NBT_FIELDS: Set<String> = setOf("DataVersion", "Sections")
internal val POI_SECTION_NBT_FIELDS: Set<String> = setOf("Valid", "Records")
internal val POI_RECORD_NBT_FIELDS: Set<String> = setOf("pos", "type", "free_tickets")
