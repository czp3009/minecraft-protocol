package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.BlockPosition
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

    private val biomeRegistry = registries.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)
    private val biomeRegistrySize = requireNotNull(registries.biomeRegistrySize) {
        "The active biome registry has no protocol size"
    }

    init {
        require(registries.blockStateRegistrySize > 0) { "The active block-state registry is empty" }
        require(biomeRegistrySize > 0) { "The active biome registry is empty" }
    }

    /** Encodes [chunk] directly into the packet accepted by the connection's outgoing channel. */
    fun encodePacket(
        chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>,
        position: ChunkPosition,
    ): ChunkDataAndUpdateLightPacket {
        require(chunk.defaultBlockState == chunkDataRegistries.blockStates.defaultValue) {
            "Chunk and packet encoder use different default block states"
        }
        require(chunk.defaultBiome == chunkDataRegistries.biomes.defaultValue) {
            "Chunk and packet encoder use different default biomes"
        }
        registries.chunkSectionCount?.let { sectionCount ->
            require(sectionCount == chunk.layout.sectionCount) {
                "Chunk has ${chunk.layout.sectionCount} Sections, but the active protocol context expects $sectionCount"
            }
        }

        val metadata = chunk.metadata
        val sectionsByY = chunk.sections.associateBy(ChunkSection<ProtocolBlockState, ProtocolRegistryEntry>::sectionY)
        val minimumLightSectionY = MinecraftCoordinates.offsetSectionCoordinate(chunk.layout.minSectionY, -1)
        val maximumLightSectionY = MinecraftCoordinates.offsetSectionCoordinate(chunk.layout.maxSectionY, 1)
        val relevantLightSections = minimumLightSectionY..maximumLightSectionY
        require(metadata.lightOnlySections.keys.all { sectionY -> sectionY in relevantLightSections }) {
            "Chunk contains light outside the protocol dimension's boundary Sections"
        }
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
            chunkX = position.x,
            chunkZ = position.z,
            chunkData = NetworkChunkData(
                heightmaps = encodeHeightmaps(metadata.heightmaps),
                sections = packetSections,
                blockEntities = encodeBlockEntities(position, metadata.blockEntities.value),
            ),
            lightData = packetLight,
        )
    }

    /** Encodes [chunk] into the server's initial-world snapshot value. */
    fun encode(
        chunk: Chunk<ProtocolBlockState, ProtocolRegistryEntry>,
        position: ChunkPosition,
    ): MinecraftChunkSnapshot = encodePacket(chunk, position).toMinecraftChunkSnapshot()

    private fun encodeSection(
        section: ChunkSection<ProtocolBlockState, ProtocolRegistryEntry>?,
        defaultBlockState: ProtocolBlockState,
        defaultBiome: ProtocolRegistryEntry,
    ): NetworkChunkSection {
        if (section == null) {
            return NetworkChunkSection(
                nonAirBlockCount = if (isAir(defaultBlockState)) 0 else SECTION_BLOCK_COUNT,
                fluidCount = if (hasFluid(defaultBlockState)) SECTION_BLOCK_COUNT else 0,
                blockStates = NetworkPalettedContainer.Single(blockStateId(defaultBlockState)),
                biomes = NetworkPalettedContainer.Single(biomeId(defaultBiome)),
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
                id = ::blockStateId,
            ),
            biomes = encodePalette(
                container = section.biomes,
                registrySize = biomeRegistrySize,
                minimumIndirectBits = BIOME_MINIMUM_INDIRECT_BITS,
                maximumIndirectBits = BIOME_MAXIMUM_INDIRECT_BITS,
                id = ::biomeId,
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
            require(bits > maximumIndirectBits) {
                "A direct palette needs more than $maximumIndirectBits bits, but the registry size is $registrySize"
            }
            NetworkPalettedContainer.Direct(
                packValues(bits, compact.entryCount) { index -> registryIds[compact.ids[index]] },
            )
        }
    }

    private fun blockStateId(value: ProtocolBlockState): Int {
        require(registries.blockStates.getOrNull(value.id) == value) {
            "Block state $value does not belong to the packet encoder's registry context"
        }
        return value.id
    }

    private fun biomeId(value: ProtocolRegistryEntry): Int {
        require(biomeRegistry[value.rawId] == value) {
            "Biome $value does not belong to the packet encoder's registry context"
        }
        return value.rawId
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
        blockEntities: List<com.hiczp.minecraft.nbt.NbtTag>,
    ): List<BlockEntityInfo> {
        if (blockEntities.isEmpty()) return emptyList()
        val blockEntityTypeRegistry = registries.requireRegistry(BLOCK_ENTITY_TYPE_REGISTRY)
        return blockEntities.mapIndexed { index, tag ->
            val compound = tag as? NbtCompound
                ?: throw IllegalArgumentException("Chunk block entity $index is not a TAG_Compound")
            val id = (compound[BLOCK_ENTITY_ID] as? NbtString)?.value
                ?: throw IllegalArgumentException("Chunk block entity $index has no string id")
            val x = (compound[BLOCK_ENTITY_X] as? NbtInt)?.value
                ?: throw IllegalArgumentException("Chunk block entity $index has no integer x")
            val y = (compound[BLOCK_ENTITY_Y] as? NbtInt)?.value
                ?: throw IllegalArgumentException("Chunk block entity $index has no integer y")
            val z = (compound[BLOCK_ENTITY_Z] as? NbtInt)?.value
                ?: throw IllegalArgumentException("Chunk block entity $index has no integer z")
            val local = chunkPosition.local(BlockPosition(x, y, z))
            val typeId = blockEntityTypeRegistry.entry(Identifier(id))?.rawId
                ?: throw IllegalArgumentException("Block entity type $id is absent from the active registry")
            BlockEntityInfo.fromLocalCoordinates(
                localX = local.x,
                y = y,
                localZ = local.z,
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
            if (bytes == null || isZero(bytes)) {
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

        private fun isZero(bytes: NbtByteArray): Boolean {
            var zero = true
            bytes.forEach { value -> if (value != 0.toByte()) zero = false }
            return zero
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
        private const val BLOCK_ENTITY_ID: String = "id"
        private const val BLOCK_ENTITY_X: String = "x"
        private const val BLOCK_ENTITY_Y: String = "y"
        private const val BLOCK_ENTITY_Z: String = "z"
        private const val BLOCK_MINIMUM_INDIRECT_BITS: Int = 4
        private const val BLOCK_MAXIMUM_INDIRECT_BITS: Int = 8
        private const val BIOME_MINIMUM_INDIRECT_BITS: Int = 1
        private const val BIOME_MAXIMUM_INDIRECT_BITS: Int = 3
        private const val LIGHT_BOUNDARY_SECTION_COUNT: Int = 2
    }
}

/** Fluent projection from a strong world Chunk to an initial-world snapshot. */
fun Chunk<ProtocolBlockState, ProtocolRegistryEntry>.toMinecraftChunkSnapshot(
    position: ChunkPosition,
    encoder: MinecraftChunkPacketEncoder,
): MinecraftChunkSnapshot = encoder.encode(this, position)

/** Fluent projection from a strong world Chunk directly to its clientbound packet. */
fun Chunk<ProtocolBlockState, ProtocolRegistryEntry>.toChunkDataAndUpdateLightPacket(
    position: ChunkPosition,
    encoder: MinecraftChunkPacketEncoder,
): ChunkDataAndUpdateLightPacket = encoder.encodePacket(this, position)

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
    require(bitsPerEntry in 1..<Int.SIZE_BITS)
    require(entryCount >= 0)
    val entriesPerLong = Long.SIZE_BITS / bitsPerEntry
    val values = LongArray((entryCount + entriesPerLong - 1) / entriesPerLong)
    val maximumValue = (1L shl bitsPerEntry) - 1
    repeat(entryCount) { index ->
        val value = valueAt(index)
        require(value >= 0 && value.toLong() <= maximumValue) {
            "Packed value $value does not fit in $bitsPerEntry bits"
        }
        val longIndex = index / entriesPerLong
        val bitIndex = index % entriesPerLong * bitsPerEntry
        values[longIndex] = values[longIndex] or (value.toLong() shl bitIndex)
    }
    return PackedLongArray(values)
}

private fun minimumBitsForDistinctValues(count: Int): Int {
    require(count > 0)
    return if (count == 1) 0 else Int.SIZE_BITS - (count - 1).countLeadingZeroBits()
}

private fun defaultBlockEntityUpdateTag(compound: NbtCompound): NbtCompound? {
    val updateValues = compound.value - setOf("id", "x", "y", "z")
    return updateValues.takeIf { values -> values.isNotEmpty() }?.let(::NbtCompound)
}
