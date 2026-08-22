package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtList
import com.hiczp.minecraft.nbt.NbtLongArray

/** The name and properties persisted by one block-state palette entry. */
class BlockStateDescriptor(
    val name: String,
    properties: Map<String, String> = emptyMap(),
) {
    val properties: Map<String, String> = properties.toMap()

    init {
        require(name.isNotBlank()) { "A block-state name must not be blank" }
        require(this.properties.all { (property, value) -> property.isNotBlank() && value.isNotBlank() }) {
            "Block-state property names and values must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is BlockStateDescriptor && name == other.name && properties == other.properties

    override fun hashCode(): Int = 31 * name.hashCode() + properties.hashCode()

    override fun toString(): String = "BlockStateDescriptor(name=$name, properties=$properties)"
}

/** Caller-supplied block-state catalogue used by strong Chunk conversion. */
interface BlockStateRegistry<B : Any> {
    val defaultValue: B

    fun resolve(descriptor: BlockStateDescriptor): B?

    fun describe(value: B): BlockStateDescriptor?
}

/** Caller-supplied biome catalogue used by strong Chunk conversion. */
interface BiomeRegistry<M : Any> {
    val defaultValue: M

    fun resolve(name: String): M?

    fun name(value: M): String?
}

/** Open block-state mapping that accepts every persisted descriptor without catalogue validation. */
class DescriptorBlockStateRegistry(
    override val defaultValue: BlockStateDescriptor = BlockStateDescriptor("minecraft:air"),
) : BlockStateRegistry<BlockStateDescriptor> {
    override fun resolve(descriptor: BlockStateDescriptor): BlockStateDescriptor = descriptor

    override fun describe(value: BlockStateDescriptor): BlockStateDescriptor = value
}

/** Open biome mapping that represents biome values by their persisted names. */
class NamedBiomeRegistry(
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

data class ChunkNbtContext<B : Any, M : Any>(
    val layout: ChunkLayout,
    val registries: ChunkDataRegistries<B, M>,
    val expectedDataVersion: Int,
) {
    init {
        require(expectedDataVersion >= 0) { "A Minecraft data version must be non-negative" }
        require(layout.minSectionY >= Byte.MIN_VALUE && layout.maxSectionY <= Byte.MAX_VALUE) {
            "A Chunk NBT layout's Section Y range must fit TAG_Byte"
        }
    }
}

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
    storage: PaletteStorage<T>,
) : Iterable<T> {
    private val palette = storage.palette
    private val ids = storage.ids

    init {
        require(size > 0) { "A paletted container must not be empty" }
        require(ids.size == size) { "A paletted container has ${ids.size} IDs for $size entries" }
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
        snapshot.copyIdsTo(ids)
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
            storage = PaletteStorage(palette.toMutableList(), ids.copyOf()),
        )
    }
}

private class PaletteStorage<T : Any>(
    val palette: MutableList<T>,
    val ids: IntArray,
)

private fun <T : Any> paletteStorage(values: List<T>): PaletteStorage<T> {
    require(values.isNotEmpty()) { "A paletted container must not be empty" }
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
class CompactPalette<T : Any> internal constructor(
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

    internal fun copyIdsTo(destination: IntArray) {
        require(destination.size == idArray.size) {
            "Cannot copy ${idArray.size} compact palette IDs into ${destination.size} entries"
        }
        idArray.copyInto(destination)
    }
}

/** Selected-release Chunk fields other than Section palettes and the contextual x/z position. */
data class ChunkMetadata(
    val dataVersion: Int,
    val lastUpdateTime: Long = 0,
    val inhabitedTime: Long = 0,
    val status: String,
    val lightCorrect: Boolean = false,
    val upgradeData: NbtCompound? = null,
    val blendingData: NbtCompound? = null,
    val belowZeroRetrogen: NbtCompound? = null,
    val carvingMask: NbtLongArray? = null,
    val heightmaps: NbtCompound = NbtCompound(emptyMap()),
    val blockTicks: NbtList = NbtList(emptyList()),
    val fluidTicks: NbtList = NbtList(emptyList()),
    val postProcessing: NbtList = NbtList(emptyList()),
    val entities: NbtList? = null,
    val structures: NbtCompound = NbtCompound(emptyMap()),
    val lightOnlySections: Map<Int, SectionLighting> = emptyMap(),
) {
    init {
        require(dataVersion >= 0) { "A Minecraft data version must be non-negative" }
        require(status.isNotBlank()) { "A Chunk status must not be blank" }
    }
}

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

/** A mutable semantic Block Entity at Chunk-local X/Z and dimension-absolute Y. */
class BlockEntity(
    val type: String,
    val position: ChunkBlockPosition,
    persistentData: NbtCompound = NbtCompound(emptyMap()),
) {
    init {
        require(type.isNotBlank()) { "A Block Entity type must not be blank" }
    }

    var persistentData: NbtCompound = persistentData.requireNoBlockEntityStructureFields()
        set(value) {
            field = value.requireNoBlockEntityStructureFields()
        }

    /** Resolves this positionless value against its owning absolute Chunk position. */
    fun absolutePosition(chunkPosition: ChunkPosition): BlockPosition = chunkPosition.block(position)

    /** Creates a detached mutable Block Entity snapshot. */
    fun snapshot(): BlockEntity = BlockEntity(type, position, persistentData)
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

    operator fun get(position: LocalBlockPosition): B = blockStates[position.index]

    operator fun set(position: LocalBlockPosition, value: B) {
        blockStates[position.index] = value
    }

    fun block(position: LocalBlockPosition): B = get(position)

    fun block(localX: Int, localY: Int, localZ: Int): B = block(LocalBlockPosition(localX, localY, localZ))

    /** Reads an absolute block after validating that it belongs to this Section's supplied position. */
    fun block(sectionPosition: SectionPosition, position: BlockPosition): B =
        block(local(sectionPosition, position))

    fun setBlock(position: LocalBlockPosition, value: B) {
        set(position, value)
    }

    fun setBlock(localX: Int, localY: Int, localZ: Int, value: B) {
        setBlock(LocalBlockPosition(localX, localY, localZ), value)
    }

    /** Writes an absolute block after validating that it belongs to this Section's supplied position. */
    fun setBlock(sectionPosition: SectionPosition, position: BlockPosition, value: B) {
        setBlock(local(sectionPosition, position), value)
    }

    /** Replaces one Section-local block and returns its previous state. */
    fun replaceBlock(position: LocalBlockPosition, value: B): B = blockStates.replace(position.index, value)

    fun replaceBlock(localX: Int, localY: Int, localZ: Int, value: B): B =
        replaceBlock(LocalBlockPosition(localX, localY, localZ), value)

    /** Replaces one absolute block after validating the supplied positionless Section context. */
    fun replaceBlock(sectionPosition: SectionPosition, position: BlockPosition, value: B): B =
        replaceBlock(local(sectionPosition, position), value)

    /** Reads one biome at Section-local quart coordinates in `0..3`. */
    fun biome(quartX: Int, quartY: Int, quartZ: Int): M = biomes[biomeIndex(quartX, quartY, quartZ)]

    /** Reads the biome cell containing one Section-local block position. */
    fun biome(position: LocalBlockPosition): M =
        biome(
            MinecraftCoordinates.quartCoordinateInSection(position.x),
            MinecraftCoordinates.quartCoordinateInSection(position.y),
            MinecraftCoordinates.quartCoordinateInSection(position.z),
        )

    /** Reads the biome cell containing an absolute block in the supplied Section position. */
    fun biome(sectionPosition: SectionPosition, position: BlockPosition): M =
        biome(local(sectionPosition, position))

    /** Writes one biome at Section-local quart coordinates in `0..3`. */
    fun setBiome(quartX: Int, quartY: Int, quartZ: Int, value: M) {
        biomes[biomeIndex(quartX, quartY, quartZ)] = value
    }

    /** Writes the biome cell containing one Section-local block position. */
    fun setBiome(position: LocalBlockPosition, value: M) {
        setBiome(
            MinecraftCoordinates.quartCoordinateInSection(position.x),
            MinecraftCoordinates.quartCoordinateInSection(position.y),
            MinecraftCoordinates.quartCoordinateInSection(position.z),
            value,
        )
    }

    /** Writes the biome cell containing an absolute block in the supplied Section position. */
    fun setBiome(sectionPosition: SectionPosition, position: BlockPosition, value: M) {
        setBiome(local(sectionPosition, position), value)
    }

    /** Replaces one Section-local biome cell and returns its previous value. */
    fun replaceBiome(quartX: Int, quartY: Int, quartZ: Int, value: M): M =
        biomes.replace(biomeIndex(quartX, quartY, quartZ), value)

    /** Replaces the biome cell containing one Section-local block and returns its previous value. */
    fun replaceBiome(position: LocalBlockPosition, value: M): M =
        replaceBiome(
            MinecraftCoordinates.quartCoordinateInSection(position.x),
            MinecraftCoordinates.quartCoordinateInSection(position.y),
            MinecraftCoordinates.quartCoordinateInSection(position.z),
            value,
        )

    /** Replaces one absolute block's biome cell after validating the supplied Section context. */
    fun replaceBiome(sectionPosition: SectionPosition, position: BlockPosition, value: M): M =
        replaceBiome(local(sectionPosition, position), value)

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

    private fun local(sectionPosition: SectionPosition, position: BlockPosition): LocalBlockPosition {
        require(sectionPosition.y == sectionY) {
            "Section position $sectionPosition does not describe Section Y $sectionY"
        }
        return sectionPosition.local(position)
    }
}

/** A mutable, positionless, detached semantic Chunk. */
class Chunk<B : Any, M : Any>(
    metadata: ChunkMetadata,
    val layout: ChunkLayout,
    sections: Collection<ChunkSection<B, M>> = emptyList(),
    blockEntities: Collection<BlockEntity> = emptyList(),
    val defaultBlockState: B,
    val defaultBiome: M,
) {
    private val sectionsByY = sections.associateByTo(linkedMapOf(), ChunkSection<B, M>::sectionY)
    private val blockEntitiesByPosition = blockEntities.associateByTo(linkedMapOf(), BlockEntity::position)
    private var chunkMetadata = metadata.snapshot()

    var metadata: ChunkMetadata
        get() = chunkMetadata.snapshot()
        set(value) {
            val snapshot = value.snapshot()
            require(snapshot.lightOnlySections.keys.none(sectionsByY::containsKey)) {
                "A Chunk stores the same Section lighting in both semantic and light-only sections"
            }
            chunkMetadata = snapshot
        }

    init {
        require(sectionsByY.size == sections.size) { "A Chunk contains duplicate Section Y coordinates" }
        require(sectionsByY.keys.all { it in layout }) { "A Chunk contains a Section outside its layout" }
        require(blockEntitiesByPosition.size == blockEntities.size) {
            "A Chunk contains duplicate Block Entity positions"
        }
        require(blockEntitiesByPosition.keys.all { position -> layout.containsBlockY(position.y) }) {
            "A Chunk contains a Block Entity outside its layout"
        }
        require(chunkMetadata.lightOnlySections.keys.none(sectionsByY::containsKey)) {
            "A Chunk stores the same Section lighting in both semantic and light-only sections"
        }
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

    /** Finds an absolute Section after validating the supplied positionless Chunk context. */
    fun section(chunkPosition: ChunkPosition, position: SectionPosition): ChunkSection<B, M>? {
        require(position.chunk == chunkPosition) { "Section $position does not belong to Chunk $chunkPosition" }
        return section(position.y)
    }

    fun getOrCreateSection(sectionY: Int): ChunkSection<B, M> {
        require(sectionY in layout) { "Section Y $sectionY is outside $layout" }
        return sectionsByY.getOrPut(sectionY) {
            val lighting = chunkMetadata.lightOnlySections[sectionY]
            if (lighting != null) {
                chunkMetadata = chunkMetadata.copy(lightOnlySections = chunkMetadata.lightOnlySections - sectionY)
            }
            ChunkSection(
                sectionY = sectionY,
                blockStates = PalettedContainer(SECTION_BLOCK_COUNT, defaultBlockState),
                biomes = PalettedContainer(SECTION_BIOME_COUNT, defaultBiome),
                blockLight = lighting?.blockLight,
                skyLight = lighting?.skyLight,
            )
        }
    }

    /** Installs one Section and returns the previous Section at the same Y coordinate. */
    fun setSection(section: ChunkSection<B, M>): ChunkSection<B, M>? {
        require(section.sectionY in layout) { "Section Y ${section.sectionY} is outside $layout" }
        if (chunkMetadata.lightOnlySections.containsKey(section.sectionY)) {
            chunkMetadata = chunkMetadata.copy(
                lightOnlySections = chunkMetadata.lightOnlySections - section.sectionY,
            )
        }
        return sectionsByY.put(section.sectionY, section)
    }

    /** Installs one absolute Section after validating the supplied positionless Chunk context. */
    fun setSection(
        chunkPosition: ChunkPosition,
        position: SectionPosition,
        section: ChunkSection<B, M>,
    ): ChunkSection<B, M>? {
        require(position.chunk == chunkPosition) { "Section $position does not belong to Chunk $chunkPosition" }
        require(section.sectionY == position.y) {
            "Section value Y ${section.sectionY} does not match position Y ${position.y}"
        }
        return setSection(section)
    }

    /** Removes one semantic Section while retaining any lighting as a light-only Section. */
    fun removeSection(sectionY: Int): ChunkSection<B, M>? {
        val removed = sectionsByY.remove(sectionY) ?: return null
        if (removed.blockLight != null || removed.skyLight != null) {
            val lighting = SectionLighting(removed.blockLight, removed.skyLight)
            chunkMetadata = chunkMetadata.copy(
                lightOnlySections = chunkMetadata.lightOnlySections + (sectionY to lighting),
            )
        }
        return removed
    }

    /** Removes one absolute Section after validating the supplied positionless Chunk context. */
    fun removeSection(chunkPosition: ChunkPosition, position: SectionPosition): ChunkSection<B, M>? {
        require(position.chunk == chunkPosition) { "Section $position does not belong to Chunk $chunkPosition" }
        return removeSection(position.y)
    }

    /** Finds or creates an absolute Section after validating the supplied positionless Chunk context. */
    fun getOrCreateSection(chunkPosition: ChunkPosition, position: SectionPosition): ChunkSection<B, M> {
        require(position.chunk == chunkPosition) { "Section $position does not belong to Chunk $chunkPosition" }
        return getOrCreateSection(position.y)
    }

    operator fun get(position: ChunkBlockPosition): B {
        require(layout.containsBlockY(position.y)) { "Block Y ${position.y} is outside $layout" }
        return section(position.sectionY)?.get(position.localInSection) ?: defaultBlockState
    }

    operator fun set(position: ChunkBlockPosition, value: B) {
        replaceBlock(position, value)
    }

    fun block(localX: Int, y: Int, localZ: Int): B = get(ChunkBlockPosition(localX, y, localZ))

    fun block(position: ChunkBlockPosition): B = get(position)

    /** Reads an absolute block after validating the supplied positionless Chunk context. */
    fun block(chunkPosition: ChunkPosition, position: BlockPosition): B = block(chunkPosition.local(position))

    fun setBlock(localX: Int, y: Int, localZ: Int, value: B) {
        set(ChunkBlockPosition(localX, y, localZ), value)
    }

    fun setBlock(position: ChunkBlockPosition, value: B) {
        set(position, value)
    }

    /** Writes an absolute block after validating the supplied positionless Chunk context. */
    fun setBlock(chunkPosition: ChunkPosition, position: BlockPosition, value: B) {
        setBlock(chunkPosition.local(position), value)
    }

    /** Replaces one Chunk-local block and returns its previous state. */
    fun replaceBlock(localX: Int, y: Int, localZ: Int, value: B): B =
        replaceBlock(ChunkBlockPosition(localX, y, localZ), value)

    /** Replaces one Chunk-local block and returns its previous state. */
    fun replaceBlock(position: ChunkBlockPosition, value: B): B {
        require(layout.containsBlockY(position.y)) { "Block Y ${position.y} is outside $layout" }
        val section = sectionsByY[position.sectionY]
        if (section == null && value == defaultBlockState) return defaultBlockState
        return (section ?: getOrCreateSection(position.sectionY)).replaceBlock(position.localInSection, value)
    }

    /** Replaces one absolute block after validating the supplied positionless Chunk context. */
    fun replaceBlock(chunkPosition: ChunkPosition, position: BlockPosition, value: B): B =
        replaceBlock(chunkPosition.local(position), value)

    /** Reads the biome cell containing the supplied Chunk-local X/Z and absolute block Y. */
    fun biome(localX: Int, y: Int, localZ: Int): M {
        val position = ChunkBlockPosition(localX, y, localZ)
        require(layout.containsBlockY(y)) { "Block Y $y is outside $layout" }
        return section(position.sectionY)?.biome(
            MinecraftCoordinates.quartCoordinateInSection(localX),
            MinecraftCoordinates.quartCoordinateInSection(y),
            MinecraftCoordinates.quartCoordinateInSection(localZ),
        )
            ?: defaultBiome
    }

    fun biome(position: ChunkBlockPosition): M = biome(position.x, position.y, position.z)

    /** Reads an absolute biome cell after validating the supplied positionless Chunk context. */
    fun biome(chunkPosition: ChunkPosition, position: BlockPosition): M = biome(chunkPosition.local(position))

    /** Writes the 4 by 4 by 4 biome cell containing the supplied Chunk-local X/Z and absolute block Y. */
    fun setBiome(localX: Int, y: Int, localZ: Int, value: M) {
        replaceBiome(localX, y, localZ, value)
    }

    fun setBiome(position: ChunkBlockPosition, value: M) {
        setBiome(position.x, position.y, position.z, value)
    }

    /** Writes an absolute biome cell after validating the supplied positionless Chunk context. */
    fun setBiome(chunkPosition: ChunkPosition, position: BlockPosition, value: M) {
        setBiome(chunkPosition.local(position), value)
    }

    /** Replaces one Chunk-local block's biome cell and returns its previous value. */
    fun replaceBiome(localX: Int, y: Int, localZ: Int, value: M): M {
        val position = ChunkBlockPosition(localX, y, localZ)
        require(layout.containsBlockY(y)) { "Block Y $y is outside $layout" }
        val section = sectionsByY[position.sectionY]
        if (section == null && value == defaultBiome) return defaultBiome
        return (section ?: getOrCreateSection(position.sectionY)).replaceBiome(position.localInSection, value)
    }

    fun replaceBiome(position: ChunkBlockPosition, value: M): M =
        replaceBiome(position.x, position.y, position.z, value)

    /** Replaces one absolute biome cell after validating the supplied positionless Chunk context. */
    fun replaceBiome(chunkPosition: ChunkPosition, position: BlockPosition, value: M): M =
        replaceBiome(chunkPosition.local(position), value)

    fun blockEntity(position: ChunkBlockPosition): BlockEntity? = blockEntitiesByPosition[position]

    /** Finds an absolute Block Entity after validating the supplied positionless Chunk context. */
    fun blockEntity(chunkPosition: ChunkPosition, position: BlockPosition): BlockEntity? =
        blockEntity(chunkPosition.local(position))

    fun hasBlockEntity(position: ChunkBlockPosition): Boolean = blockEntitiesByPosition.containsKey(position)

    /** Checks an absolute Block Entity position after validating the supplied positionless Chunk context. */
    fun hasBlockEntity(chunkPosition: ChunkPosition, position: BlockPosition): Boolean =
        hasBlockEntity(chunkPosition.local(position))

    /** Installs one Block Entity and returns the previous value at the same position. */
    fun setBlockEntity(blockEntity: BlockEntity): BlockEntity? {
        require(layout.containsBlockY(blockEntity.position.y)) {
            "Block Entity Y ${blockEntity.position.y} is outside $layout"
        }
        return blockEntitiesByPosition.put(blockEntity.position, blockEntity)
    }

    fun setBlockEntity(
        position: ChunkBlockPosition,
        type: String,
        persistentData: NbtCompound = NbtCompound(emptyMap()),
    ): BlockEntity? = setBlockEntity(BlockEntity(type, position, persistentData))

    /** Installs one absolute Block Entity after validating the supplied positionless Chunk context. */
    fun setBlockEntity(
        chunkPosition: ChunkPosition,
        position: BlockPosition,
        type: String,
        persistentData: NbtCompound = NbtCompound(emptyMap()),
    ): BlockEntity? = setBlockEntity(chunkPosition.local(position), type, persistentData)

    fun removeBlockEntity(position: ChunkBlockPosition): BlockEntity? = blockEntitiesByPosition.remove(position)

    /** Removes an absolute Block Entity after validating the supplied positionless Chunk context. */
    fun removeBlockEntity(chunkPosition: ChunkPosition, position: BlockPosition): BlockEntity? =
        removeBlockEntity(chunkPosition.local(position))

    /** Creates an independently mutable Chunk snapshot. Caller-supplied block and biome values are retained. */
    fun snapshot(): Chunk<B, M> = Chunk(
        metadata = chunkMetadata,
        layout = layout,
        sections = sectionsByY.values.map(ChunkSection<B, M>::snapshot),
        blockEntities = blockEntitiesByPosition.values.map(BlockEntity::snapshot),
        defaultBlockState = defaultBlockState,
        defaultBiome = defaultBiome,
    )
}

private fun ChunkMetadata.snapshot(): ChunkMetadata =
    copy(lightOnlySections = lightOnlySections.toMap())

private fun NbtCompound.requireNoBlockEntityStructureFields(): NbtCompound {
    val reserved = value.keys intersect BLOCK_ENTITY_STRUCTURE_FIELDS
    require(reserved.isEmpty()) {
        "Block Entity persistent data cannot contain structural fields: ${reserved.sorted().joinToString()}"
    }
    return this
}

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
