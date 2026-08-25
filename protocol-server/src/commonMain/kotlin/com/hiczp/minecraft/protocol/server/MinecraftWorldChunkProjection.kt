package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.protocol.model.type.ChunkData as NetworkChunkData
import com.hiczp.minecraft.protocol.model.type.ChunkSection as NetworkChunkSection
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as NetworkPalettedContainer

/** Converts one configured dimension layout into the corresponding semantic world-Chunk layout. */
fun MinecraftDimensionLayout.toChunkLayout(): ChunkLayout = ChunkLayout(
    minSectionY = MinecraftCoordinates.sectionCoordinate(minY),
    sectionCount = sectionCount,
)

/**
 * Stateless projection of strong world Chunks into clientbound Chunk packets.
 *
 * [isAir] and [hasFluid] come from the caller's selected-release vanilla or mod block data. They cannot be inferred
 * from registry IDs alone. The encoder is immutable and can be shared by every Chunk sent with [registries].
 */
class MinecraftChunkPacketEncoder(
    val registries: ProtocolRegistryContext,
    private val isAir: (ProtocolBlockState) -> Boolean,
    private val hasFluid: (ProtocolBlockState) -> Boolean,
    private val hasSkyLight: Boolean,
    defaultBlock: Identifier = Identifier("air"),
    defaultBiome: Identifier = Identifier("plains"),
    private val blockEntityUpdateTag: (NbtCompound) -> NbtCompound? = ::defaultBlockEntityUpdateTag,
) {
    val chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
        protocolChunkDataRegistries(registries, defaultBlock, defaultBiome)

    private val biomeRegistrySize = registries.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY).size

    /** Encodes [chunk] directly into the packet accepted by the connection's outgoing channel. */
    fun encodePacket(chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>): ChunkDataAndUpdateLightPacket {
        val metadata = chunk.metadata
        val sectionsByY = chunk.sections.associateBy(ChunkSection<ProtocolBlockState, ProtocolRegistryEntry>::sectionY)
        val packetSections = chunk.layout.sectionYRange.map { sectionY ->
            encodeSection(sectionsByY[sectionY], chunk.defaultBlockState, chunk.defaultBiome)
        }
        val packetLight = encodeLight(
            chunk.layout.minSectionY,
            chunk.layout.sectionCount,
            sectionsByY,
            metadata.lightOnlySections,
        )
        return ChunkDataAndUpdateLightPacket(
            chunkX = chunk.position.x,
            chunkZ = chunk.position.z,
            chunkData = NetworkChunkData(
                heightmaps = encodeHeightmaps(metadata.heightmaps),
                sections = packetSections,
                blockEntities = encodeBlockEntities(chunk.position, chunk.blockEntities),
            ),
            lightData = packetLight,
        )
    }

    /** Encodes [chunk] into the server's initial-world snapshot value. */
    fun encode(chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>): MinecraftChunkSnapshot =
        encodePacket(chunk).toMinecraftChunkSnapshot()

    private fun encodeSection(
        section: ChunkSection<ProtocolBlockState, ProtocolRegistryEntry>?,
        defaultBlockState: ProtocolBlockState,
        defaultBiome: ProtocolRegistryEntry,
    ): NetworkChunkSection {
        if (section == null) {
            return NetworkChunkSection(
                nonAirBlockCount = if (isAir(defaultBlockState)) 0 else SECTION_BLOCK_COUNT,
                fluidCount = if (hasFluid(defaultBlockState)) SECTION_BLOCK_COUNT else 0,
                blockStates = NetworkPalettedContainer.Single(defaultBlockState.id),
                biomes = NetworkPalettedContainer.Single(defaultBiome.rawId),
            )
        }

        var nonAirBlockCount = 0
        var fluidCount = 0
        section.blockStates.forEach { blockState ->
            if (!isAir(blockState)) nonAirBlockCount++
            if (hasFluid(blockState)) fluidCount++
        }
        return NetworkChunkSection(
            nonAirBlockCount = nonAirBlockCount,
            fluidCount = fluidCount,
            blockStates = encodePalette(
                container = section.blockStates,
                registrySize = registries.blockStateRegistrySize,
                minimumIndirectBits = BLOCK_MINIMUM_INDIRECT_BITS,
                maximumIndirectBits = BLOCK_MAXIMUM_INDIRECT_BITS,
                id = ProtocolBlockState::id,
            ),
            biomes = encodePalette(
                container = section.biomes,
                registrySize = biomeRegistrySize,
                minimumIndirectBits = BIOME_MINIMUM_INDIRECT_BITS,
                maximumIndirectBits = BIOME_MAXIMUM_INDIRECT_BITS,
                id = ProtocolRegistryEntry::rawId,
            ),
        )
    }

    private fun <T : Any> encodePalette(
        container: com.hiczp.minecraft.world.format.PalettedContainer<T>,
        registrySize: Int,
        minimumIndirectBits: Int,
        maximumIndirectBits: Int,
        id: (T) -> Int,
    ): NetworkPalettedContainer {
        val compact = container.compactSnapshot()
        val registryIds = compact.values.map(id)
        if (registryIds.size == 1) return NetworkPalettedContainer.Single(registryIds.single())

        val logicalBits = minimumBitsForDistinctValues(registryIds.size)
        return if (logicalBits <= maximumIndirectBits) {
            val bits = maxOf(minimumIndirectBits, logicalBits)
            NetworkPalettedContainer.Indirect(
                bitsPerEntry = bits,
                palette = registryIds,
                data = packValues(bits, compact.entryCount) { index -> compact.ids[index] },
            )
        } else {
            val bits = minimumBitsForDistinctValues(registrySize)
            NetworkPalettedContainer.Direct(
                packValues(bits, compact.entryCount) { index -> registryIds[compact.ids[index]] },
            )
        }
    }

    private fun encodeHeightmaps(heightmaps: NbtCompound): Map<HeightmapType, LongArray> = buildMap {
        heightmaps.forEachEntry { name, tag ->
            val type = CLIENT_HEIGHTMAP_TYPES_BY_NAME[name] ?: return@forEachEntry
            val values = tag as? NbtLongArray
                ?: throw IllegalArgumentException("Chunk heightmap $name is not a TAG_Long_Array")
            put(type, values.value)
        }
    }

    private fun encodeBlockEntities(
        chunkPosition: ChunkPosition,
        blockEntities: Collection<BlockEntity>,
    ): List<BlockEntityInfo> {
        if (blockEntities.isEmpty()) return emptyList()
        val blockEntityTypeRegistry = registries.requireRegistry(BLOCK_ENTITY_TYPE_REGISTRY)
        return blockEntities.map { blockEntity ->
            val localPosition = chunkPosition.local(blockEntity.position)
            val typeId = blockEntityTypeRegistry.entry(Identifier(blockEntity.type))?.rawId
                ?: throw IllegalArgumentException(
                    "Block entity type ${blockEntity.type} is absent from the active registry",
                )
            val compound = blockEntity.persistedData()
            BlockEntityInfo.fromLocalCoordinates(
                localX = localPosition.x,
                y = blockEntity.position.y,
                localZ = localPosition.z,
                typeId = typeId,
                tag = blockEntityUpdateTag(compound),
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
            val section = sections[sectionY]
            val lighting = lightOnlySections[sectionY]
            blockLight.add(bit, section?.blockLight ?: lighting?.blockLight)
            if (hasSkyLight) skyLight.add(bit, section?.skyLight ?: lighting?.skyLight)
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
    encoder: MinecraftChunkPacketEncoder,
): MinecraftChunkSnapshot = encoder.encode(this)

/** Fluent projection from a strong world Chunk directly to its clientbound packet. */
fun Chunk<ProtocolBlockState, ProtocolRegistryEntry>.toChunkDataAndUpdateLightPacket(
    encoder: MinecraftChunkPacketEncoder,
): ChunkDataAndUpdateLightPacket = encoder.encodePacket(this)

/** Adapts an already-created packet to the server's initial-world snapshot without copying its payloads. */
fun ChunkDataAndUpdateLightPacket.toMinecraftChunkSnapshot(): MinecraftChunkSnapshot =
    MinecraftChunkSnapshot(
        chunkX = chunkX,
        chunkZ = chunkZ,
        chunkData = chunkData,
        lightData = lightData,
    )

private fun protocolChunkDataRegistries(
    registries: ProtocolRegistryContext,
    defaultBlock: Identifier,
    defaultBiome: Identifier,
): ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
    ChunkDataRegistries(
        blockStates = object : BlockStateRegistry<ProtocolBlockState> {
            override val defaultValue = registries.requireDefaultBlockState(defaultBlock)

            override fun resolve(descriptor: BlockStateDescriptor): ProtocolBlockState? =
                descriptor.identifierOrNull()?.let { block -> registries.blockState(block, descriptor.properties) }

            override fun describe(value: ProtocolBlockState): BlockStateDescriptor? =
                value.takeIf { state -> registries.blockStates.getOrNull(state.id) == state }
                    ?.let { state -> BlockStateDescriptor(state.block.value, state.properties) }
        },
        biomes = object : BiomeRegistry<ProtocolRegistryEntry> {
            private val registry = registries.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)

            override val defaultValue = registries.requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                defaultBiome,
            )

            override fun resolve(name: String): ProtocolRegistryEntry? =
                identifierOrNull(name)?.let(registry::entry)

            override fun name(value: ProtocolRegistryEntry): String? =
                value.takeIf { entry -> registry[entry.rawId] == entry }?.id?.value
        },
    )

private fun BlockStateDescriptor.identifierOrNull(): Identifier? = identifierOrNull(name)

private fun identifierOrNull(value: String): Identifier? = try {
    Identifier(value)
} catch (_: IllegalArgumentException) {
    null
}

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

private fun defaultBlockEntityUpdateTag(compound: NbtCompound): NbtCompound? {
    val updateValues = compound.value - setOf("id", "x", "y", "z")
    return updateValues.takeIf { values -> values.isNotEmpty() }?.let(::NbtCompound)
}

private fun BlockEntity.persistedData(): NbtCompound {
    val value = linkedMapOf<String, NbtTag>()
    value["id"] = NbtString(type)
    value["x"] = NbtInt(position.x)
    value["y"] = NbtInt(position.y)
    value["z"] = NbtInt(position.z)
    persistentData.forEachEntry { name, tag -> value[name] = tag }
    return NbtCompound(value)
}
