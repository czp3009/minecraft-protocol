package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.Sink

/**
 * Layout, NBT mappings, tick base and persistence metadata used for terrain writes.
 * [chunkNbtMetadata] supplies the DataVersion and LastUpdate to write; [tickBase] converts absolute scheduled times
 * into saved relative delays. Construct a new context when those facts change.
 */
data class ChunkNbtEncoderContext(
    val chunkLayout: ChunkLayout,
    val nbtFormat: NbtFormat,
    val nbtPropertyWriteMappings: NbtPropertyWriteMappings,
    val tickBase: Long,
    val chunkNbtMetadata: ChunkNbtMetadata,
)

/** Encodes the Chunk graph currently reachable from its root using only the explicitly supplied codec context. */
class ChunkNbtEncoder(val chunkNbtEncoderContext: ChunkNbtEncoderContext) {
    private val nbtFormat = chunkNbtEncoderContext.nbtFormat.forWorldRecord()
    private val writer = ChunkWriter(chunkNbtEncoderContext)

    /** Writes decompressed NBT without mutating the Chunk, flushing the sink or closing it. */
    fun encode(chunk: Chunk, sink: Sink) = chunkNbtOperation {
        nbtFormat.encodeToSink(chunk, sink, writer)
    }

    /** Materializes the same persisted representation as [encode] in an NBT document. */
    fun encodeDocument(chunk: Chunk): NbtDocument = chunkNbtOperation {
        NbtDocument(chunkNbtEncoderContext.nbtFormat.encodeToNbtTag(chunk, writer).compound())
    }
}

private class ChunkWriter(private val context: ChunkNbtEncoderContext) : WorldNbtWriter<Chunk>() {
    override fun fields(value: Chunk): List<WorldNbtField<*>> {
        val chunkLayout = context.chunkLayout
        val mappings = context.nbtPropertyWriteMappings
        require(value.status.isNotBlank()) { "A Chunk Status must not be blank" }
        value.blockEntities.keys.forEach { blockPosition ->
            require(blockPosition.chunkPosition == value.chunkPosition && chunkLayout.containsBlockY(blockPosition.y)) {
                "Block Entity $blockPosition lies outside Chunk ${value.chunkPosition}"
            }
        }
        return buildList {
            add(nbtField("DataVersion", NbtInt(context.chunkNbtMetadata.dataVersion)))
            add(nbtField("xPos", NbtInt(value.chunkPosition.x)))
            add(nbtField("yPos", NbtInt(chunkLayout.minSectionY)))
            add(nbtField("zPos", NbtInt(value.chunkPosition.z)))
            add(nbtField("LastUpdate", NbtLong(context.chunkNbtMetadata.lastUpdateTime)))
            add(nbtField("InhabitedTime", NbtLong(value.inhabitedTime)))
            add(nbtField("Status", NbtString(value.status)))
            if (value.lighting.isLightCorrect) add(nbtField("isLightOn", NbtByte(1)))
            value.upgradeData?.let { add(nbtField("UpgradeData", encodeUpgradeData(it, chunkLayout, mappings))) }
            value.blendingData?.let { add(nbtField("blending_data", encodeBlendingData(it, mappings))) }
            add(
                WorldNbtField(
                    "sections",
                    WorldNbtListWriter(WorldNbtCompoundValueWriter { entry: Map.Entry<Int, ChunkSection> ->
                        encodeSection(entry.key, entry.value, chunkLayout, mappings)
                    }),
                    value.sections.entries.sortedBy { it.key })
            )
            add(
                WorldNbtField(
                    "block_entities",
                    WorldNbtListWriter(WorldNbtCompoundValueWriter { entry: Map.Entry<BlockPosition, BlockEntity> ->
                        encodeBlockEntity(entry.key, entry.value, mappings)
                    }),
                    value.blockEntities.entries.toList()
                )
            )
            add(nbtField("Heightmaps", encodeHeightmaps(value.heightmaps, chunkLayout, mappings)))
            add(
                nbtField(
                    "block_ticks",
                    encodeScheduledTicks(value.blockTicks, context.tickBase, mappings, BlockId::toString)
                )
            )
            add(
                nbtField(
                    "fluid_ticks",
                    encodeScheduledTicks(value.fluidTicks, context.tickBase, mappings, FluidId::toString)
                )
            )
            add(nbtField("PostProcessing", encodePostProcessing(value.postProcessing, chunkLayout)))
            add(nbtField("structures", encodeStructures(value.structures, mappings)))
            mappings.writeProperties(value.properties, NbtPropertyScope.Chunk, CHUNK_NBT_FIELDS)
                .forEach { (name, tag) ->
                    add(nbtField(name, tag))
                }
        }
    }
}
