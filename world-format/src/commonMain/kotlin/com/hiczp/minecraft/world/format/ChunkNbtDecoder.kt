package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.Source

/** Persisted DataVersion and LastUpdate fields, kept outside the computational Chunk graph. */
data class ChunkNbtMetadata(val dataVersion: Int, val lastUpdateTime: Long)

/** The decoded mutable Chunk and the metadata read from the same record. */
data class ChunkNbtDecodeResult(val chunk: Chunk, val chunkNbtMetadata: ChunkNbtMetadata)

/**
 * Interpretation inputs for one dimension or batch of terrain records.
 * [chunkContext] is attached to decoded Chunks by reference. [tickBase] converts saved relative tick delays to absolute
 * game times; it is not LastUpdate. Persistence version and update time are read from each record, not supplied here.
 */
data class ChunkNbtDecoderContext(
    val chunkContext: ChunkContext,
    val nbtFormat: NbtFormat,
    val nbtPropertyReadMappings: NbtPropertyReadMappings,
    val tickBase: Long,
)

/**
 * Reads the completed-Chunk schema from decompressed NBT using fixed layout and property mappings.
 * Non-full status is exposed for caller decisions; generation-only data is neither completed nor preserved.
 */
class ChunkNbtDecoder(val chunkNbtDecoderContext: ChunkNbtDecoderContext) {
    private val nbtFormat = chunkNbtDecoderContext.nbtFormat.forWorldRecord()
    private val reader = ChunkReader(chunkNbtDecoderContext)

    /** Consumes one decompressed compound-root record without closing the caller-owned source. */
    fun decode(source: Source): ChunkNbtDecodeResult = chunkNbtOperation {
        nbtFormat.decodeFromSource(reader, source)
    }

    /** Decodes an already materialized document through the same semantic reader as [decode]. */
    fun decodeDocument(nbtDocument: NbtDocument): ChunkNbtDecodeResult = chunkNbtOperation {
        chunkNbtDecoderContext.nbtFormat.decodeFromNbtTag(reader, nbtDocument.root)
    }
}

private class ChunkReader(private val context: ChunkNbtDecoderContext) : WorldNbtReader<ChunkNbtDecodeResult>() {
    override fun begin(): WorldNbtReadState<ChunkNbtDecodeResult> = object : WorldNbtReadState<ChunkNbtDecodeResult> {
        private var dataVersion: Int? = null
        private var x = 0
        private var z = 0
        private var lastUpdateTime = 0L
        private var inhabitedTime = 0L
        private var status: String? = null
        private var lightCorrect = false
        private var upgradeData: UpgradeData? = null
        private var blendingData: BlendingData? = null
        private var heightmaps = ChunkHeightmaps()
        private var blockTicks = mutableListOf<ScheduledTick<BlockId>>()
        private var fluidTicks = mutableListOf<ScheduledTick<FluidId>>()
        private var structures = ChunkStructures()
        private var postProcessing = ChunkPostProcessing()
        private var sections = linkedMapOf<Int, ChunkSection>()
        private var blockEntities = linkedMapOf<BlockPosition, BlockEntity>()
        private val properties = DataProperties()
        private val mappings = context.nbtPropertyReadMappings
        private val chunkLayout = context.chunkContext.dimensionTypeLayout.chunkLayout

        override fun read(name: String, worldNbtFieldInput: WorldNbtFieldInput) {
            when (name) {
                "sections" -> {
                    val values = worldNbtFieldInput.read(WorldNbtListReader(WorldNbtCompoundValueReader { section ->
                        decodeSection(section, context.chunkContext, mappings)
                    }))
                    sections = linkedMapOf()
                    values.forEach { (y, chunkSection) ->
                        require(sections.put(y, chunkSection) == null) { "Duplicate Section Y $y" }
                    }
                }

                "block_entities" -> {
                    val values = worldNbtFieldInput.read(WorldNbtListReader(WorldNbtCompoundValueReader { blockEntity ->
                        decodeBlockEntity(blockEntity, mappings)
                    }))
                    blockEntities = linkedMapOf()
                    values.forEach { (position, blockEntity) ->
                        require(
                            blockEntities.put(
                                position,
                                blockEntity
                            ) == null
                        ) { "Duplicate Block Entity at $position" }
                    }
                }

                else -> {
                    val nbtTag = worldNbtFieldInput.tag()
                    when (name) {
                        "DataVersion" -> dataVersion = nbtTag.int()
                        "xPos" -> x = nbtTag.int()
                        "zPos" -> z = nbtTag.int()
                        "yPos" -> nbtTag.int() // The explicit domain layout owns the interpretation.
                        "LastUpdate" -> lastUpdateTime = nbtTag.long()
                        "InhabitedTime" -> inhabitedTime = nbtTag.long()
                        "Status" -> status = nbtTag.string()
                        "isLightOn" -> lightCorrect = (nbtTag as? NbtByte)?.value?.let { it != 0.toByte() }
                            ?: throw ChunkNbtFormatException("isLightOn must be a Boolean Byte")

                        "UpgradeData" -> upgradeData = decodeUpgradeData(nbtTag.compound(), chunkLayout, mappings)
                        "blending_data" -> blendingData = decodeBlendingData(nbtTag.compound(), mappings)
                        "Heightmaps" -> heightmaps = decodeHeightmaps(nbtTag.compound(), chunkLayout, mappings)
                        "block_ticks" -> blockTicks =
                            decodeScheduledTicks(nbtTag.list(), context.tickBase, mappings, BlockId::parse)

                        "fluid_ticks" -> fluidTicks =
                            decodeScheduledTicks(nbtTag.list(), context.tickBase, mappings, FluidId::parse)

                        "PostProcessing" -> postProcessing = decodePostProcessing(nbtTag.list(), chunkLayout)
                        "structures" -> structures = decodeStructures(nbtTag.compound(), mappings)
                        in PROTO_CHUNK_FIELDS -> Unit
                        else -> properties[name] = mappings.read(NbtPropertyScope.Chunk, name, nbtTag)
                    }
                }
            }
        }

        override fun finish(): ChunkNbtDecodeResult {
            val position = ChunkPosition(x, z)
            blockEntities.keys.forEach { blockPosition ->
                require(blockPosition.chunkPosition == position && chunkLayout.containsBlockY(blockPosition.y)) {
                    "Block Entity $blockPosition lies outside Chunk $position"
                }
            }
            val chunk = Chunk(
                position, context.chunkContext, sections, blockEntities, heightmaps, ChunkLighting(lightCorrect, null),
                blockTicks, fluidTicks, structures, postProcessing,
                requireNotNull(status) { "Missing Chunk Status" }, inhabitedTime, upgradeData, blendingData, properties,
            )
            return ChunkNbtDecodeResult(
                chunk, ChunkNbtMetadata(requireNotNull(dataVersion) { "Missing Chunk DataVersion" }, lastUpdateTime),
            )
        }
    }
}

class ChunkNbtFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal inline fun <T> chunkNbtOperation(block: () -> T): T = try {
    block()
} catch (failure: ChunkNbtFormatException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw ChunkNbtFormatException(failure.message ?: "Invalid Chunk NBT", failure)
}

internal val PROTO_CHUNK_FIELDS: Set<String> = setOf("below_zero_retrogen", "carving_mask", "entities")
internal val CHUNK_NBT_FIELDS: Set<String> = setOf(
    "DataVersion",
    "xPos",
    "yPos",
    "zPos",
    "LastUpdate",
    "InhabitedTime",
    "Status",
    "isLightOn",
    "UpgradeData",
    "blending_data",
    "Heightmaps",
    "block_ticks",
    "fluid_ticks",
    "PostProcessing",
    "structures",
    "sections",
    "block_entities",
) + PROTO_CHUNK_FIELDS
