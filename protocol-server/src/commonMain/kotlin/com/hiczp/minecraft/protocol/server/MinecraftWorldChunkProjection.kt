package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.protocol.datapack.MinecraftChunkContext
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.world.format.PalettedContainer
import com.hiczp.minecraft.protocol.model.type.ChunkData as NetworkChunkData
import com.hiczp.minecraft.protocol.model.type.ChunkSection as NetworkChunkSection
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as NetworkPalettedContainer

fun MinecraftChunkContext.packetEncoder(
    isAir: (ProtocolBlockState) -> Boolean,
    hasFluid: (ProtocolBlockState) -> Boolean,
    blockEntityUpdateTag: (NbtCompound) -> NbtCompound? = ::defaultBlockEntityUpdateTag,
): MinecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
    minecraftChunkContext = this,
    isAir = isAir,
    hasFluid = hasFluid,
    blockEntityUpdateTag = blockEntityUpdateTag,
)

/**
 * Stateless projection of strong world Chunks into clientbound Chunk packets.
 *
 * [isAir] and [hasFluid] come from the caller's selected-release vanilla or mod block data. They cannot be inferred
 * from registry IDs alone. The encoder is immutable and can be shared by every Chunk sent with
 * [protocolRegistryContext].
 */
class MinecraftChunkPacketEncoder(
    val protocolRegistryContext: ProtocolRegistryContext,
    val chunkCodecContext: ChunkCodecContext<ProtocolBlockState, ProtocolRegistryEntry>,
    private val isAir: (ProtocolBlockState) -> Boolean,
    private val hasFluid: (ProtocolBlockState) -> Boolean,
    private val hasSkyLight: Boolean,
    private val blockEntityUpdateTag: (NbtCompound) -> NbtCompound? = ::defaultBlockEntityUpdateTag,
) {
    constructor(
        minecraftChunkContext: MinecraftChunkContext,
        isAir: (ProtocolBlockState) -> Boolean,
        hasFluid: (ProtocolBlockState) -> Boolean,
        blockEntityUpdateTag: (NbtCompound) -> NbtCompound? = ::defaultBlockEntityUpdateTag,
    ) : this(
        protocolRegistryContext = minecraftChunkContext.protocolRegistryContext,
        chunkCodecContext = minecraftChunkContext.chunkCodecContext,
        isAir = isAir,
        hasFluid = hasFluid,
        hasSkyLight = minecraftChunkContext.dimensionTypeLayout.hasSkyLight,
        blockEntityUpdateTag = blockEntityUpdateTag,
    )

    val chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
        chunkCodecContext.chunkDataRegistries

    private val biomeRegistrySize =
        protocolRegistryContext.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY).size

    init {
        require(protocolRegistryContext.blockStateRegistrySize > 0) { "The active block-state registry is empty" }
        require(biomeRegistrySize > 0) { "The active biome registry is empty" }
        require(protocolRegistryContext.chunkSectionCount == chunkCodecContext.chunkLayout.sectionCount) {
            val sectionCount = protocolRegistryContext.chunkSectionCount
            "Chunk layout has ${chunkCodecContext.chunkLayout.sectionCount} Sections, but the context has $sectionCount"
        }
    }

    /** Encodes [chunk] directly into the packet accepted by the connection's outgoing channel. */
    fun encodePacket(chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>): ChunkDataAndUpdateLightPacket {
        require(chunk.chunkLayout == chunkCodecContext.chunkLayout) {
            "Chunk layout ${chunk.chunkLayout} does not match encoder layout ${chunkCodecContext.chunkLayout}"
        }
        require(chunk.defaultBlockState == chunkDataRegistries.blockStates.defaultValue) {
            "Chunk and encoder use different default block states"
        }
        require(chunk.defaultBiome == chunkDataRegistries.biomes.defaultValue) {
            "Chunk and encoder use different default biomes"
        }
        val chunkMetadata = chunk.chunkMetadata
        val sectionsByY = chunk.sections.associateBy(ChunkSection<ProtocolBlockState, ProtocolRegistryEntry>::sectionY)
        val packetSections = chunk.chunkLayout.sectionYRange.map { sectionY ->
            encodeSection(sectionsByY[sectionY], chunk.defaultBlockState, chunk.defaultBiome)
        }
        val packetLight = encodeLight(
            chunk.chunkLayout.minSectionY,
            chunk.chunkLayout.sectionCount,
            sectionsByY,
            chunkMetadata.lightOnlySections,
        )
        return ChunkDataAndUpdateLightPacket(
            chunkX = chunk.chunkPosition.x,
            chunkZ = chunk.chunkPosition.z,
            chunkData = NetworkChunkData(
                heightmaps = encodeHeightmaps(chunkMetadata.heightmaps),
                sections = packetSections,
                blockEntities = encodeBlockEntities(chunk.chunkPosition, chunk.blockEntities),
            ),
            lightData = packetLight,
        )
    }

    /** Encodes [chunk] into the server's initial-world snapshot value. */
    fun encode(chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>): MinecraftChunkSnapshot =
        encodePacket(chunk).toMinecraftChunkSnapshot()

    private fun encodeSection(
        chunkSection: ChunkSection<ProtocolBlockState, ProtocolRegistryEntry>?,
        defaultBlockState: ProtocolBlockState,
        defaultBiome: ProtocolRegistryEntry,
    ): NetworkChunkSection {
        if (chunkSection == null) {
            return NetworkChunkSection(
                nonAirBlockCount = if (isAir(defaultBlockState)) 0 else SECTION_BLOCK_COUNT,
                fluidCount = if (hasFluid(defaultBlockState)) SECTION_BLOCK_COUNT else 0,
                blockStates = NetworkPalettedContainer.Single(defaultBlockState.id),
                biomes = NetworkPalettedContainer.Single(defaultBiome.rawId),
            )
        }

        var nonAirBlockCount = 0
        var fluidCount = 0
        chunkSection.blockStates.forEach { protocolBlockState ->
            if (!isAir(protocolBlockState)) nonAirBlockCount++
            if (hasFluid(protocolBlockState)) fluidCount++
        }
        return NetworkChunkSection(
            nonAirBlockCount = nonAirBlockCount,
            fluidCount = fluidCount,
            blockStates = encodePalette(
                palettedContainer = chunkSection.blockStates,
                registrySize = protocolRegistryContext.blockStateRegistrySize,
                minimumIndirectBits = BLOCK_MINIMUM_INDIRECT_BITS,
                maximumIndirectBits = BLOCK_MAXIMUM_INDIRECT_BITS,
                id = ProtocolBlockState::id,
            ),
            biomes = encodePalette(
                palettedContainer = chunkSection.biomes,
                registrySize = biomeRegistrySize,
                minimumIndirectBits = BIOME_MINIMUM_INDIRECT_BITS,
                maximumIndirectBits = BIOME_MAXIMUM_INDIRECT_BITS,
                id = ProtocolRegistryEntry::rawId,
            ),
        )
    }

    private fun <T : Any> encodePalette(
        palettedContainer: PalettedContainer<T>,
        registrySize: Int,
        minimumIndirectBits: Int,
        maximumIndirectBits: Int,
        id: (T) -> Int,
    ): NetworkPalettedContainer {
        val compactPalette = palettedContainer.compactSnapshot()
        val registryIds = compactPalette.values.map(id)
        if (registryIds.size == 1) return NetworkPalettedContainer.Single(registryIds.single())

        val logicalBits = minimumBitsForDistinctValues(registryIds.size)
        return if (logicalBits <= maximumIndirectBits) {
            val bits = maxOf(minimumIndirectBits, logicalBits)
            NetworkPalettedContainer.Indirect(
                bitsPerEntry = bits,
                palette = registryIds,
                data = packValues(bits, compactPalette.entryCount) { index -> compactPalette.ids[index] },
            )
        } else {
            val bits = minimumBitsForDistinctValues(registrySize)
            NetworkPalettedContainer.Direct(
                packValues(bits, compactPalette.entryCount) { index -> registryIds[compactPalette.ids[index]] },
            )
        }
    }

    private fun encodeHeightmaps(heightmaps: NbtCompound): Map<HeightmapType, LongArray> = buildMap {
        heightmaps.forEachEntry { name, nbtTag ->
            val heightmapType = CLIENT_HEIGHTMAP_TYPES_BY_NAME[name] ?: return@forEachEntry
            val values = nbtTag as? NbtLongArray
                ?: throw IllegalArgumentException("Chunk heightmap $name is not a TAG_Long_Array")
            put(heightmapType, values.value)
        }
    }

    private fun encodeBlockEntities(
        chunkPosition: ChunkPosition,
        blockEntities: Collection<BlockEntity>,
    ): List<BlockEntityInfo> {
        if (blockEntities.isEmpty()) return emptyList()
        val blockEntityTypeProtocolRegistry = protocolRegistryContext.requireRegistry(BLOCK_ENTITY_TYPE_REGISTRY)
        return blockEntities.map { blockEntity ->
            val chunkBlockPosition = chunkPosition.local(blockEntity.blockPosition)
            val typeId = blockEntityTypeProtocolRegistry.entry(Identifier(blockEntity.type))?.rawId
                ?: throw IllegalArgumentException(
                    "Block entity type ${blockEntity.type} is absent from the active registry",
                )
            val nbtCompound = blockEntity.persistedData()
            BlockEntityInfo.fromLocalCoordinates(
                localX = chunkBlockPosition.x,
                y = blockEntity.blockPosition.y,
                localZ = chunkBlockPosition.z,
                typeId = typeId,
                tag = blockEntityUpdateTag(nbtCompound),
            )
        }
    }

    private fun encodeLight(
        minSectionY: Int,
        sectionCount: Int,
        sections: Map<Int, ChunkSection<ProtocolBlockState, ProtocolRegistryEntry>>,
        lightOnlySections: Map<Int, SectionLighting>,
    ): LightUpdateData {
        val bitCount = sectionCount + LIGHT_BOUNDARY_SECTION_COUNT
        val blockLight = LightAccumulator(bitCount)
        val skyLight = LightAccumulator(bitCount)
        val firstSectionY = MinecraftCoordinates.offsetSectionCoordinate(minSectionY, -1)
        repeat(bitCount) { bit ->
            val sectionY = MinecraftCoordinates.offsetSectionCoordinate(firstSectionY, bit)
            val chunkSection = sections[sectionY]
            val sectionLighting = lightOnlySections[sectionY]
            blockLight.add(bit, chunkSection?.blockLight ?: sectionLighting?.blockLight)
            if (hasSkyLight) skyLight.add(bit, chunkSection?.skyLight ?: sectionLighting?.skyLight)
        }
        return LightUpdateData(
            skyYMask = skyLight.updateMask(),
            blockYMask = blockLight.updateMask(),
            emptySkyYMask = skyLight.emptyMask(),
            emptyBlockYMask = blockLight.emptyMask(),
            skyUpdates = skyLight.updates,
            blockUpdates = blockLight.updates,
        )
    }

    private class LightAccumulator(bitCount: Int) {
        private val updateWords = LongArray((bitCount + Long.SIZE_BITS - 1) / Long.SIZE_BITS)
        private val emptyWords = LongArray(updateWords.size)
        val updates = mutableListOf<LightDataLayer>()

        fun add(bit: Int, bytes: NbtByteArray?) {
            if (bytes == null || (0 until bytes.size).all { index -> bytes[index] == 0.toByte() }) {
                set(emptyWords, bit)
            } else {
                set(updateWords, bit)
                updates += LightDataLayer(ByteString(bytes.value))
            }
        }

        fun updateMask(): BitSet = BitSet(updateWords)

        fun emptyMask(): BitSet = BitSet(emptyWords)

        private fun set(words: LongArray, bit: Int) {
            words[bit / Long.SIZE_BITS] = words[bit / Long.SIZE_BITS] or (1L shl (bit % Long.SIZE_BITS))
        }
    }

    companion object {
        val BLOCK_ENTITY_TYPE_REGISTRY: Identifier = Identifier("block_entity_type")

        private val CLIENT_HEIGHTMAP_TYPES = setOf(
            HeightmapType.WORLD_SURFACE,
            HeightmapType.MOTION_BLOCKING,
            HeightmapType.MOTION_BLOCKING_NO_LEAVES,
        )
        private val CLIENT_HEIGHTMAP_TYPES_BY_NAME = CLIENT_HEIGHTMAP_TYPES.associateBy(HeightmapType::name)
        private const val BLOCK_MINIMUM_INDIRECT_BITS: Int = 4
        private const val BLOCK_MAXIMUM_INDIRECT_BITS: Int = 8
        private const val BIOME_MINIMUM_INDIRECT_BITS: Int = 1
        private const val BIOME_MAXIMUM_INDIRECT_BITS: Int = 3
        private const val LIGHT_BOUNDARY_SECTION_COUNT: Int = 2
    }
}

/** Fluent projection from a strong world Chunk to an initial-world snapshot. */
fun Chunk<ProtocolBlockState, ProtocolRegistryEntry>.toMinecraftChunkSnapshot(
    minecraftChunkPacketEncoder: MinecraftChunkPacketEncoder,
): MinecraftChunkSnapshot = minecraftChunkPacketEncoder.encode(this)

/** Fluent projection from a strong world Chunk directly to its clientbound packet. */
fun Chunk<ProtocolBlockState, ProtocolRegistryEntry>.toChunkDataAndUpdateLightPacket(
    minecraftChunkPacketEncoder: MinecraftChunkPacketEncoder,
): ChunkDataAndUpdateLightPacket = minecraftChunkPacketEncoder.encodePacket(this)

/** Adapts an already-created packet to the server's initial-world snapshot without copying its payloads. */
fun ChunkDataAndUpdateLightPacket.toMinecraftChunkSnapshot(): MinecraftChunkSnapshot =
    MinecraftChunkSnapshot(
        chunkX = chunkX,
        chunkZ = chunkZ,
        chunkData = chunkData,
        lightUpdateData = lightData,
    )

private fun packValues(
    bitsPerEntry: Int,
    entryCount: Int,
    valueAt: (Int) -> Int,
): PackedLongArray {
    val entriesPerLong = Long.SIZE_BITS / bitsPerEntry
    val values = LongArray((entryCount + entriesPerLong - 1) / entriesPerLong)
    repeat(entryCount) { index ->
        val value = valueAt(index)
        val longIndex = index / entriesPerLong
        val bitIndex = index % entriesPerLong * bitsPerEntry
        values[longIndex] = values[longIndex] or (value.toLong() shl bitIndex)
    }
    return PackedLongArray(values)
}

private fun minimumBitsForDistinctValues(count: Int): Int {
    return if (count == 1) 0 else Int.SIZE_BITS - (count - 1).countLeadingZeroBits()
}

private fun defaultBlockEntityUpdateTag(nbtCompound: NbtCompound): NbtCompound? {
    val updateValues = nbtCompound.value - setOf("id", "x", "y", "z")
    return updateValues.takeIf { values -> values.isNotEmpty() }?.let(::NbtCompound)
}

private fun BlockEntity.persistedData(): NbtCompound {
    val value = linkedMapOf<String, NbtTag>()
    value["id"] = NbtString(type)
    value["x"] = NbtInt(blockPosition.x)
    value["y"] = NbtInt(blockPosition.y)
    value["z"] = NbtInt(blockPosition.z)
    persistentData.forEachEntry { name, nbtTag -> value[name] = nbtTag }
    return NbtCompound(value)
}
