package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtLongArray
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.datapack.MinecraftChunkContext
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.world.format.PalettedContainer
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as NetworkPalettedContainer

fun MinecraftChunkContext.packetDecoder(chunkMetadata: ChunkMetadata): MinecraftChunkPacketDecoder =
    MinecraftChunkPacketDecoder(this, chunkMetadata)

/**
 * Stateless decoding of clientbound Chunk packets into positioned strong world Chunks.
 *
 * The packet does not carry a world data version, generation status, inhabited time, or other persistence-only fields;
 * [chunkMetadata] supplies those values. Packet heightmaps, block entities, and light replace the corresponding template
 * fields on each decoded Chunk. This decoder is immutable and can be shared across the active dimension.
 */
class MinecraftChunkPacketDecoder(
    val protocolRegistryContext: ProtocolRegistryContext,
    val chunkCodecContext: ChunkCodecContext<ProtocolBlockState, ProtocolRegistryEntry>,
    private val chunkMetadata: ChunkMetadata,
) {
    constructor(
        minecraftChunkContext: MinecraftChunkContext,
        chunkMetadata: ChunkMetadata,
    ) : this(
        protocolRegistryContext = minecraftChunkContext.protocolRegistryContext,
        chunkCodecContext = minecraftChunkContext.chunkCodecContext,
        chunkMetadata = chunkMetadata,
    )

    val chunkLayout: ChunkLayout = chunkCodecContext.chunkLayout

    val chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
        chunkCodecContext.chunkDataRegistries

    private val biomeProtocolRegistry = protocolRegistryContext.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)
    private val biomeRegistrySize = requireNotNull(protocolRegistryContext.biomeRegistrySize) {
        "The active biome registry has no protocol size"
    }

    init {
        require(protocolRegistryContext.blockStateRegistrySize > 0) { "The active block-state registry is empty" }
        require(biomeRegistrySize > 0) { "The active biome registry is empty" }
        require(protocolRegistryContext.chunkSectionCount == chunkLayout.sectionCount) {
            val sectionCount = protocolRegistryContext.chunkSectionCount
            "Chunk layout has ${chunkLayout.sectionCount} Sections, but the protocol context has $sectionCount"
        }
    }

    /** Decodes one packet while retaining its x/z coordinates in the resulting Chunk. */
    fun decode(chunkDataAndUpdateLightPacket: ChunkDataAndUpdateLightPacket): Chunk<ProtocolBlockState, ProtocolRegistryEntry> {
        require(chunkDataAndUpdateLightPacket.chunkData.sections.size == chunkLayout.sectionCount) {
            "Chunk packet has ${chunkDataAndUpdateLightPacket.chunkData.sections.size} Sections, expected ${chunkLayout.sectionCount}"
        }
        val blockLight = decodeLightLayers(
            updateMask = chunkDataAndUpdateLightPacket.lightData.blockYMask,
            emptyMask = chunkDataAndUpdateLightPacket.lightData.emptyBlockYMask,
            updates = chunkDataAndUpdateLightPacket.lightData.blockUpdates.map { lightDataLayer -> lightDataLayer.bytes.toByteArray() },
            name = "block",
        )
        val skyLight = decodeLightLayers(
            updateMask = chunkDataAndUpdateLightPacket.lightData.skyYMask,
            emptyMask = chunkDataAndUpdateLightPacket.lightData.emptySkyYMask,
            updates = chunkDataAndUpdateLightPacket.lightData.skyUpdates.map { lightDataLayer -> lightDataLayer.bytes.toByteArray() },
            name = "sky",
        )
        val sections = chunkDataAndUpdateLightPacket.chunkData.sections.mapIndexed { index, chunkSection ->
            val sectionY = MinecraftCoordinates.offsetSectionCoordinate(chunkLayout.minSectionY, index)
            ChunkSection(
                sectionY = sectionY,
                blockStates = decodePalette(
                    networkPalettedContainer = chunkSection.blockStates,
                    entryCount = com.hiczp.minecraft.protocol.model.type.ChunkSection.BLOCK_COUNT,
                    registrySize = protocolRegistryContext.blockStateRegistrySize,
                    minimumIndirectBits = BLOCK_MINIMUM_INDIRECT_BITS,
                    maximumIndirectBits = BLOCK_MAXIMUM_INDIRECT_BITS,
                    valueAt = ::blockState,
                    kind = "block-state",
                ),
                biomes = decodePalette(
                    networkPalettedContainer = chunkSection.biomes,
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
                if (sectionY !in chunkLayout) {
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
        val decodedMetadata = chunkMetadata.copy(
            lightCorrect = true,
            heightmaps = NbtCompound(
                chunkDataAndUpdateLightPacket.chunkData.heightmaps.mapKeys { (heightmapType, _) -> heightmapType.name }
                    .mapValues { (_, values) -> NbtLongArray(values) },
            ),
            lightOnlySections = lightOnlySections,
        )
        return Chunk(
            chunkPosition = chunkDataAndUpdateLightPacket.chunkPosition,
            chunkMetadata = decodedMetadata,
            chunkLayout = chunkLayout,
            sections = sections,
            blockEntities = chunkDataAndUpdateLightPacket.chunkData.blockEntities.map { blockEntityInfo ->
                decodeBlockEntity(chunkDataAndUpdateLightPacket.chunkPosition, blockEntityInfo)
            },
            defaultBlockState = chunkDataRegistries.blockStates.defaultValue,
            defaultBiome = chunkDataRegistries.biomes.defaultValue,
        )
    }

    private fun blockState(id: Int): ProtocolBlockState = protocolRegistryContext.blockStates.getOrNull(id)
        ?: throw IllegalArgumentException(
            "Block-state registry ID $id is outside 0 until ${protocolRegistryContext.blockStateRegistrySize}",
        )

    private fun biome(id: Int): ProtocolRegistryEntry = biomeProtocolRegistry[id]
        ?: throw IllegalArgumentException("Biome registry ID $id has no installed entry")

    private fun <T : Any> decodePalette(
        networkPalettedContainer: NetworkPalettedContainer,
        entryCount: Int,
        registrySize: Int,
        minimumIndirectBits: Int,
        maximumIndirectBits: Int,
        valueAt: (Int) -> T,
        kind: String,
    ): PalettedContainer<T> = when (networkPalettedContainer) {
        is NetworkPalettedContainer.Single -> PalettedContainer(entryCount, valueAt(networkPalettedContainer.valueId))

        is NetworkPalettedContainer.Indirect -> {
            require(networkPalettedContainer.bitsPerEntry in minimumIndirectBits..maximumIndirectBits) {
                val bits = networkPalettedContainer.bitsPerEntry
                "$kind indirect palette uses $bits bits, expected $minimumIndirectBits..$maximumIndirectBits"
            }
            require(networkPalettedContainer.palette.size <= 1.shl(networkPalettedContainer.bitsPerEntry)) {
                "$kind indirect palette has too many values"
            }
            val palette = networkPalettedContainer.palette.map(valueAt)
            val ids = unpackValues(networkPalettedContainer.data, networkPalettedContainer.bitsPerEntry, entryCount)
            require(ids.all { id -> id in palette.indices }) { "$kind palette data contains an invalid local ID" }
            PalettedContainer.fromPalette(palette, ids)
        }

        is NetworkPalettedContainer.Direct -> {
            val bits = minimumBitsForDistinctValues(registrySize)
            require(bits > maximumIndirectBits) {
                "A direct $kind palette is invalid for registry size $registrySize"
            }
            val registryIds = unpackValues(networkPalettedContainer.data, bits, entryCount)
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
        val bitCount = chunkLayout.sectionCount + LIGHT_BOUNDARY_SECTION_COUNT
        requireNoBitsOutside(updateMask, bitCount, "$name update")
        requireNoBitsOutside(emptyMask, bitCount, "$name empty")
        val result = linkedMapOf<Int, com.hiczp.minecraft.nbt.NbtByteArray>()
        var updateIndex = 0
        val firstSectionY = MinecraftCoordinates.offsetSectionCoordinate(chunkLayout.minSectionY, -1)
        repeat(bitCount) { bit ->
            require(!(updateMask[bit] && emptyMask[bit])) { "$name light bit $bit is both updated and empty" }
            if (updateMask[bit]) {
                val byteArray = updates.getOrNull(updateIndex++)
                    ?: throw IllegalArgumentException("$name light update mask contains more bits than payloads")
                require(byteArray.size == com.hiczp.minecraft.world.format.SECTION_LIGHT_BYTE_COUNT) {
                    "$name light update has ${byteArray.size} bytes"
                }
                val sectionY = MinecraftCoordinates.offsetSectionCoordinate(firstSectionY, bit)
                result[sectionY] = com.hiczp.minecraft.nbt.NbtByteArray(byteArray)
            }
        }
        require(updateIndex == updates.size) { "$name light packet has more payloads than update-mask bits" }
        return result
    }

    private fun decodeBlockEntity(chunkPosition: ChunkPosition, blockEntityInfo: BlockEntityInfo): BlockEntity {
        val blockEntityTypeRegistryEntry =
            protocolRegistryContext.requireRegistry(BLOCK_ENTITY_TYPE_REGISTRY)[blockEntityInfo.typeId]
                ?: throw IllegalArgumentException("Block-entity type registry ID ${blockEntityInfo.typeId} has no installed entry")
        val values = linkedMapOf<String, NbtTag>()
        blockEntityInfo.tag?.forEachEntry { name, nbtTag ->
            if (name !in BLOCK_ENTITY_STRUCTURE_FIELDS) values[name] = nbtTag
        }
        return BlockEntity(
            type = blockEntityTypeRegistryEntry.id.value,
            blockPosition = chunkPosition.block(
                ChunkBlockPosition(
                    blockEntityInfo.localX,
                    blockEntityInfo.y.toInt(),
                    blockEntityInfo.localZ
                )
            ),
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
    minecraftChunkPacketDecoder: MinecraftChunkPacketDecoder,
): Chunk<ProtocolBlockState, ProtocolRegistryEntry> = minecraftChunkPacketDecoder.decode(this)

private fun unpackValues(packedLongArray: PackedLongArray, bitsPerEntry: Int, entryCount: Int): IntArray {
    require(bitsPerEntry in 1..<Int.SIZE_BITS)
    val entriesPerLong = Long.SIZE_BITS / bitsPerEntry
    val expectedSize = (entryCount + entriesPerLong - 1) / entriesPerLong
    require(packedLongArray.size == expectedSize) {
        "Packed palette has ${packedLongArray.size} Longs, expected $expectedSize"
    }
    val mask = (1L shl bitsPerEntry) - 1
    return IntArray(entryCount) { index ->
        val longIndex = index / entriesPerLong
        val bitIndex = index % entriesPerLong * bitsPerEntry
        (packedLongArray[longIndex] ushr bitIndex and mask).toInt()
    }
}

private fun minimumBitsForDistinctValues(count: Int): Int {
    require(count > 0)
    return if (count == 1) 0 else Int.SIZE_BITS - (count - 1).countLeadingZeroBits()
}
