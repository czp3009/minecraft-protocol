package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.io.Sink
import kotlinx.io.Source

/** Selected-release conversion between unnamed-root Chunk NBT and a positionless semantic [Chunk]. */
class ChunkNbtCodec<B : Any, M : Any>(
    val context: ChunkNbtContext<B, M>,
    val nbt: NbtFormat = NbtFormat(
        NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED),
    ),
) {
    init {
        require(nbt.configuration.rootEncoding == NbtRootEncoding.UNNAMED) {
            "Region Chunk NBT requires NbtRootEncoding.UNNAMED"
        }
    }

    fun decodeFromSource(source: Source, expectedPosition: ChunkPosition): Chunk<B, M> =
        decodeDocument(nbt.decodeDocumentFromSource(source), expectedPosition)

    fun encodeToSink(chunk: Chunk<B, M>, position: ChunkPosition, sink: Sink) {
        nbt.encodeDocumentToSink(encodeDocument(chunk, position), sink)
    }

    fun decodeDocument(document: NbtDocument, expectedPosition: ChunkPosition): Chunk<B, M> {
        val root = document.root
        root.rejectUnknownKeys(ROOT_KEYS, "Chunk")

        val dataVersion = root.requireInt(DATA_VERSION)
        if (dataVersion != context.expectedDataVersion) {
            throw ChunkNbtFormatException(
                "Expected Chunk data version ${context.expectedDataVersion}, got $dataVersion",
            )
        }
        val actualPosition = ChunkPosition(root.requireInt(X_POS), root.requireInt(Z_POS))
        if (actualPosition != expectedPosition) {
            throw ChunkNbtFormatException(
                "Expected Chunk position $expectedPosition, got $actualPosition",
            )
        }
        val minSectionY = root.requireInt(Y_POS)
        if (minSectionY != context.layout.minSectionY) {
            throw ChunkNbtFormatException(
                "Expected minimum Section Y ${context.layout.minSectionY}, got $minSectionY",
            )
        }

        val sections = mutableListOf<ChunkSection<B, M>>()
        val lightOnlySections = linkedMapOf<Int, SectionLighting>()
        val seenSectionYs = mutableSetOf<Int>()
        root.requireList(SECTIONS).forEach { tag ->
            val section = tag as? NbtCompound
                ?: throw ChunkNbtFormatException("Chunk sections must contain compounds")
            section.rejectUnknownKeys(SECTION_KEYS, "Chunk Section")
            val sectionY = section.requireByte(SECTION_Y).toInt()
            if (!seenSectionYs.add(sectionY)) {
                throw ChunkNbtFormatException("Chunk contains duplicate Section Y $sectionY")
            }
            val blockStatesTag = section[BLOCK_STATES]
            val biomesTag = section[BIOMES]
            if ((blockStatesTag == null) != (biomesTag == null)) {
                throw ChunkNbtFormatException(
                    "Section Y $sectionY must contain both block_states and biomes or neither",
                )
            }
            val blockLight = section.optionalByteArray(BLOCK_LIGHT)
            val skyLight = section.optionalByteArray(SKY_LIGHT)
            val lighting = if (blockLight != null || skyLight != null) {
                try {
                    SectionLighting(blockLight, skyLight)
                } catch (failure: IllegalArgumentException) {
                    throw ChunkNbtFormatException("Section Y $sectionY has invalid lighting", failure)
                }
            } else {
                null
            }
            if (blockStatesTag == null) {
                if (lighting != null) lightOnlySections[sectionY] = lighting
                return@forEach
            }
            if (sectionY !in context.layout) {
                throw ChunkNbtFormatException(
                    "Section Y $sectionY contains palettes outside ${context.layout}",
                )
            }
            val blockStates = decodeBlockStates(blockStatesTag as? NbtCompound, sectionY)
            val biomes = decodeBiomes(biomesTag as? NbtCompound, sectionY)
            sections += ChunkSection(
                sectionY = sectionY,
                blockStates = blockStates,
                biomes = biomes,
                blockLight = blockLight,
                skyLight = skyLight,
            )
        }

        val status = root.requireString(STATUS)
        if (status.isBlank()) throw ChunkNbtFormatException("Chunk status must not be blank")
        val metadata = ChunkMetadata(
            dataVersion = dataVersion,
            lastUpdateTime = root.requireLong(LAST_UPDATE),
            inhabitedTime = root.requireLong(INHABITED_TIME),
            status = status,
            lightCorrect = root.optionalBoolean(IS_LIGHT_ON) ?: false,
            upgradeData = root.optionalCompound(UPGRADE_DATA),
            blendingData = root.optionalCompound(BLENDING_DATA),
            belowZeroRetrogen = root.optionalCompound(BELOW_ZERO_RETROGEN),
            carvingMask = root.optionalLongArray(CARVING_MASK),
            heightmaps = root.requireCompound(HEIGHTMAPS),
            blockTicks = root.requireList(BLOCK_TICKS),
            fluidTicks = root.requireList(FLUID_TICKS),
            postProcessing = root.requireList(POST_PROCESSING),
            entities = root.optionalList(ENTITIES),
            blockEntities = root.requireList(BLOCK_ENTITIES),
            structures = root.requireCompound(STRUCTURES),
            lightOnlySections = lightOnlySections,
        )
        return Chunk(
            metadata = metadata,
            layout = context.layout,
            sections = sections,
            defaultBlockState = context.registries.blockStates.defaultValue,
            defaultBiome = context.registries.biomes.defaultValue,
        )
    }

    fun encodeDocument(chunk: Chunk<B, M>, position: ChunkPosition): NbtDocument {
        if (chunk.layout != context.layout) {
            throw ChunkNbtFormatException(
                "Chunk layout ${chunk.layout} does not match codec layout ${context.layout}",
            )
        }
        if (chunk.metadata.dataVersion != context.expectedDataVersion) {
            throw ChunkNbtFormatException(
                "Chunk data version ${chunk.metadata.dataVersion} does not match ${context.expectedDataVersion}",
            )
        }
        if (chunk.defaultBlockState != context.registries.blockStates.defaultValue) {
            throw ChunkNbtFormatException("Chunk and codec use different default block states")
        }
        if (chunk.defaultBiome != context.registries.biomes.defaultValue) {
            throw ChunkNbtFormatException("Chunk and codec use different default biomes")
        }
        val metadata = chunk.metadata
        val root = linkedMapOf<String, NbtTag>()
        root[DATA_VERSION] = NbtInt(metadata.dataVersion)
        root[X_POS] = NbtInt(position.x)
        root[Y_POS] = NbtInt(context.layout.minSectionY)
        root[Z_POS] = NbtInt(position.z)
        root[LAST_UPDATE] = NbtLong(metadata.lastUpdateTime)
        root[INHABITED_TIME] = NbtLong(metadata.inhabitedTime)
        root[STATUS] = NbtString(metadata.status)
        metadata.blendingData?.let { root[BLENDING_DATA] = it }
        metadata.belowZeroRetrogen?.let { root[BELOW_ZERO_RETROGEN] = it }
        metadata.upgradeData?.let { root[UPGRADE_DATA] = it }

        val sections = linkedMapOf<Int, NbtCompound>()
        chunk.sections.sortedBy(ChunkSection<B, M>::sectionY).forEach { section ->
            if (section.sectionY !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                throw ChunkNbtFormatException("Section Y ${section.sectionY} cannot be stored as TAG_Byte")
            }
            val value = linkedMapOf<String, NbtTag>()
            value[BLOCK_STATES] = encodeBlockStates(section.blockStates)
            value[BIOMES] = encodeBiomes(section.biomes)
            section.blockLight?.let { value[BLOCK_LIGHT] = it }
            section.skyLight?.let { value[SKY_LIGHT] = it }
            value[SECTION_Y] = NbtByte(section.sectionY.toByte())
            sections[section.sectionY] = NbtCompound(value)
        }
        metadata.lightOnlySections.entries
            .sortedBy { it.key }
            .forEach { (sectionY, lighting) ->
                if (sections.containsKey(sectionY)) {
                    throw ChunkNbtFormatException("Section Y $sectionY has duplicate lighting")
                }
                if (sectionY !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                    throw ChunkNbtFormatException("Section Y $sectionY cannot be stored as TAG_Byte")
                }
                val value = linkedMapOf<String, NbtTag>()
                lighting.blockLight?.let { value[BLOCK_LIGHT] = it }
                lighting.skyLight?.let { value[SKY_LIGHT] = it }
                value[SECTION_Y] = NbtByte(sectionY.toByte())
                sections[sectionY] = NbtCompound(value)
            }
        val encodedSections = sections.entries.sortedBy { it.key }.map { it.value }
        root[SECTIONS] = NbtList(encodedSections)
        if (metadata.lightCorrect) root[IS_LIGHT_ON] = NbtByte(1)
        root[BLOCK_ENTITIES] = metadata.blockEntities
        metadata.entities?.let { root[ENTITIES] = it }
        metadata.carvingMask?.let { root[CARVING_MASK] = it }
        root[BLOCK_TICKS] = metadata.blockTicks
        root[FLUID_TICKS] = metadata.fluidTicks
        root[POST_PROCESSING] = metadata.postProcessing
        root[HEIGHTMAPS] = metadata.heightmaps
        root[STRUCTURES] = metadata.structures
        return NbtDocument(NbtCompound(root))
    }

    private fun decodeBlockStates(tag: NbtCompound?, sectionY: Int): PalettedContainer<B> {
        val compound = tag ?: throw ChunkNbtFormatException("Section Y $sectionY block_states is not a compound")
        compound.rejectUnknownKeys(PALETTED_CONTAINER_KEYS, "Section Y $sectionY block_states")
        val palette = compound.requireList(PALETTE).value.mapIndexed { index, entry ->
            val state = entry as? NbtCompound
                ?: throw ChunkNbtFormatException("Block-state palette entry $index is not a compound")
            state.rejectUnknownKeys(BLOCK_STATE_KEYS, "Block-state palette entry $index")
            val properties = state.optionalCompound(PROPERTIES)?.value?.mapValues { (name, value) ->
                (value as? NbtString)?.value
                    ?: throw ChunkNbtFormatException("Block-state property $name is not a string")
            }.orEmpty()
            val stateName = state.requireString(NAME)
            if (stateName.isBlank()) {
                throw ChunkNbtFormatException("Block-state palette entry $index has a blank name")
            }
            if (properties.any { (name, value) -> name.isBlank() || value.isBlank() }) {
                throw ChunkNbtFormatException("Block-state palette entry $index has a blank property name or value")
            }
            val descriptor = BlockStateDescriptor(stateName, properties)
            context.registries.blockStates.resolve(descriptor)
                ?: throw ChunkNbtFormatException("Unknown block state $descriptor")
        }
        return decodePalette(compound, palette, SECTION_BLOCK_COUNT, BLOCK_STATE_MIN_BITS, "block_states")
    }

    private fun decodeBiomes(tag: NbtCompound?, sectionY: Int): PalettedContainer<M> {
        val compound = tag ?: throw ChunkNbtFormatException("Section Y $sectionY biomes is not a compound")
        compound.rejectUnknownKeys(PALETTED_CONTAINER_KEYS, "Section Y $sectionY biomes")
        val palette = compound.requireList(PALETTE).value.mapIndexed { index, entry ->
            val name = (entry as? NbtString)?.value
                ?: throw ChunkNbtFormatException("Biome palette entry $index is not a string")
            context.registries.biomes.resolve(name)
                ?: throw ChunkNbtFormatException("Unknown biome $name")
        }
        return decodePalette(compound, palette, SECTION_BIOME_COUNT, BIOME_MIN_BITS, "biomes")
    }

    private fun <T : Any> decodePalette(
        compound: NbtCompound,
        palette: List<T>,
        entryCount: Int,
        minimumBits: Int,
        description: String,
    ): PalettedContainer<T> {
        if (palette.isEmpty()) throw ChunkNbtFormatException("$description palette is empty")
        if (palette.size == 1) {
            if (compound[DATA] != null) {
                throw ChunkNbtFormatException("Single-valued $description palette must omit data")
            }
            return PalettedContainer(entryCount, palette.single())
        }
        val bits = maxOf(minimumBits, bitsForPaletteSize(palette.size))
        if (bits > MAX_PALETTE_BITS) {
            throw ChunkNbtFormatException("$description palette needs unsupported $bits-bit entries")
        }
        val packed = compound.requireLongArray(DATA)
        val ids = unpackPaletteIds(packed, bits, entryCount, description)
        ids.forEachIndexed { index, id ->
            if (id !in palette.indices) {
                throw ChunkNbtFormatException("$description entry $index references absent palette ID $id")
            }
        }
        return PalettedContainer.fromPalette(palette, ids)
    }

    private fun encodeBlockStates(container: PalettedContainer<B>): NbtCompound {
        val compact = container.compactSnapshot()
        val palette = compact.values.mapIndexed { index, value ->
            val descriptor = context.registries.blockStates.describe(value)
                ?: throw ChunkNbtFormatException("Block-state value at palette index $index cannot be described")
            val state = linkedMapOf<String, NbtTag>(NAME to NbtString(descriptor.name))
            if (descriptor.properties.isNotEmpty()) {
                state[PROPERTIES] = NbtCompound(
                    descriptor.properties.mapValues { (_, property) -> NbtString(property) },
                )
            }
            NbtCompound(state)
        }
        return encodePalette(palette, compact.rawIds, BLOCK_STATE_MIN_BITS)
    }

    private fun encodeBiomes(container: PalettedContainer<M>): NbtCompound {
        val compact = container.compactSnapshot()
        val palette = compact.values.mapIndexed { index, value ->
            val name = context.registries.biomes.name(value)
                ?: throw ChunkNbtFormatException("Biome value at palette index $index has no persistent name")
            if (name.isBlank()) {
                throw ChunkNbtFormatException("Biome value at palette index $index has a blank persistent name")
            }
            NbtString(name)
        }
        return encodePalette(palette, compact.rawIds, BIOME_MIN_BITS)
    }

    private fun encodePalette(palette: List<NbtTag>, ids: IntArray, minimumBits: Int): NbtCompound {
        val value = linkedMapOf<String, NbtTag>(PALETTE to NbtList(palette))
        if (palette.size > 1) {
            val bits = maxOf(minimumBits, bitsForPaletteSize(palette.size))
            value[DATA] = NbtLongArray(packPaletteIds(ids, bits))
        }
        return NbtCompound(value)
    }
}

private fun unpackPaletteIds(
    packed: NbtLongArray,
    bits: Int,
    entryCount: Int,
    description: String,
): IntArray {
    val valuesPerLong = Long.SIZE_BITS / bits
    val expectedLongs = (entryCount + valuesPerLong - 1) / valuesPerLong
    if (packed.size != expectedLongs) {
        throw ChunkNbtFormatException(
            "$description data has ${packed.size} longs, expected $expectedLongs for $entryCount $bits-bit entries",
        )
    }
    val mask = (1L shl bits) - 1L
    return IntArray(entryCount) { index ->
        val cell = index / valuesPerLong
        val shift = index % valuesPerLong * bits
        (packed[cell] ushr shift and mask).toInt()
    }
}

private fun packPaletteIds(ids: IntArray, bits: Int): LongArray {
    val valuesPerLong = Long.SIZE_BITS / bits
    val packed = LongArray((ids.size + valuesPerLong - 1) / valuesPerLong)
    val maximum = (1L shl bits) - 1L
    ids.forEachIndexed { index, id ->
        require(id >= 0 && id.toLong() <= maximum) { "Palette ID $id does not fit in $bits bits" }
        val cell = index / valuesPerLong
        val shift = index % valuesPerLong * bits
        packed[cell] = packed[cell] or (id.toLong() shl shift)
    }
    return packed
}

private fun NbtCompound.rejectUnknownKeys(known: Set<String>, description: String) {
    val unknown = value.keys - known
    if (unknown.isNotEmpty()) {
        throw ChunkNbtFormatException("$description contains unmodeled fields: ${unknown.sorted().joinToString()}")
    }
}

private fun NbtCompound.requireByte(name: String): Byte =
    (this[name] as? NbtByte)?.value ?: wrongType(name, "TAG_Byte")

private fun NbtCompound.requireInt(name: String): Int =
    (this[name] as? NbtInt)?.value ?: wrongType(name, "TAG_Int")

private fun NbtCompound.requireLong(name: String): Long =
    (this[name] as? NbtLong)?.value ?: wrongType(name, "TAG_Long")

private fun NbtCompound.requireString(name: String): String =
    (this[name] as? NbtString)?.value ?: wrongType(name, "TAG_String")

private fun NbtCompound.requireList(name: String): NbtList =
    this[name] as? NbtList ?: wrongType(name, "TAG_List")

private fun NbtCompound.optionalList(name: String): NbtList? = optional(name, "TAG_List")

private fun NbtCompound.requireCompound(name: String): NbtCompound =
    this[name] as? NbtCompound ?: wrongType(name, "TAG_Compound")

private fun NbtCompound.optionalCompound(name: String): NbtCompound? = optional(name, "TAG_Compound")

private fun NbtCompound.requireLongArray(name: String): NbtLongArray =
    this[name] as? NbtLongArray ?: wrongType(name, "TAG_Long_Array")

private fun NbtCompound.optionalLongArray(name: String): NbtLongArray? = optional(name, "TAG_Long_Array")

private fun NbtCompound.optionalByteArray(name: String): NbtByteArray? = optional(name, "TAG_Byte_Array")

private fun NbtCompound.optionalBoolean(name: String): Boolean? {
    val value = (this[name] ?: return null) as? NbtByte ?: wrongType(name, "Boolean TAG_Byte")
    return when (value.value.toInt()) {
        0 -> false
        1 -> true
        else -> throw ChunkNbtFormatException("Chunk field $name is not a Boolean TAG_Byte")
    }
}

private inline fun <reified T : NbtTag> NbtCompound.optional(name: String, expected: String): T? {
    val value = this[name] ?: return null
    return value as? T ?: wrongType(name, expected)
}

private fun NbtCompound.wrongType(name: String, expected: String): Nothing {
    val actual = this[name]?.let { it::class.simpleName } ?: "missing"
    throw ChunkNbtFormatException("Chunk field $name must be $expected, got $actual")
}

private const val DATA_VERSION = "DataVersion"
private const val X_POS = "xPos"
private const val Y_POS = "yPos"
private const val Z_POS = "zPos"
private const val LAST_UPDATE = "LastUpdate"
private const val INHABITED_TIME = "InhabitedTime"
private const val STATUS = "Status"
private const val BLENDING_DATA = "blending_data"
private const val BELOW_ZERO_RETROGEN = "below_zero_retrogen"
private const val UPGRADE_DATA = "UpgradeData"
private const val IS_LIGHT_ON = "isLightOn"
private const val CARVING_MASK = "carving_mask"
private const val HEIGHTMAPS = "Heightmaps"
private const val BLOCK_TICKS = "block_ticks"
private const val FLUID_TICKS = "fluid_ticks"
private const val POST_PROCESSING = "PostProcessing"
private const val ENTITIES = "entities"
private const val BLOCK_ENTITIES = "block_entities"
private const val STRUCTURES = "structures"
private const val SECTIONS = "sections"
private const val SECTION_Y = "Y"
private const val BLOCK_STATES = "block_states"
private const val BIOMES = "biomes"
private const val BLOCK_LIGHT = "BlockLight"
private const val SKY_LIGHT = "SkyLight"
private const val PALETTE = "palette"
private const val DATA = "data"
private const val NAME = "Name"
private const val PROPERTIES = "Properties"
private const val BLOCK_STATE_MIN_BITS = 4
private const val BIOME_MIN_BITS = 1
private const val MAX_PALETTE_BITS = 32

private val ROOT_KEYS = setOf(
    DATA_VERSION,
    X_POS,
    Y_POS,
    Z_POS,
    LAST_UPDATE,
    INHABITED_TIME,
    STATUS,
    BLENDING_DATA,
    BELOW_ZERO_RETROGEN,
    UPGRADE_DATA,
    IS_LIGHT_ON,
    CARVING_MASK,
    HEIGHTMAPS,
    BLOCK_TICKS,
    FLUID_TICKS,
    POST_PROCESSING,
    ENTITIES,
    BLOCK_ENTITIES,
    STRUCTURES,
    SECTIONS,
)
private val SECTION_KEYS = setOf(SECTION_Y, BLOCK_STATES, BIOMES, BLOCK_LIGHT, SKY_LIGHT)
private val PALETTED_CONTAINER_KEYS = setOf(PALETTE, DATA)
private val BLOCK_STATE_KEYS = setOf(NAME, PROPERTIES)
