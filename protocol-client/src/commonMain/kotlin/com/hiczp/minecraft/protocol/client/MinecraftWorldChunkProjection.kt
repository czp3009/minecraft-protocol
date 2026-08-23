package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtLongArray
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.world.format.PalettedContainer
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as NetworkPalettedContainer

/**
 * Converts an installed client registry context into the registries needed by strong world-Chunk NBT decoding.
 *
 * Call this after Configuration and Play Login have installed the active context. Loader-resolved block states,
 * aliases, raw IDs, and synchronized biomes are retained by reference.
 */
fun ProtocolRegistryContext.toChunkDataRegistries(
    defaultBlock: Identifier = Identifier("air"),
    defaultBiome: Identifier = Identifier("plains"),
): ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
    protocolChunkDataRegistries(this, defaultBlock, defaultBiome)

/** Convenience access to [toChunkDataRegistries] through the client's currently installed registry context. */
fun MinecraftClientConnection.chunkDataRegistries(
    defaultBlock: Identifier = Identifier("air"),
    defaultBiome: Identifier = Identifier("plains"),
): ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
    registries.toChunkDataRegistries(defaultBlock, defaultBiome)

/**
 * Stateless decoding of clientbound Chunk packets into positioned strong world Chunks.
 *
 * The packet does not carry a world data version, generation status, inhabited time, or other persistence-only fields;
 * [metadata] supplies those values. Packet heightmaps, block entities, and light replace the corresponding template
 * fields on each decoded Chunk. This decoder is immutable and can be shared across the active dimension.
 */
class MinecraftChunkPacketDecoder(
    val registries: ProtocolRegistryContext,
    val layout: ChunkLayout,
    private val metadata: ChunkMetadata,
    defaultBlock: Identifier = Identifier("air"),
    defaultBiome: Identifier = Identifier("plains"),
) {
    val chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
        registries.toChunkDataRegistries(defaultBlock, defaultBiome)

    private val biomeRegistry = registries.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)
    private val biomeRegistrySize = requireNotNull(registries.biomeRegistrySize) {
        "The active biome registry has no protocol size"
    }

    init {
        require(registries.blockStateRegistrySize > 0) { "The active block-state registry is empty" }
        require(biomeRegistrySize > 0) { "The active biome registry is empty" }
        registries.chunkSectionCount?.let { sectionCount ->
            require(sectionCount == layout.sectionCount) {
                val actual = layout.sectionCount
                "Chunk layout has $actual Sections, but the active protocol context expects $sectionCount"
            }
        }
    }

    /** Decodes one packet while retaining its x/z coordinates in the resulting Chunk. */
    fun decode(packet: ChunkDataAndUpdateLightPacket): Chunk<ProtocolBlockState, ProtocolRegistryEntry> {
        require(packet.chunkData.sections.size == layout.sectionCount) {
            "Chunk packet has ${packet.chunkData.sections.size} Sections, expected ${layout.sectionCount}"
        }
        val blockLight = decodeLightLayers(
            updateMask = packet.lightData.blockYMask,
            emptyMask = packet.lightData.emptyBlockYMask,
            updates = packet.lightData.blockUpdates.map { update -> update.bytes.toByteArray() },
            name = "block",
        )
        val skyLight = decodeLightLayers(
            updateMask = packet.lightData.skyYMask,
            emptyMask = packet.lightData.emptySkyYMask,
            updates = packet.lightData.skyUpdates.map { update -> update.bytes.toByteArray() },
            name = "sky",
        )
        val sections = packet.chunkData.sections.mapIndexed { index, section ->
            val sectionY = MinecraftCoordinates.offsetSectionCoordinate(layout.minSectionY, index)
            ChunkSection(
                sectionY = sectionY,
                blockStates = decodePalette(
                    value = section.blockStates,
                    entryCount = com.hiczp.minecraft.protocol.model.type.ChunkSection.BLOCK_COUNT,
                    registrySize = registries.blockStateRegistrySize,
                    minimumIndirectBits = BLOCK_MINIMUM_INDIRECT_BITS,
                    maximumIndirectBits = BLOCK_MAXIMUM_INDIRECT_BITS,
                    valueAt = ::blockState,
                    kind = "block-state",
                ),
                biomes = decodePalette(
                    value = section.biomes,
                    entryCount = com.hiczp.minecraft.protocol.model.type.ChunkSection.BIOME_COUNT,
                    registrySize = biomeRegistrySize,
                    minimumIndirectBits = BIOME_MINIMUM_INDIRECT_BITS,
                    maximumIndirectBits = BIOME_MAXIMUM_INDIRECT_BITS,
                    valueAt = ::biome,
                    kind = "biome",
                ),
                blockLight = blockLight[sectionY],
                skyLight = skyLight[sectionY],
            )
        }
        val lightOnlySections = buildMap {
            (blockLight.keys + skyLight.keys).forEach { sectionY ->
                if (sectionY !in layout) {
                    put(
                        sectionY,
                        SectionLighting(
                            blockLight = blockLight[sectionY],
                            skyLight = skyLight[sectionY],
                        ),
                    )
                }
            }
        }
        val decodedMetadata = metadata.copy(
            lightCorrect = true,
            heightmaps = NbtCompound(
                packet.chunkData.heightmaps.mapKeys { (type, _) -> type.name }
                    .mapValues { (_, values) -> NbtLongArray(values) },
            ),
            lightOnlySections = lightOnlySections,
        )
        return Chunk(
            position = packet.chunkPosition,
            metadata = decodedMetadata,
            layout = layout,
            sections = sections,
            blockEntities = packet.chunkData.blockEntities.map { info ->
                decodeBlockEntity(packet.chunkPosition, info)
            },
            defaultBlockState = chunkDataRegistries.blockStates.defaultValue,
            defaultBiome = chunkDataRegistries.biomes.defaultValue,
        )
    }

    private fun blockState(id: Int): ProtocolBlockState = registries.blockStates.getOrNull(id)
        ?: throw IllegalArgumentException(
            "Block-state registry ID $id is outside 0 until ${registries.blockStateRegistrySize}",
        )

    private fun biome(id: Int): ProtocolRegistryEntry = biomeRegistry[id]
        ?: throw IllegalArgumentException("Biome registry ID $id has no installed entry")

    private fun <T : Any> decodePalette(
        value: NetworkPalettedContainer,
        entryCount: Int,
        registrySize: Int,
        minimumIndirectBits: Int,
        maximumIndirectBits: Int,
        valueAt: (Int) -> T,
        kind: String,
    ): PalettedContainer<T> = when (value) {
        is NetworkPalettedContainer.Single -> PalettedContainer(entryCount, valueAt(value.valueId))

        is NetworkPalettedContainer.Indirect -> {
            require(value.bitsPerEntry in minimumIndirectBits..maximumIndirectBits) {
                val bits = value.bitsPerEntry
                "$kind indirect palette uses $bits bits, expected $minimumIndirectBits..$maximumIndirectBits"
            }
            require(value.palette.size <= 1.shl(value.bitsPerEntry)) {
                "$kind indirect palette has too many values"
            }
            val palette = value.palette.map(valueAt)
            val ids = unpackValues(value.data, value.bitsPerEntry, entryCount)
            require(ids.all { id -> id in palette.indices }) { "$kind palette data contains an invalid local ID" }
            PalettedContainer.fromPalette(palette, ids)
        }

        is NetworkPalettedContainer.Direct -> {
            val bits = minimumBitsForDistinctValues(registrySize)
            require(bits > maximumIndirectBits) {
                "A direct $kind palette is invalid for registry size $registrySize"
            }
            val registryIds = unpackValues(value.data, bits, entryCount)
            val palette = mutableListOf<T>()
            val ids = IntArray(entryCount)
            registryIds.forEachIndexed { index, registryId ->
                val resolved = valueAt(registryId)
                var paletteId = palette.indexOf(resolved)
                if (paletteId < 0) {
                    palette += resolved
                    paletteId = palette.lastIndex
                }
                ids[index] = paletteId
            }
            PalettedContainer.fromPalette(palette, ids)
        }
    }

    private fun decodeLightLayers(
        updateMask: BitSet,
        emptyMask: BitSet,
        updates: List<ByteArray>,
        name: String,
    ): Map<Int, com.hiczp.minecraft.nbt.NbtByteArray> {
        val bitCount = layout.sectionCount + LIGHT_BOUNDARY_SECTION_COUNT
        requireNoBitsOutside(updateMask, bitCount, "$name update")
        requireNoBitsOutside(emptyMask, bitCount, "$name empty")
        val result = linkedMapOf<Int, com.hiczp.minecraft.nbt.NbtByteArray>()
        var updateIndex = 0
        val firstSectionY = MinecraftCoordinates.offsetSectionCoordinate(layout.minSectionY, -1)
        repeat(bitCount) { bit ->
            require(!(updateMask[bit] && emptyMask[bit])) { "$name light bit $bit is both updated and empty" }
            if (updateMask[bit]) {
                val bytes = updates.getOrNull(updateIndex++)
                    ?: throw IllegalArgumentException("$name light update mask contains more bits than payloads")
                require(bytes.size == com.hiczp.minecraft.world.format.SECTION_LIGHT_BYTE_COUNT) {
                    "$name light update has ${bytes.size} bytes"
                }
                val sectionY = MinecraftCoordinates.offsetSectionCoordinate(firstSectionY, bit)
                result[sectionY] = com.hiczp.minecraft.nbt.NbtByteArray(bytes)
            }
        }
        require(updateIndex == updates.size) { "$name light packet has more payloads than update-mask bits" }
        return result
    }

    private fun decodeBlockEntity(chunkPosition: ChunkPosition, info: BlockEntityInfo): BlockEntity {
        val type = registries.requireRegistry(BLOCK_ENTITY_TYPE_REGISTRY)[info.typeId]
            ?: throw IllegalArgumentException("Block-entity type registry ID ${info.typeId} has no installed entry")
        val values = linkedMapOf<String, NbtTag>()
        info.tag?.forEachEntry { name, tag ->
            if (name !in BLOCK_ENTITY_STRUCTURE_FIELDS) values[name] = tag
        }
        return BlockEntity(
            type = type.id.value,
            position = chunkPosition.block(ChunkBlockPosition(info.localX, info.y.toInt(), info.localZ)),
            persistentData = NbtCompound(values),
        )
    }

    private fun requireNoBitsOutside(mask: BitSet, bitCount: Int, name: String) {
        for (bit in bitCount until mask.words.size * Long.SIZE_BITS) {
            require(!mask[bit]) { "$name light mask contains out-of-range bit $bit" }
        }
    }

    companion object {
        val BLOCK_ENTITY_TYPE_REGISTRY: Identifier = Identifier("block_entity_type")

        private val BLOCK_ENTITY_STRUCTURE_FIELDS = setOf("id", "x", "y", "z")
        private const val BLOCK_MINIMUM_INDIRECT_BITS: Int = 4
        private const val BLOCK_MAXIMUM_INDIRECT_BITS: Int = 8
        private const val BIOME_MINIMUM_INDIRECT_BITS: Int = 1
        private const val BIOME_MAXIMUM_INDIRECT_BITS: Int = 3
        private const val LIGHT_BOUNDARY_SECTION_COUNT: Int = 2
    }
}

/** Absolute world Chunk coordinates carried by this packet. */
val ChunkDataAndUpdateLightPacket.chunkPosition: ChunkPosition
    get() = ChunkPosition(chunkX, chunkZ)

/** Fluent clientbound packet to strong world-Chunk conversion. */
fun ChunkDataAndUpdateLightPacket.toChunk(
    decoder: MinecraftChunkPacketDecoder,
): Chunk<ProtocolBlockState, ProtocolRegistryEntry> = decoder.decode(this)

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

private fun unpackValues(data: PackedLongArray, bitsPerEntry: Int, entryCount: Int): IntArray {
    require(bitsPerEntry in 1..<Int.SIZE_BITS)
    val entriesPerLong = Long.SIZE_BITS / bitsPerEntry
    val expectedSize = (entryCount + entriesPerLong - 1) / entriesPerLong
    require(data.size == expectedSize) {
        "Packed palette has ${data.size} Longs, expected $expectedSize"
    }
    val mask = (1L shl bitsPerEntry) - 1
    return IntArray(entryCount) { index ->
        val longIndex = index / entriesPerLong
        val bitIndex = index % entriesPerLong * bitsPerEntry
        (data[longIndex] ushr bitIndex and mask).toInt()
    }
}

private fun minimumBitsForDistinctValues(count: Int): Int {
    require(count > 0)
    return if (count == 1) 0 else Int.SIZE_BITS - (count - 1).countLeadingZeroBits()
}
