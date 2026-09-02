package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtList
import com.hiczp.minecraft.nbt.NbtLongArray

/** The name and properties persisted by one block-state palette entry. */
data class BlockStateDescriptor(
    val name: String,
    val properties: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "A block-state name must not be blank" }
        require(properties.all { (property, value) -> property.isNotBlank() && value.isNotBlank() }) {
            "Block-state property names and values must not be blank"
        }
    }

}

/** Caller-supplied block-state catalogue used by strong Chunk conversion. */
interface BlockStateRegistry<B : Any> {
    val defaultValue: B

    fun resolve(blockStateDescriptor: BlockStateDescriptor): B?

    fun describe(value: B): BlockStateDescriptor?
}

/** Caller-supplied biome catalogue used by strong Chunk conversion. */
interface BiomeRegistry<M : Any> {
    val defaultValue: M

    fun resolve(name: String): M?

    fun name(value: M): String?
}

/** Open block-state mapping that accepts every persisted descriptor without catalogue validation. */
data class DescriptorBlockStateRegistry(
    override val defaultValue: BlockStateDescriptor = BlockStateDescriptor("minecraft:air"),
) : BlockStateRegistry<BlockStateDescriptor> {
    override fun resolve(blockStateDescriptor: BlockStateDescriptor): BlockStateDescriptor = blockStateDescriptor

    override fun describe(value: BlockStateDescriptor): BlockStateDescriptor = value
}

/** Open biome mapping that represents biome values by their persisted names. */
data class NamedBiomeRegistry(
    override val defaultValue: String = "minecraft:plains",
) : BiomeRegistry<String> {
    init {
        require(defaultValue.isNotBlank()) { "A default biome name must not be blank" }
    }

    override fun resolve(name: String): String? = name.takeIf(String::isNotBlank)

    override fun name(value: String): String? = value.takeIf(String::isNotBlank)
}

data class ChunkDataRegistries<B : Any, M : Any>(
    val blockStates: BlockStateRegistry<B>,
    val biomes: BiomeRegistry<M>,
)

/**
 * The vertical Section layout of one dimension.
 *
 * This value has no release-wide default: servers can synchronize different vanilla, datapack, or modded dimension
 * types with different minimum Y coordinates and heights.
 */
data class ChunkLayout(
    val minSectionY: Int,
    val sectionCount: Int,
) {
    init {
        require(sectionCount > 0) { "A Chunk layout must contain at least one Section" }
        val maximumSectionY = MinecraftCoordinates.offsetSectionCoordinate(minSectionY, sectionCount - 1)
        MinecraftCoordinates.sectionBlockCoordinate(minSectionY, 0)
        MinecraftCoordinates.sectionBlockCoordinate(maximumSectionY, SECTION_SIDE - 1)
        MinecraftCoordinates.blockCountForSections(sectionCount)
    }

    companion object {
        /** Creates a Section layout from block-aligned dimension bounds. */
        fun fromBlockBounds(minY: Int, height: Int): ChunkLayout {
            require(minY % SECTION_SIDE == 0) {
                "Chunk minimum block Y must be a multiple of $SECTION_SIDE"
            }
            require(height % SECTION_SIDE == 0) {
                "Chunk block height must be a multiple of $SECTION_SIDE"
            }
            return ChunkLayout(
                minSectionY = MinecraftCoordinates.sectionCoordinate(minY),
                sectionCount = height / SECTION_SIDE,
            )
        }
    }

    val maxSectionY: Int
        get() = MinecraftCoordinates.offsetSectionCoordinate(minSectionY, sectionCount - 1)

    val sectionYRange: IntRange
        get() = minSectionY..maxSectionY

    val minBlockY: Int
        get() = MinecraftCoordinates.sectionBlockCoordinate(minSectionY, 0)

    val height: Int
        get() = MinecraftCoordinates.blockCountForSections(sectionCount)

    val maxBlockY: Int
        get() = MinecraftCoordinates.sectionBlockCoordinate(maxSectionY, SECTION_SIDE - 1)

    val blockYRange: IntRange
        get() = minBlockY..maxBlockY

    operator fun contains(sectionY: Int): Boolean = sectionY in minSectionY..maxSectionY

    fun containsBlockY(y: Int): Boolean = y in blockYRange
}

data class ChunkCodecContext<B : Any, M : Any>(
    val chunkLayout: ChunkLayout,
    val chunkDataRegistries: ChunkDataRegistries<B, M>,
)

/**
 * Read-only diagnostics for a palette-backed container.
 *
 * [bitsPerEntry] is the smallest logical width for the current palette IDs. A physical format may impose a larger
 * minimum when it packs those IDs.
 */
data class PaletteSnapshot<T : Any>(
    val values: List<T>,
    val bitsPerEntry: Int,
    val entryCount: Int,
)

/**
 * Mutable logical values backed by stable palette IDs.
 *
 * Ordinary writes reuse or append IDs. They deliberately do not remove an unused value or reorder the palette.
 */
class PalettedContainer<T : Any> private constructor(
    val size: Int,
    paletteStorage: PaletteStorage<T>,
) : Iterable<T> {
    private val palette = paletteStorage.palette
    private val ids = paletteStorage.ids

    init {
        require(size > 0) { "A paletted container must not be empty" }
        require(palette.isNotEmpty()) { "A paletted container must have at least one palette value" }
        require(ids.all { it in palette.indices }) { "A paletted container contains an invalid palette ID" }
    }

    constructor(size: Int, initialValue: T) : this(
        size,
        PaletteStorage(mutableListOf(initialValue), IntArray(size)),
    )

    constructor(values: List<T>) : this(values.size, paletteStorage(values))

    operator fun get(index: Int): T = palette[ids[index]]

    operator fun set(index: Int, value: T) {
        replace(index, value)
    }

    /** Replaces one logical value and returns the previous value without compacting the palette. */
    fun replace(index: Int, value: T): T {
        require(index in 0 until size) { "Palette index $index is outside 0 until $size" }
        val previous = palette[ids[index]]
        var id = palette.indexOf(value)
        if (id < 0) {
            palette += value
            id = palette.lastIndex
        }
        ids[index] = id
        return previous
    }

    /** Creates an independently mutable container while retaining the current palette IDs. */
    fun snapshot(): PalettedContainer<T> = fromPalette(palette, ids)

    fun paletteSnapshot(): PaletteSnapshot<T> =
        PaletteSnapshot(
            values = palette.toList(),
            bitsPerEntry = bitsForPaletteSize(palette.size),
            entryCount = size,
        )

    fun distinctValues(): Set<T> = LinkedHashSet<T>().apply {
        ids.forEach { add(palette[it]) }
    }

    fun toDenseList(): List<T> = List(size, ::get)

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var index = 0

        override fun hasNext(): Boolean = index < size

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return get(index++)
        }
    }

    /** Removes unused palette values and remaps this container's IDs in place. */
    fun compact() {
        val snapshot = compactSnapshot()
        palette.clear()
        palette.addAll(snapshot.values)
        snapshot.rawIds.copyInto(ids)
    }

    /** Returns a compact palette and remapped IDs without modifying this container. */
    fun compactSnapshot(): CompactPalette<T> {
        val compactValues = mutableListOf<T>()
        val compactIds = IntArray(size)
        ids.forEachIndexed { index, oldId ->
            val value = palette[oldId]
            var compactId = compactValues.indexOf(value)
            if (compactId < 0) {
                compactValues += value
                compactId = compactValues.lastIndex
            }
            compactIds[index] = compactId
        }
        return CompactPalette(compactValues, compactIds)
    }

    companion object {
        /**
         * Creates a container from an existing palette and one palette ID per logical entry.
         *
         * Both inputs are snapshotted. This avoids constructing a temporary dense value list when adapting another
         * palette-based representation.
         */
        fun <T : Any> fromPalette(
            palette: List<T>,
            ids: IntArray,
        ): PalettedContainer<T> = PalettedContainer(
            size = ids.size,
            paletteStorage = PaletteStorage(palette.toMutableList(), ids.copyOf()),
        )
    }
}

private class PaletteStorage<T : Any>(
    val palette: MutableList<T>,
    val ids: IntArray,
)

private fun <T : Any> paletteStorage(values: List<T>): PaletteStorage<T> {
    val palette = mutableListOf<T>()
    val ids = IntArray(values.size)
    values.forEachIndexed { index, value ->
        var id = palette.indexOf(value)
        if (id < 0) {
            palette += value
            id = palette.lastIndex
        }
        ids[index] = id
    }
    return PaletteStorage(palette, ids)
}

/**
 * A read-only compact palette snapshot.
 *
 * [values] follows first-use order, while [ids] maps every logical container entry to one value.
 */
class CompactPalette<T : Any>(
    values: List<T>,
    ids: IntArray,
) {
    private val idArray = ids.copyOf()

    val values: List<T> = values.toList()
    val ids: List<Int> = idArray.asList()
    val bitsPerEntry: Int = bitsForPaletteSize(values.size)
    val entryCount: Int = idArray.size

    init {
        require(this.values.isNotEmpty()) { "A compact palette must not be empty" }
        require(idArray.isNotEmpty()) { "A compact palette must describe at least one entry" }
        require(idArray.all { it in this.values.indices }) { "A compact palette contains an invalid palette ID" }
    }

    operator fun get(index: Int): T = values[idArray[index]]

    internal val rawIds: IntArray
        get() = idArray
}

/** Selected-release fields that exist only in persistent Chunk NBT. */
data class ChunkStorageMetadata(
    val dataVersion: Int,
    val status: String,
    val lastUpdateTime: Long = 0,
    val inhabitedTime: Long = 0,
    val lightCorrect: Boolean = false,
    val upgradeData: NbtCompound? = null,
    val blendingData: NbtCompound? = null,
    val belowZeroRetrogen: NbtCompound? = null,
    val carvingMask: NbtLongArray? = null,
    val blockTicks: NbtList = NbtList(emptyList()),
    val fluidTicks: NbtList = NbtList(emptyList()),
    val postProcessing: NbtList = NbtList(emptyList()),
    val entities: NbtList? = null,
    val structures: NbtCompound = NbtCompound(emptyMap()),
) {
    init {
        require(status.isNotBlank()) { "A Chunk status must not be blank" }
    }

    /** Whether this Chunk has reached the selected release's terminal world-generation status. */
    val isFullyGenerated: Boolean
        get() = status == FULLY_GENERATED_STATUS

    companion object {
        const val FULLY_GENERATED_STATUS: String = "minecraft:full"
    }
}

/**
 * Auxiliary semantic Chunk state outside its Section palettes and Block Entities.
 *
 * Heightmaps and boundary lighting are shared by persistent and network Chunks. [chunkStorageMetadata] is absent when
 * the Chunk came from a packet because the wire does not carry those persistence-only fields.
 */
data class ChunkMetadata(
    val chunkStorageMetadata: ChunkStorageMetadata? = null,
    val heightmaps: NbtCompound = NbtCompound(emptyMap()),
    val lightOnlySections: Map<Int, SectionLighting> = emptyMap(),
)

data class SectionLighting(
    val blockLight: NbtByteArray? = null,
    val skyLight: NbtByteArray? = null,
) {
    init {
        require(blockLight != null || skyLight != null) { "An empty Section lighting value is not meaningful" }
        require(blockLight == null || blockLight.size == SECTION_LIGHT_BYTE_COUNT) {
            "Block light must contain $SECTION_LIGHT_BYTE_COUNT bytes"
        }
        require(skyLight == null || skyLight.size == SECTION_LIGHT_BYTE_COUNT) {
            "Sky light must contain $SECTION_LIGHT_BYTE_COUNT bytes"
        }
    }
}

/** A mutable semantic Block Entity at one absolute Block position. */
class BlockEntity(
    val type: String,
    val blockPosition: BlockPosition,
    persistentData: NbtCompound = NbtCompound(emptyMap()),
) {
    init {
        require(type.isNotBlank()) { "A Block Entity type must not be blank" }
    }

    var persistentData: NbtCompound = persistentData

    /** Creates a detached mutable Block Entity snapshot. */
    fun snapshot(): BlockEntity = BlockEntity(type, blockPosition, persistentData)
}

class ChunkSection<B : Any, M : Any>(
    val sectionY: Int,
    val blockStates: PalettedContainer<B>,
    val biomes: PalettedContainer<M>,
    blockLight: NbtByteArray? = null,
    skyLight: NbtByteArray? = null,
) {
    init {
        require(blockStates.size == SECTION_BLOCK_COUNT) {
            "A Chunk Section needs $SECTION_BLOCK_COUNT block states, got ${blockStates.size}"
        }
        require(biomes.size == SECTION_BIOME_COUNT) {
            "A Chunk Section needs $SECTION_BIOME_COUNT biomes, got ${biomes.size}"
        }
        require(blockLight == null || blockLight.size == SECTION_LIGHT_BYTE_COUNT) {
            "Block light must contain $SECTION_LIGHT_BYTE_COUNT bytes"
        }
        require(skyLight == null || skyLight.size == SECTION_LIGHT_BYTE_COUNT) {
            "Sky light must contain $SECTION_LIGHT_BYTE_COUNT bytes"
        }
    }

    var blockLight: NbtByteArray? = blockLight
        set(value) {
            require(value == null || value.size == SECTION_LIGHT_BYTE_COUNT) {
                "Block light must contain $SECTION_LIGHT_BYTE_COUNT bytes"
            }
            field = value
        }

    var skyLight: NbtByteArray? = skyLight
        set(value) {
            require(value == null || value.size == SECTION_LIGHT_BYTE_COUNT) {
                "Sky light must contain $SECTION_LIGHT_BYTE_COUNT bytes"
            }
            field = value
        }

    operator fun get(localBlockPosition: LocalBlockPosition): B = blockStates[localBlockPosition.index]

    operator fun set(localBlockPosition: LocalBlockPosition, value: B) {
        blockStates[localBlockPosition.index] = value
    }

    fun block(localBlockPosition: LocalBlockPosition): B = get(localBlockPosition)

    fun block(localX: Int, localY: Int, localZ: Int): B = block(LocalBlockPosition(localX, localY, localZ))

    /** Reads an absolute block after validating that it belongs to this Section's supplied position. */
    fun block(sectionPosition: SectionPosition, blockPosition: BlockPosition): B =
        block(local(sectionPosition, blockPosition))

    fun setBlock(localBlockPosition: LocalBlockPosition, value: B) {
        set(localBlockPosition, value)
    }

    fun setBlock(localX: Int, localY: Int, localZ: Int, value: B) {
        setBlock(LocalBlockPosition(localX, localY, localZ), value)
    }

    /** Writes an absolute block after validating that it belongs to this Section's supplied position. */
    fun setBlock(sectionPosition: SectionPosition, blockPosition: BlockPosition, value: B) {
        setBlock(local(sectionPosition, blockPosition), value)
    }

    /** Replaces one Section-local block and returns its previous state. */
    fun replaceBlock(localBlockPosition: LocalBlockPosition, value: B): B =
        blockStates.replace(localBlockPosition.index, value)

    fun replaceBlock(localX: Int, localY: Int, localZ: Int, value: B): B =
        replaceBlock(LocalBlockPosition(localX, localY, localZ), value)

    /** Replaces one absolute block after validating the supplied positionless Section context. */
    fun replaceBlock(sectionPosition: SectionPosition, blockPosition: BlockPosition, value: B): B =
        replaceBlock(local(sectionPosition, blockPosition), value)

    /** Reads one biome at Section-local quart coordinates in `0..3`. */
    fun biome(quartX: Int, quartY: Int, quartZ: Int): M = biomes[biomeIndex(quartX, quartY, quartZ)]

    /** Reads the biome cell containing one Section-local block position. */
    fun biome(localBlockPosition: LocalBlockPosition): M =
        biome(
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.x),
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.y),
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.z),
        )

    /** Reads the biome cell containing an absolute block in the supplied Section position. */
    fun biome(sectionPosition: SectionPosition, blockPosition: BlockPosition): M =
        biome(local(sectionPosition, blockPosition))

    /** Writes one biome at Section-local quart coordinates in `0..3`. */
    fun setBiome(quartX: Int, quartY: Int, quartZ: Int, value: M) {
        biomes[biomeIndex(quartX, quartY, quartZ)] = value
    }

    /** Writes the biome cell containing one Section-local block position. */
    fun setBiome(localBlockPosition: LocalBlockPosition, value: M) {
        setBiome(
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.x),
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.y),
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.z),
            value,
        )
    }

    /** Writes the biome cell containing an absolute block in the supplied Section position. */
    fun setBiome(sectionPosition: SectionPosition, blockPosition: BlockPosition, value: M) {
        setBiome(local(sectionPosition, blockPosition), value)
    }

    /** Replaces one Section-local biome cell and returns its previous value. */
    fun replaceBiome(quartX: Int, quartY: Int, quartZ: Int, value: M): M =
        biomes.replace(biomeIndex(quartX, quartY, quartZ), value)

    /** Replaces the biome cell containing one Section-local block and returns its previous value. */
    fun replaceBiome(localBlockPosition: LocalBlockPosition, value: M): M =
        replaceBiome(
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.x),
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.y),
            MinecraftCoordinates.quartCoordinateInSection(localBlockPosition.z),
            value,
        )

    /** Replaces one absolute block's biome cell after validating the supplied Section context. */
    fun replaceBiome(sectionPosition: SectionPosition, blockPosition: BlockPosition, value: M): M =
        replaceBiome(local(sectionPosition, blockPosition), value)

    /** Compacts the block-state and biome palettes in place without changing their logical values. */
    fun compactPalettes() {
        blockStates.compact()
        biomes.compact()
    }

    /** Creates an independently mutable Section snapshot. */
    fun snapshot(): ChunkSection<B, M> = ChunkSection(
        sectionY = sectionY,
        blockStates = blockStates.snapshot(),
        biomes = biomes.snapshot(),
        blockLight = blockLight,
        skyLight = skyLight,
    )

    fun toDenseBlockStates(): List<B> = blockStates.toDenseList()

    fun toDenseBiomes(): List<M> = biomes.toDenseList()

    private fun biomeIndex(quartX: Int, quartY: Int, quartZ: Int): Int =
        MinecraftCoordinates.biomeIndex(quartX, quartY, quartZ)

    private fun local(sectionPosition: SectionPosition, blockPosition: BlockPosition): LocalBlockPosition {
        require(sectionPosition.y == sectionY) {
            "Section position $sectionPosition does not describe Section Y $sectionY"
        }
        return sectionPosition.local(blockPosition)
    }
}

/** A mutable semantic Chunk at one absolute X/Z position. */
class Chunk<B : Any, M : Any>(
    val chunkPosition: ChunkPosition,
    chunkMetadata: ChunkMetadata,
    val chunkLayout: ChunkLayout,
    sections: Collection<ChunkSection<B, M>> = emptyList(),
    blockEntities: Collection<BlockEntity> = emptyList(),
    val defaultBlockState: B,
    val defaultBiome: M,
) {
    private val sectionsByY = sections.associateByTo(linkedMapOf(), ChunkSection<B, M>::sectionY)
    private val blockEntitiesByPosition = blockEntities.associateByTo(linkedMapOf(), BlockEntity::blockPosition)
    private var storedChunkMetadata = chunkMetadata.snapshotWithoutSections(sectionsByY.keys)

    var chunkMetadata: ChunkMetadata
        get() = storedChunkMetadata.snapshot()
        set(value) {
            storedChunkMetadata = value.snapshotWithoutSections(sectionsByY.keys)
        }

    init {
        require(sectionsByY.size == sections.size) { "A Chunk contains duplicate Section Y coordinates" }
        sectionsByY.keys.forEach(::requireSectionInLayout)
        require(blockEntitiesByPosition.size == blockEntities.size) {
            "A Chunk contains duplicate Block Entity positions"
        }
        blockEntitiesByPosition.values.forEach(::requireBlockEntityMembership)
    }

    val sections: Collection<ChunkSection<B, M>>
        get() = sectionsByY.values.toList()

    val sectionCount: Int
        get() = sectionsByY.size

    val blockEntities: Collection<BlockEntity>
        get() = blockEntitiesByPosition.values.toList()

    val blockEntityCount: Int
        get() = blockEntitiesByPosition.size

    fun section(sectionY: Int): ChunkSection<B, M>? = sectionsByY[sectionY]

    /** Finds the Section containing one Chunk-local block position. */
    fun section(chunkBlockPosition: ChunkBlockPosition): ChunkSection<B, M>? = section(chunkBlockPosition.sectionY)

    /** Finds an absolute Section after validating that it belongs to this Chunk. */
    fun section(sectionPosition: SectionPosition): ChunkSection<B, M>? {
        require(sectionPosition.chunkPosition == this.chunkPosition) { "Section $sectionPosition does not belong to Chunk ${this.chunkPosition}" }
        return section(sectionPosition.y)
    }

    /** Finds the Section containing one absolute block after validating that it belongs to this Chunk. */
    fun section(blockPosition: BlockPosition): ChunkSection<B, M>? = section(blockPosition.sectionPosition)

    /** Whether a semantic Section is explicitly present at [sectionY]. */
    fun hasSection(sectionY: Int): Boolean = sectionsByY.containsKey(sectionY)

    /** Whether the semantic Section containing [chunkBlockPosition] is explicitly present. */
    fun hasSection(chunkBlockPosition: ChunkBlockPosition): Boolean = hasSection(chunkBlockPosition.sectionY)

    /** Whether one absolute semantic Section belonging to this Chunk is explicitly present. */
    fun hasSection(sectionPosition: SectionPosition): Boolean {
        require(sectionPosition.chunkPosition == this.chunkPosition) { "Section $sectionPosition does not belong to Chunk ${this.chunkPosition}" }
        return hasSection(sectionPosition.y)
    }

    /** Whether the Section containing one absolute block belonging to this Chunk is explicitly present. */
    fun hasSection(blockPosition: BlockPosition): Boolean = hasSection(blockPosition.sectionPosition)

    fun getOrCreateSection(sectionY: Int): ChunkSection<B, M> {
        requireSectionInLayout(sectionY)
        return sectionsByY.getOrPut(sectionY) {
            val sectionLighting = storedChunkMetadata.lightOnlySections[sectionY]
            if (sectionLighting != null) {
                storedChunkMetadata =
                    storedChunkMetadata.copy(lightOnlySections = storedChunkMetadata.lightOnlySections - sectionY)
            }
            ChunkSection(
                sectionY = sectionY,
                blockStates = PalettedContainer(SECTION_BLOCK_COUNT, defaultBlockState),
                biomes = PalettedContainer(SECTION_BIOME_COUNT, defaultBiome),
                blockLight = sectionLighting?.blockLight,
                skyLight = sectionLighting?.skyLight,
            )
        }
    }

    /** Finds or creates the Section containing one Chunk-local block position. */
    fun getOrCreateSection(chunkBlockPosition: ChunkBlockPosition): ChunkSection<B, M> =
        getOrCreateSection(chunkBlockPosition.sectionY)

    /** Finds or creates an absolute Section after validating that it belongs to this Chunk. */
    fun getOrCreateSection(sectionPosition: SectionPosition): ChunkSection<B, M> {
        require(sectionPosition.chunkPosition == this.chunkPosition) { "Section $sectionPosition does not belong to Chunk ${this.chunkPosition}" }
        return getOrCreateSection(sectionPosition.y)
    }

    /** Finds or creates the Section containing one absolute block belonging to this Chunk. */
    fun getOrCreateSection(blockPosition: BlockPosition): ChunkSection<B, M> =
        getOrCreateSection(blockPosition.sectionPosition)

    /** Installs one Section and returns the previous Section at the same Y coordinate. */
    fun setSection(chunkSection: ChunkSection<B, M>): ChunkSection<B, M>? {
        requireSectionInLayout(chunkSection.sectionY)
        if (storedChunkMetadata.lightOnlySections.containsKey(chunkSection.sectionY)) {
            storedChunkMetadata = storedChunkMetadata.copy(
                lightOnlySections = storedChunkMetadata.lightOnlySections - chunkSection.sectionY,
            )
        }
        return sectionsByY.put(chunkSection.sectionY, chunkSection)
    }

    /** Removes one semantic Section while retaining any lighting as a light-only Section. */
    fun removeSection(sectionY: Int): ChunkSection<B, M>? {
        val removed = sectionsByY.remove(sectionY) ?: return null
        if (removed.blockLight != null || removed.skyLight != null) {
            val sectionLighting = SectionLighting(removed.blockLight, removed.skyLight)
            storedChunkMetadata = storedChunkMetadata.copy(
                lightOnlySections = storedChunkMetadata.lightOnlySections + (sectionY to sectionLighting),
            )
        }
        return removed
    }

    /** Removes an absolute Section after validating that it belongs to this Chunk. */
    fun removeSection(sectionPosition: SectionPosition): ChunkSection<B, M>? {
        require(sectionPosition.chunkPosition == this.chunkPosition) { "Section $sectionPosition does not belong to Chunk ${this.chunkPosition}" }
        return removeSection(sectionPosition.y)
    }

    /** Reads a logical block, returning [defaultBlockState] without materializing an absent Section. */
    operator fun get(chunkBlockPosition: ChunkBlockPosition): B {
        require(chunkLayout.containsBlockY(chunkBlockPosition.y)) { "Block Y ${chunkBlockPosition.y} is outside $chunkLayout" }
        return section(chunkBlockPosition.sectionY)?.get(chunkBlockPosition.localInSection) ?: defaultBlockState
    }

    /** Reads an absolute logical block after validating that it belongs to this Chunk. */
    operator fun get(blockPosition: BlockPosition): B = get(local(blockPosition))

    operator fun set(chunkBlockPosition: ChunkBlockPosition, value: B) {
        replaceBlock(chunkBlockPosition, value)
    }

    /** Writes an absolute block after validating that it belongs to this Chunk. */
    operator fun set(blockPosition: BlockPosition, value: B) {
        set(local(blockPosition), value)
    }

    fun block(localX: Int, y: Int, localZ: Int): B = get(ChunkBlockPosition(localX, y, localZ))

    fun block(chunkBlockPosition: ChunkBlockPosition): B = get(chunkBlockPosition)

    fun block(blockPosition: BlockPosition): B = get(blockPosition)

    /** Whether the complete semantic Section containing this Chunk-local position is explicitly present. */
    fun hasBlock(localX: Int, y: Int, localZ: Int): Boolean =
        hasBlock(ChunkBlockPosition(localX, y, localZ))

    /** Whether the complete semantic Section containing [chunkBlockPosition] is explicitly present, regardless of Block state. */
    fun hasBlock(chunkBlockPosition: ChunkBlockPosition): Boolean = hasSection(chunkBlockPosition)

    /** Whether the complete semantic Section containing this absolute position is explicitly present. */
    fun hasBlock(blockPosition: BlockPosition): Boolean = hasSection(blockPosition)

    fun setBlock(localX: Int, y: Int, localZ: Int, value: B) {
        set(ChunkBlockPosition(localX, y, localZ), value)
    }

    fun setBlock(chunkBlockPosition: ChunkBlockPosition, value: B) {
        set(chunkBlockPosition, value)
    }

    fun setBlock(blockPosition: BlockPosition, value: B) {
        set(blockPosition, value)
    }

    /** Replaces one Chunk-local block and returns its previous state. */
    fun replaceBlock(localX: Int, y: Int, localZ: Int, value: B): B =
        replaceBlock(ChunkBlockPosition(localX, y, localZ), value)

    /** Replaces one Chunk-local block and returns its previous state. */
    fun replaceBlock(chunkBlockPosition: ChunkBlockPosition, value: B): B {
        require(chunkLayout.containsBlockY(chunkBlockPosition.y)) { "Block Y ${chunkBlockPosition.y} is outside $chunkLayout" }
        val chunkSection = sectionsByY[chunkBlockPosition.sectionY]
        if (chunkSection == null && value == defaultBlockState) return defaultBlockState
        return (chunkSection
            ?: getOrCreateSection(chunkBlockPosition.sectionY)).replaceBlock(chunkBlockPosition.localInSection, value)
    }

    /** Replaces one absolute block belonging to this Chunk and returns its previous state. */
    fun replaceBlock(blockPosition: BlockPosition, value: B): B = replaceBlock(local(blockPosition), value)

    /** Reads the biome cell containing the supplied Chunk-local X/Z and absolute block Y. */
    fun biome(localX: Int, y: Int, localZ: Int): M {
        val chunkBlockPosition = ChunkBlockPosition(localX, y, localZ)
        require(chunkLayout.containsBlockY(y)) { "Block Y $y is outside $chunkLayout" }
        return section(chunkBlockPosition.sectionY)?.biome(
            MinecraftCoordinates.quartCoordinateInSection(localX),
            MinecraftCoordinates.quartCoordinateInSection(y),
            MinecraftCoordinates.quartCoordinateInSection(localZ),
        )
            ?: defaultBiome
    }

    fun biome(chunkBlockPosition: ChunkBlockPosition): M =
        biome(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z)

    /** Reads the biome cell containing one absolute block belonging to this Chunk. */
    fun biome(blockPosition: BlockPosition): M = biome(local(blockPosition))

    /** Writes the 4 by 4 by 4 biome cell containing the supplied Chunk-local X/Z and absolute block Y. */
    fun setBiome(localX: Int, y: Int, localZ: Int, value: M) {
        replaceBiome(localX, y, localZ, value)
    }

    fun setBiome(chunkBlockPosition: ChunkBlockPosition, value: M) {
        setBiome(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z, value)
    }

    /** Writes the biome cell containing one absolute block belonging to this Chunk. */
    fun setBiome(blockPosition: BlockPosition, value: M) {
        setBiome(local(blockPosition), value)
    }

    /** Replaces one Chunk-local block's biome cell and returns its previous value. */
    fun replaceBiome(localX: Int, y: Int, localZ: Int, value: M): M {
        val chunkBlockPosition = ChunkBlockPosition(localX, y, localZ)
        require(chunkLayout.containsBlockY(y)) { "Block Y $y is outside $chunkLayout" }
        val chunkSection = sectionsByY[chunkBlockPosition.sectionY]
        if (chunkSection == null && value == defaultBiome) return defaultBiome
        return (chunkSection
            ?: getOrCreateSection(chunkBlockPosition.sectionY)).replaceBiome(chunkBlockPosition.localInSection, value)
    }

    fun replaceBiome(chunkBlockPosition: ChunkBlockPosition, value: M): M =
        replaceBiome(chunkBlockPosition.x, chunkBlockPosition.y, chunkBlockPosition.z, value)

    /** Replaces one absolute block's biome cell belonging to this Chunk and returns its previous value. */
    fun replaceBiome(blockPosition: BlockPosition, value: M): M = replaceBiome(local(blockPosition), value)

    fun blockEntity(chunkBlockPosition: ChunkBlockPosition): BlockEntity? =
        blockEntity(this.chunkPosition.block(chunkBlockPosition))

    /** Finds an absolute Block Entity by its stored position. */
    fun blockEntity(blockPosition: BlockPosition): BlockEntity? = blockEntitiesByPosition[blockPosition]

    fun hasBlockEntity(chunkBlockPosition: ChunkBlockPosition): Boolean =
        hasBlockEntity(this.chunkPosition.block(chunkBlockPosition))

    /** Checks an absolute Block Entity position against the stored positions. */
    fun hasBlockEntity(blockPosition: BlockPosition): Boolean = blockEntitiesByPosition.containsKey(blockPosition)

    /** Installs one Block Entity and returns the previous value at the same position. */
    fun setBlockEntity(blockEntity: BlockEntity): BlockEntity? {
        requireBlockEntityMembership(blockEntity)
        return blockEntitiesByPosition.put(blockEntity.blockPosition, blockEntity)
    }

    fun setBlockEntity(
        chunkBlockPosition: ChunkBlockPosition,
        type: String,
        persistentData: NbtCompound = NbtCompound(emptyMap()),
    ): BlockEntity? = setBlockEntity(BlockEntity(type, this.chunkPosition.block(chunkBlockPosition), persistentData))

    /** Installs one Block Entity at the supplied absolute position. */
    fun setBlockEntity(
        blockPosition: BlockPosition,
        type: String,
        persistentData: NbtCompound = NbtCompound(emptyMap()),
    ): BlockEntity? = setBlockEntity(BlockEntity(type, blockPosition, persistentData))

    fun removeBlockEntity(chunkBlockPosition: ChunkBlockPosition): BlockEntity? =
        removeBlockEntity(this.chunkPosition.block(chunkBlockPosition))

    /** Removes a Block Entity at the supplied absolute position. */
    fun removeBlockEntity(blockPosition: BlockPosition): BlockEntity? = blockEntitiesByPosition.remove(blockPosition)

    /** Compacts every present Section's block-state and biome palettes without materializing absent Sections. */
    fun compactPalettes() {
        sectionsByY.values.forEach { chunkSection -> chunkSection.compactPalettes() }
    }

    /** Creates an independently mutable Chunk snapshot. Caller-supplied block and biome values are retained. */
    fun snapshot(): Chunk<B, M> = Chunk(
        chunkPosition = chunkPosition,
        chunkMetadata = storedChunkMetadata,
        chunkLayout = chunkLayout,
        sections = sectionsByY.values.map(ChunkSection<B, M>::snapshot),
        blockEntities = blockEntitiesByPosition.values.map(BlockEntity::snapshot),
        defaultBlockState = defaultBlockState,
        defaultBiome = defaultBiome,
    )

    private fun requireSectionInLayout(sectionY: Int) {
        require(sectionY in chunkLayout) { "Section Y $sectionY is outside $chunkLayout" }
    }

    private fun requireBlockEntityMembership(blockEntity: BlockEntity) {
        require(blockEntity.blockPosition.chunkPosition == chunkPosition) {
            "Block Entity ${blockEntity.blockPosition} does not belong to Chunk $chunkPosition"
        }
        require(chunkLayout.containsBlockY(blockEntity.blockPosition.y)) {
            "Block Entity Y ${blockEntity.blockPosition.y} is outside $chunkLayout"
        }
    }

    private fun local(blockPosition: BlockPosition): ChunkBlockPosition = this.chunkPosition.local(blockPosition)
}

private fun ChunkMetadata.snapshot(): ChunkMetadata =
    copy(lightOnlySections = lightOnlySections.toMap())

private fun ChunkMetadata.snapshotWithoutSections(sectionYs: Set<Int>): ChunkMetadata =
    copy(lightOnlySections = lightOnlySections.filterKeys { sectionY -> sectionY !in sectionYs })

class ChunkNbtFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun bitsForPaletteSize(size: Int): Int {
    require(size > 0)
    return if (size == 1) 0 else Int.SIZE_BITS - (size - 1).countLeadingZeroBits()
}

const val BIOME_CELL_SIDE: Int = 4
const val BIOME_SECTION_SIDE: Int = SECTION_SIDE / BIOME_CELL_SIDE
const val SECTION_BIOME_COUNT: Int = BIOME_SECTION_SIDE * BIOME_SECTION_SIDE * BIOME_SECTION_SIDE
const val SECTION_LIGHT_BYTE_COUNT: Int = SECTION_BLOCK_COUNT / 2
internal val BLOCK_ENTITY_STRUCTURE_FIELDS: Set<String> = setOf("id", "x", "y", "z")
