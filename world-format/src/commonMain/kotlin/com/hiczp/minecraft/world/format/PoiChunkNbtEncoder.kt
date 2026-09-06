package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.Sink

/** Explicit output layout, NBT format, mappings and DataVersion; not inferred from the POI Chunk context. */
data class PoiChunkNbtEncoderContext(
    val chunkLayout: ChunkLayout,
    val nbtFormat: NbtFormat,
    val nbtPropertyWriteMappings: NbtPropertyWriteMappings,
    val poiChunkNbtMetadata: PoiChunkNbtMetadata,
)

/** Writes the current POI sections and records using the supplied mappings and persistence metadata. */
class PoiChunkNbtEncoder(val poiChunkNbtEncoderContext: PoiChunkNbtEncoderContext) {
    private val nbtFormat = poiChunkNbtEncoderContext.nbtFormat.forWorldRecord()
    private val writer = PoiChunkWriter(poiChunkNbtEncoderContext)

    /** Writes decompressed NBT without mutating the graph, flushing the sink or closing it. */
    fun encode(poiChunk: PoiChunk, sink: Sink) = poiChunkNbtOperation {
        nbtFormat.encodeToSink(poiChunk, sink, writer)
    }

    /** Materializes the same persisted representation as [encode] in a document. */
    fun encodeDocument(poiChunk: PoiChunk): NbtDocument = poiChunkNbtOperation {
        NbtDocument(poiChunkNbtEncoderContext.nbtFormat.encodeToNbtTag(poiChunk, writer).compound())
    }
}

private class PoiChunkWriter(private val context: PoiChunkNbtEncoderContext) : WorldNbtWriter<PoiChunk>() {
    override fun fields(value: PoiChunk): List<WorldNbtField<*>> = buildList {
        val chunkPosition = value.chunkPosition
        add(nbtField("DataVersion", NbtInt(context.poiChunkNbtMetadata.dataVersion)))
        add(WorldNbtField("Sections", object : WorldNbtWriter<MutableMap<Int, PoiSection>>() {
            override fun fields(value: MutableMap<Int, PoiSection>): List<WorldNbtField<*>> =
                value.entries.sortedBy { it.key }.map { (y, section) ->
                    require(y in context.chunkLayout) { "POI Section Y $y is outside the dimension" }
                    section.records.keys.forEach { requirePoiPosition(it, y, chunkPosition) }
                    nbtField(y.toString(), encodePoiSection(section, context.nbtPropertyWriteMappings))
                }
        }, value.sections))
        context.nbtPropertyWriteMappings.writeProperties(
            value.properties,
            NbtPropertyScope.PoiChunk,
            POI_CHUNK_NBT_FIELDS
        )
            .forEach { (name, tag) -> add(nbtField(name, tag)) }
    }
}

private fun encodePoiSection(value: PoiSection, mappings: NbtPropertyWriteMappings): NbtCompound {
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope.PoiSection, POI_SECTION_NBT_FIELDS)
    if (value.isValid) fields["Valid"] = NbtByte(1)
    fields["Records"] = NbtList(value.records.map { (position, record) ->
        val recordFields =
            mappings.writeProperties(record.properties, NbtPropertyScope.PoiRecord, POI_RECORD_NBT_FIELDS)
        recordFields["pos"] = position.toNbt()
        recordFields["type"] = NbtString(record.poiTypeId.toString())
        if (record.freeTickets != 0) recordFields["free_tickets"] = NbtInt(record.freeTickets)
        NbtCompound(recordFields)
    })
    return NbtCompound(fields)
}
