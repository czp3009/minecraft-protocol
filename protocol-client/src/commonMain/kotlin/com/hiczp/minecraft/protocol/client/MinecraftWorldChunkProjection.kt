package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtLongArray
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.datapack.MinecraftChunkContext
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.ChunkSection
import com.hiczp.minecraft.world.format.PalettedContainer
import com.hiczp.minecraft.protocol.model.type.ChunkSection as NetworkChunkSection
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as NetworkPalettedContainer

fun MinecraftChunkContext.packetDecoder(): MinecraftChunkPacketDecoder = MinecraftChunkPacketDecoder(this)

/**
 * Stateless decoding of clientbound Chunk packets into positioned strong world Chunks.
 *
 * The packet does not carry a world data version, generation status, inhabited time, or other persistence-only fields,
 * so a decoded Chunk has no [ChunkStorageMetadata]. Packet heightmaps, block entities, and light become its common
 * semantic metadata. This decoder is immutable and can be shared across the active dimension.
 */
class MinecraftChunkPacketDecoder(
    val protocolRegistryContext: ProtocolRegistryContext,
    val chunkCodecContext: ChunkCodecContext<ProtocolBlockState, ProtocolRegistryEntry>,
) {
    constructor(minecraftChunkContext: MinecraftChunkContext) : this(
        protocolRegistryContext = minecraftChunkContext.protocolRegistryContext,
        chunkCodecContext = minecraftChunkContext.chunkCodecContext,
    )

    val chunkLayout: ChunkLayout = chunkCodecContext.chunkLayout

    val chunkDataRegistries: ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
        chunkCodecContext.chunkDataRegistries

    private val biomeProtocolRegistry = protocolRegistryContext.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)
    private val biomeRegistrySize = biomeProtocolRegistry.size

    /** Decodes one packet while retaining its x/z coordinates in the resulting Chunk. */
    fun decode(chunkDataAndUpdateLightPacket: ChunkDataAndUpdateLightPacket): Chunk<ProtocolBlockState, ProtocolRegistryEntry> {
        val blockLight = decodeLightLayers(
            updateMask = chunkDataAndUpdateLightPacket.lightData.blockYMask,
            updates = chunkDataAndUpdateLightPacket.lightData.blockUpdates.map { lightDataLayer -> lightDataLayer.bytes.toByteArray() },
            name = "block",
        )
        val skyLight = decodeLightLayers(
            updateMask = chunkDataAndUpdateLightPacket.lightData.skyYMask,
            updates = chunkDataAndUpdateLightPacket.lightData.skyUpdates.map { lightDataLayer -> lightDataLayer.bytes.toByteArray() },
            name = "sky",
        )
        val sections = chunkLayout.sectionYRange.mapIndexedNotNull { index, sectionY ->
            val chunkSection = chunkDataAndUpdateLightPacket.chunkData.sections.getOrNull(index)
                ?: return@mapIndexedNotNull null
            ChunkSection(
                sectionY = sectionY,
                blockStates = decodePalette(
                    networkPalettedContainer = chunkSection.blockStates,
                    entryCount = NetworkChunkSection.BLOCK_COUNT,
                    registrySize = protocolRegistryContext.blockStateRegistrySize,
                    valueAt = ::blockState,
                ),
                biomes = decodePalette(
                    networkPalettedContainer = chunkSection.biomes,
                    entryCount = NetworkChunkSection.BIOME_COUNT,
                    registrySize = biomeRegistrySize,
                    valueAt = ::biome,
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
        val decodedMetadata = ChunkMetadata(
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

    private fun blockState(id: Int): ProtocolBlockState = protocolRegistryContext.blockState(id)
        ?: throw IllegalArgumentException(
            "Block-state registry ID $id does not exist",
        )

    private fun biome(id: Int): ProtocolRegistryEntry = biomeProtocolRegistry[id]
        ?: throw IllegalArgumentException("Biome registry ID $id has no installed entry")

    private fun <T : Any> decodePalette(
        networkPalettedContainer: NetworkPalettedContainer,
        entryCount: Int,
        registrySize: Int,
        valueAt: (Int) -> T,
    ): PalettedContainer<T> = when (networkPalettedContainer) {
        is NetworkPalettedContainer.Single -> PalettedContainer(entryCount, valueAt(networkPalettedContainer.valueId))

        is NetworkPalettedContainer.Indirect -> {
            val palette = networkPalettedContainer.palette.map(valueAt)
            val ids = unpackValues(networkPalettedContainer.data, networkPalettedContainer.bitsPerEntry, entryCount)
            PalettedContainer.fromPalette(palette, ids)
        }

        is NetworkPalettedContainer.Direct -> {
            val bits = minimumBitsForDistinctValues(registrySize)
            val registryIds = if (bits == 0) {
                IntArray(entryCount)
            } else {
                unpackValues(networkPalettedContainer.data, bits, entryCount)
            }
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
        updates: List<ByteArray>,
        name: String,
    ): Map<Int, NbtByteArray> {
        val bitCount = chunkLayout.sectionCount + LIGHT_BOUNDARY_SECTION_COUNT
        val result = linkedMapOf<Int, NbtByteArray>()
        var updateIndex = 0
        val firstSectionY = MinecraftCoordinates.offsetSectionCoordinate(chunkLayout.minSectionY, -1)
        repeat(bitCount) { bit ->
            if (updateMask[bit]) {
                val byteArray = updates.getOrNull(updateIndex++)
                    ?: throw IllegalArgumentException("$name light update mask contains more bits than payloads")
                val sectionY = MinecraftCoordinates.offsetSectionCoordinate(firstSectionY, bit)
                result[sectionY] = NbtByteArray(byteArray)
            }
        }
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

    companion object {
        val BLOCK_ENTITY_TYPE_REGISTRY: Identifier = Identifier("block_entity_type")

        private val BLOCK_ENTITY_STRUCTURE_FIELDS = setOf("id", "x", "y", "z")
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
    val entriesPerLong = Long.SIZE_BITS / bitsPerEntry
    val requiredLongCount = (entryCount + entriesPerLong - 1) / entriesPerLong
    require(packedLongArray.size >= requiredLongCount) {
        "Packed palette has ${packedLongArray.size} Longs, but decoding $entryCount entries requires $requiredLongCount"
    }
    val mask = (1L shl bitsPerEntry) - 1
    return IntArray(entryCount) { index ->
        val longIndex = index / entriesPerLong
        val bitIndex = index % entriesPerLong * bitsPerEntry
        (packedLongArray[longIndex] ushr bitIndex and mask).toInt()
    }
}

private fun minimumBitsForDistinctValues(count: Int): Int {
    return if (count <= 1) 0 else Int.SIZE_BITS - (count - 1).countLeadingZeroBits()
}
