package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.protocol.model.type.HeightmapType as PacketHeightmapType
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.world.format.*

/**
 * Complete inputs for projecting Chunks to one dimension's full terrain/light packets.
 *
 * Registry mappings come from the destination's Configuration epoch. Update-tag mappings choose client-visible Block
 * Entity data; required-data callbacks supply counts, heights or light absent from the input. These inputs are explicit
 * and independent of the encoded Chunk's replaceable domain context.
 */
data class ChunkPacketEncoderContext(
    val chunkLayout: ChunkLayout,
    val hasSkyLight: Boolean,
    val defaultBlockState: BlockState,
    val defaultBiome: BiomeId,
    val packetCodecContext: PacketCodecContext,
    val chunkPacketWriteMappings: ChunkPacketWriteMappings,
    val chunkPacketRequiredDataProvider: ChunkPacketRequiredDataProvider,
)

/**
 * Complete inputs for constructing Chunks from full terrain/light packets in one dimension and registry epoch.
 * [chunkContext] is attached to each result by reference. [chunkPacketMissingDataProvider] supplies local facts the
 * packet cannot carry; decoding does not recover private persistence state from a server.
 */
data class ChunkPacketDecoderContext(
    val chunkContext: ChunkContext,
    val packetCodecContext: PacketCodecContext,
    val chunkPacketReadMappings: ChunkPacketReadMappings,
    val chunkPacketMissingDataProvider: ChunkPacketMissingDataProvider,
)

fun interface ChunkPacketMissingDataProvider {
    fun provide(chunkPosition: ChunkPosition): ChunkPacketMissingData
}

/** Fields absent from a full Chunk packet. Every invocation may return independent or deliberately shared containers. */
data class ChunkPacketMissingData(
    val status: String,
    val inhabitedTime: Long,
    val lighting: ChunkLighting,
    val blockTicks: MutableList<ScheduledTick<BlockId>>,
    val fluidTicks: MutableList<ScheduledTick<FluidId>>,
    val structures: ChunkStructures,
    val postProcessing: ChunkPostProcessing,
    val upgradeData: UpgradeData?,
    val blendingData: BlendingData?,
    val properties: DataProperties,
    val heightmaps: ChunkHeightmaps,
    val sections: Map<Int, ChunkPacketMissingSectionData>,
) {
    /** Explicitly chooses empty collections and unknown caches, with fresh containers on every call. */
    constructor(status: String, inhabitedTime: Long, isLightCorrect: Boolean) : this(
        status, inhabitedTime, ChunkLighting(isLightCorrect, null), mutableListOf(), mutableListOf(),
        ChunkStructures(), ChunkPostProcessing(), null, null, DataProperties(), ChunkHeightmaps(), emptyMap(),
    )
}

data class ChunkPacketMissingSectionData(
    val tickingBlockCount: Int?,
    val tickingFluidCount: Int?,
    val properties: DataProperties,
)

enum class SectionStatistic { NON_EMPTY_BLOCKS, FLUIDS }
enum class LightKind { BLOCK, SKY }

data class ChunkPacketRequiredDataProvider(
    val sectionStatistic: (Chunk, Int, SectionStatistic) -> Int,
    val heightmapValue: (Chunk, HeightmapType, Int) -> Int,
    /** Null explicitly chooses no update; a zero-valued layer explicitly sends known darkness. */
    val lightLayer: (Chunk, Int, LightKind) -> LightLayer?,
) {
    companion object {
        /** Requires stored counts/heights and omits unavailable light. The caller selects this policy explicitly. */
        val RequirePresent: ChunkPacketRequiredDataProvider = ChunkPacketRequiredDataProvider(
            { _, sectionY, statistic -> error("Section $sectionY has no $statistic count") },
            { _, heightmapType, index -> error("Heightmap ${heightmapType.serializationKey} column $index is unknown") },
            { _, _, _ -> null },
        )
    }
}

data class BlockEntityPacketContents(val components: DataComponentMap, val properties: DataProperties)

data class ChunkPacketReadMappings(
    val blockEntity: (BlockEntityTypeId, NbtCompound?) -> BlockEntityPacketContents,
    val heightmapType: (PacketHeightmapType) -> HeightmapType = { HeightmapType(it.name) },
) {
    companion object {
        /** Reads only the received update tag. Missing private state remains absent in properties. */
        fun dynamic(nbtPropertyReadMappings: NbtPropertyReadMappings): ChunkPacketReadMappings =
            ChunkPacketReadMappings(
                blockEntity = { blockEntityTypeId, tag ->
                    BlockEntityPacketContents(
                        components = tag?.get("components")?.let {
                            NbtPropertyReaders.components.read(it, nbtPropertyReadMappings)
                                .get(PropertyTypes.Components)
                        } ?: DataComponentMap(),
                        properties = tag?.let {
                            nbtPropertyReadMappings.readProperties(
                                it,
                                NbtPropertyScope("block_entity", blockEntityTypeId.value),
                                BLOCK_ENTITY_PACKET_FIELDS,
                            )
                        } ?: DataProperties(),
                    )
                },
            )
    }
}

data class ChunkPacketWriteMappings(
    /** Explicitly selects the client-visible tag; complete storage NBT is never inferred here. */
    val blockEntityUpdateTag: (BlockEntity) -> NbtCompound?,
    val heightmapType: (HeightmapType) -> PacketHeightmapType? = { heightmapType ->
        CLIENT_HEIGHTMAP_TYPES.firstOrNull { it.name == heightmapType.serializationKey }
    },
)

private val BLOCK_ENTITY_PACKET_FIELDS = setOf("id", "x", "y", "z", "components")
private val CLIENT_HEIGHTMAP_TYPES = setOf(
    PacketHeightmapType.WORLD_SURFACE,
    PacketHeightmapType.MOTION_BLOCKING,
    PacketHeightmapType.MOTION_BLOCKING_NO_LEAVES,
)
