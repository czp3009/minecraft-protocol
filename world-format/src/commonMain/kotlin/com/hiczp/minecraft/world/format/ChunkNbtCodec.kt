package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Selected-release conversion between unnamed-root Chunk NBT and a positioned semantic [Chunk].
 *
 * `DataVersion` is carried by [ChunkStorageMetadata] but is not compared with a library- or caller-selected version.
 * Decoding always supplies that storage metadata; encoding rejects a packet-derived Chunk until the caller supplies it.
 */
class ChunkNbtCodec<B : Any, M : Any>(
    val chunkCodecContext: ChunkCodecContext<B, M>,
    val nbtFormat: NbtFormat = NbtFormat(
        NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED),
    ),
) {
    /** Decodes a Chunk using the position carried by its NBT root. */
    fun decodeFromSource(source: Source): Chunk<B, M> = decodeDocument(nbtFormat.decodeDocumentFromSource(source))

    fun encodeToSink(chunk: Chunk<B, M>, sink: Sink) {
        nbtFormat.encodeDocumentToSink(encodeDocument(chunk), sink)
    }

    /** Decodes a Chunk using the position carried by its NBT root. */
    fun decodeDocument(nbtDocument: NbtDocument): Chunk<B, M> {
        val root = nbtDocument.root

        val dataVersion = root.requireInt(DATA_VERSION)
        val chunkPosition = ChunkPosition(root.requireInt(X_POS), root.requireInt(Z_POS))

        val sections = mutableListOf<ChunkSection<B, M>>()
        val lightOnlySections = linkedMapOf<Int, SectionLighting>()
        root.requireList(SECTIONS).forEach { nbtTag ->
            val section = nbtTag as? NbtCompound
                ?: throw ChunkNbtFormatException("Chunk sections must contain compounds")
            val sectionY = section.requireByte(SECTION_Y).toInt()
            val blockStatesTag = section[BLOCK_STATES]
            val biomesTag = section[BIOMES]
            if ((blockStatesTag == null) != (biomesTag == null)) {
                throw ChunkNbtFormatException(
                    "Section Y $sectionY must contain both block_states and biomes or neither",
                )
            }
            val blockLight = section.optionalByteArray(BLOCK_LIGHT)
            val skyLight = section.optionalByteArray(SKY_LIGHT)
            if (blockStatesTag == null) {
                if (blockLight != null || skyLight != null) {
                    lightOnlySections[sectionY] = try {
                        SectionLighting(blockLight, skyLight)
                    } catch (failure: IllegalArgumentException) {
                        throw ChunkNbtFormatException("Section Y $sectionY has invalid lighting", failure)
                    }
                }
                return@forEach
            }
            val blockStates = decodeBlockStates(blockStatesTag as? NbtCompound, sectionY)
            val biomes = decodeBiomes(biomesTag as? NbtCompound, sectionY)
            sections += try {
                ChunkSection(
                    sectionY = sectionY,
                    blockStates = blockStates,
                    biomes = biomes,
                    blockLight = blockLight,
                    skyLight = skyLight,
                )
            } catch (failure: IllegalArgumentException) {
                throw ChunkNbtFormatException("Section Y $sectionY has invalid lighting", failure)
            }
        }

        val status = root.requireString(STATUS)
        val blockEntities = root.requireList(BLOCK_ENTITIES).value.mapIndexed { index, nbtTag ->
            decodeBlockEntity(nbtTag, index)
        }
        return try {
            val chunkMetadata = ChunkMetadata(
                chunkStorageMetadata = ChunkStorageMetadata(
                    dataVersion = dataVersion,
                    lastUpdateTime = root.requireLong(LAST_UPDATE),
                    inhabitedTime = root.requireLong(INHABITED_TIME),
                    status = status,
                    lightCorrect = root.optionalBoolean(IS_LIGHT_ON) ?: false,
                    upgradeData = root.optionalCompound(UPGRADE_DATA),
                    blendingData = root.optionalCompound(BLENDING_DATA),
                    belowZeroRetrogen = root.optionalCompound(BELOW_ZERO_RETROGEN),
                    carvingMask = root.optionalLongArray(CARVING_MASK),
                    blockTicks = root.requireList(BLOCK_TICKS),
                    fluidTicks = root.requireList(FLUID_TICKS),
                    postProcessing = root.requireList(POST_PROCESSING),
                    entities = root.optionalList(ENTITIES),
                    structures = root.requireCompound(STRUCTURES),
                ),
                heightmaps = root.requireCompound(HEIGHTMAPS),
                lightOnlySections = lightOnlySections,
            )
            Chunk(
                chunkPosition = chunkPosition,
                chunkMetadata = chunkMetadata,
                chunkLayout = chunkCodecContext.chunkLayout,
                sections = sections,
                blockEntities = blockEntities,
                defaultBlockState = chunkCodecContext.chunkDataRegistries.blockStates.defaultValue,
                defaultBiome = chunkCodecContext.chunkDataRegistries.biomes.defaultValue,
            )
        } catch (failure: IllegalArgumentException) {
            throw ChunkNbtFormatException("Invalid Chunk", failure)
        }
    }

    fun encodeDocument(chunk: Chunk<B, M>): NbtDocument {
        val chunkMetadata = chunk.chunkMetadata
        val chunkStorageMetadata = chunkMetadata.chunkStorageMetadata
            ?: throw ChunkNbtFormatException("Persistent Chunk encoding requires ChunkStorageMetadata")
        val root = linkedMapOf<String, NbtTag>()
        root[DATA_VERSION] = NbtInt(chunkStorageMetadata.dataVersion)
        root[X_POS] = NbtInt(chunk.chunkPosition.x)
        root[Y_POS] = NbtInt(chunkCodecContext.chunkLayout.minSectionY)
        root[Z_POS] = NbtInt(chunk.chunkPosition.z)
        root[LAST_UPDATE] = NbtLong(chunkStorageMetadata.lastUpdateTime)
        root[INHABITED_TIME] = NbtLong(chunkStorageMetadata.inhabitedTime)
        root[STATUS] = NbtString(chunkStorageMetadata.status)
        chunkStorageMetadata.blendingData?.let { root[BLENDING_DATA] = it }
        chunkStorageMetadata.belowZeroRetrogen?.let { root[BELOW_ZERO_RETROGEN] = it }
        chunkStorageMetadata.upgradeData?.let { root[UPGRADE_DATA] = it }

        val sections = linkedMapOf<Int, NbtCompound>()
        chunk.sections.sortedBy(ChunkSection<B, M>::sectionY).forEach { chunkSection ->
            if (chunkSection.sectionY !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                throw ChunkNbtFormatException("Section Y ${chunkSection.sectionY} cannot be stored as TAG_Byte")
            }
            val value = linkedMapOf<String, NbtTag>()
            value[BLOCK_STATES] = encodeBlockStates(chunkSection.blockStates)
            value[BIOMES] = encodeBiomes(chunkSection.biomes)
            chunkSection.blockLight?.let { value[BLOCK_LIGHT] = it }
            chunkSection.skyLight?.let { value[SKY_LIGHT] = it }
            value[SECTION_Y] = NbtByte(chunkSection.sectionY.toByte())
            sections[chunkSection.sectionY] = NbtCompound(value)
        }
        chunkMetadata.lightOnlySections.entries
            .sortedBy { it.key }
            .forEach { (sectionY, sectionLighting) ->
                if (sections.containsKey(sectionY)) return@forEach
                if (sectionY !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                    throw ChunkNbtFormatException("Section Y $sectionY cannot be stored as TAG_Byte")
                }
                val value = linkedMapOf<String, NbtTag>()
                sectionLighting.blockLight?.let { value[BLOCK_LIGHT] = it }
                sectionLighting.skyLight?.let { value[SKY_LIGHT] = it }
                value[SECTION_Y] = NbtByte(sectionY.toByte())
                sections[sectionY] = NbtCompound(value)
            }
        val encodedSections = sections.entries.sortedBy { it.key }.map { it.value }
        root[SECTIONS] = NbtList(encodedSections)
        if (chunkStorageMetadata.lightCorrect) root[IS_LIGHT_ON] = NbtByte(1)
        root[BLOCK_ENTITIES] = NbtList(chunk.blockEntities.map { blockEntity ->
            encodeBlockEntity(blockEntity)
        })
        chunkStorageMetadata.entities?.let { root[ENTITIES] = it }
        chunkStorageMetadata.carvingMask?.let { root[CARVING_MASK] = it }
        root[BLOCK_TICKS] = chunkStorageMetadata.blockTicks
        root[FLUID_TICKS] = chunkStorageMetadata.fluidTicks
        root[POST_PROCESSING] = chunkStorageMetadata.postProcessing
        root[HEIGHTMAPS] = chunkMetadata.heightmaps
        root[STRUCTURES] = chunkStorageMetadata.structures
        return NbtDocument(NbtCompound(root))
    }

    private fun decodeBlockEntity(nbtTag: NbtTag, index: Int): BlockEntity {
        val nbtCompound = nbtTag as? NbtCompound
            ?: throw ChunkNbtFormatException("Chunk Block Entity $index is not a compound")
        val type = nbtCompound.requireString(BLOCK_ENTITY_ID)
        val absolutePosition = BlockPosition(
            x = nbtCompound.requireInt(BLOCK_ENTITY_X),
            y = nbtCompound.requireInt(BLOCK_ENTITY_Y),
            z = nbtCompound.requireInt(BLOCK_ENTITY_Z),
        )
        val persistentData = linkedMapOf<String, NbtTag>()
        nbtCompound.forEachEntry { name, value ->
            if (name !in BLOCK_ENTITY_STRUCTURE_FIELDS) persistentData[name] = value
        }
        return try {
            BlockEntity(type, absolutePosition, NbtCompound(persistentData))
        } catch (failure: IllegalArgumentException) {
            throw ChunkNbtFormatException("Invalid Chunk Block Entity $index", failure)
        }
    }

    private fun encodeBlockEntity(blockEntity: BlockEntity): NbtCompound {
        val value = linkedMapOf<String, NbtTag>()
        blockEntity.persistentData.forEachEntry { name, nbtTag ->
            if (name !in BLOCK_ENTITY_STRUCTURE_FIELDS) value[name] = nbtTag
        }
        value[BLOCK_ENTITY_ID] = NbtString(blockEntity.type)
        value[BLOCK_ENTITY_X] = NbtInt(blockEntity.blockPosition.x)
        value[BLOCK_ENTITY_Y] = NbtInt(blockEntity.blockPosition.y)
        value[BLOCK_ENTITY_Z] = NbtInt(blockEntity.blockPosition.z)
        return NbtCompound(value)
    }

    private fun decodeBlockStates(tag: NbtCompound?, sectionY: Int): PalettedContainer<B> {
        val nbtCompound = tag ?: throw ChunkNbtFormatException("Section Y $sectionY block_states is not a compound")
        val palette = nbtCompound.requireList(PALETTE).value.mapIndexed { index, entry ->
            val state = entry as? NbtCompound
                ?: throw ChunkNbtFormatException("Block-state palette entry $index is not a compound")
            val properties = state.optionalCompound(PROPERTIES)?.value?.mapValues { (name, value) ->
                (value as? NbtString)?.value
                    ?: throw ChunkNbtFormatException("Block-state property $name is not a string")
            }.orEmpty()
            val blockStateDescriptor = try {
                BlockStateDescriptor(state.requireString(NAME), properties)
            } catch (failure: IllegalArgumentException) {
                throw ChunkNbtFormatException("Invalid block-state palette entry $index", failure)
            }
            chunkCodecContext.chunkDataRegistries.blockStates.resolve(blockStateDescriptor)
                ?: throw ChunkNbtFormatException("Unknown block state $blockStateDescriptor")
        }
        return decodePalette(nbtCompound, palette, SECTION_BLOCK_COUNT, BLOCK_STATE_MIN_BITS, "block_states")
    }

    private fun decodeBiomes(tag: NbtCompound?, sectionY: Int): PalettedContainer<M> {
        val nbtCompound = tag ?: throw ChunkNbtFormatException("Section Y $sectionY biomes is not a compound")
        val palette = nbtCompound.requireList(PALETTE).value.mapIndexed { index, entry ->
            val name = (entry as? NbtString)?.value
                ?: throw ChunkNbtFormatException("Biome palette entry $index is not a string")
            chunkCodecContext.chunkDataRegistries.biomes.resolve(name)
                ?: throw ChunkNbtFormatException("Unknown biome $name")
        }
        return decodePalette(nbtCompound, palette, SECTION_BIOME_COUNT, BIOME_MIN_BITS, "biomes")
    }

    private fun <T : Any> decodePalette(
        nbtCompound: NbtCompound,
        palette: List<T>,
        entryCount: Int,
        minimumBits: Int,
        description: String,
    ): PalettedContainer<T> {
        if (palette.isEmpty()) throw ChunkNbtFormatException("$description palette is empty")
        if (palette.size == 1) {
            return PalettedContainer(entryCount, palette.single())
        }
        val bits = maxOf(minimumBits, bitsForPaletteSize(palette.size))
        val packed = nbtCompound.requireLongArray(DATA)
        val ids = unpackPaletteIds(packed, bits, entryCount, description)
        return try {
            PalettedContainer.fromPalette(palette, ids)
        } catch (failure: IllegalArgumentException) {
            throw ChunkNbtFormatException("Invalid $description palette data", failure)
        }
    }

    private fun encodeBlockStates(palettedContainer: PalettedContainer<B>): NbtCompound {
        val compactPalette = palettedContainer.compactSnapshot()
        val palette = compactPalette.values.mapIndexed { index, value ->
            val blockStateDescriptor = chunkCodecContext.chunkDataRegistries.blockStates.describe(value)
                ?: throw ChunkNbtFormatException("Block-state value at palette index $index cannot be described")
            val state = linkedMapOf<String, NbtTag>(NAME to NbtString(blockStateDescriptor.name))
            if (blockStateDescriptor.properties.isNotEmpty()) {
                state[PROPERTIES] = NbtCompound(
                    blockStateDescriptor.properties.mapValues { (_, property) -> NbtString(property) },
                )
            }
            NbtCompound(state)
        }
        return encodePalette(palette, compactPalette.rawIds, BLOCK_STATE_MIN_BITS)
    }

    private fun encodeBiomes(palettedContainer: PalettedContainer<M>): NbtCompound {
        val compactPalette = palettedContainer.compactSnapshot()
        val palette = compactPalette.values.mapIndexed { index, value ->
            val name = chunkCodecContext.chunkDataRegistries.biomes.name(value)
                ?: throw ChunkNbtFormatException("Biome value at palette index $index has no persistent name")
            if (name.isBlank()) {
                throw ChunkNbtFormatException("Biome value at palette index $index has a blank persistent name")
            }
            NbtString(name)
        }
        return encodePalette(palette, compactPalette.rawIds, BIOME_MIN_BITS)
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
    if (packed.size < expectedLongs) {
        throw ChunkNbtFormatException(
            "$description data has ${packed.size} longs, needs $expectedLongs for $entryCount $bits-bit entries",
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
    ids.forEachIndexed { index, id ->
        val cell = index / valuesPerLong
        val shift = index % valuesPerLong * bits
        packed[cell] = packed[cell] or (id.toLong() shl shift)
    }
    return packed
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
    val nbtByte = (this[name] ?: return null) as? NbtByte ?: wrongType(name, "Boolean TAG_Byte")
    return when (nbtByte.value.toInt()) {
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
private const val BLOCK_ENTITY_ID = "id"
private const val BLOCK_ENTITY_X = "x"
private const val BLOCK_ENTITY_Y = "y"
private const val BLOCK_ENTITY_Z = "z"
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
