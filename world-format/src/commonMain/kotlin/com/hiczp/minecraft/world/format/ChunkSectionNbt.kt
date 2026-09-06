package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

internal fun decodeSection(
    nbtCompound: NbtCompound,
    chunkContext: ChunkContext,
    mappings: NbtPropertyReadMappings,
): Pair<Int, ChunkSection> {
    val y = nbtCompound.optionalTag<NbtByte>("Y")?.value?.toInt() ?: 0
    val blockStates = nbtCompound.optionalTag<NbtCompound>("block_states")
    val biomes = nbtCompound.optionalTag<NbtCompound>("biomes")
    val terrain = if (blockStates == null && biomes == null) null else {
        require(y in chunkContext.dimensionTypeLayout.chunkLayout) { "Section terrain Y $y is outside the build range" }
        SectionTerrain(
            blockStates?.let { decodeNbtPalette(it, SECTION_BLOCK_COUNT, 4, ::decodeBlockState) }
                ?: PalettedContainer(SECTION_BLOCK_COUNT, chunkContext.defaultBlockState),
            biomes?.let { decodeNbtPalette(it, SECTION_BIOME_COUNT, 1) { tag -> BiomeId.parse(tag.string()) } }
                ?: PalettedContainer(SECTION_BIOME_COUNT, chunkContext.defaultBiome),
            SectionStatistics(null, null, null, null),
        )
    }
    return y to ChunkSection(
        terrain,
        SectionLighting(
            nbtCompound.optionalTag<NbtByteArray>("BlockLight")?.toLightLayer(),
            nbtCompound.optionalTag<NbtByteArray>("SkyLight")?.toLightLayer(),
        ),
        mappings.readProperties(nbtCompound, NbtPropertyScope.Section, SECTION_NBT_FIELDS),
    )
}

internal fun encodeSection(
    y: Int,
    chunkSection: ChunkSection,
    chunkLayout: ChunkLayout,
    mappings: NbtPropertyWriteMappings,
): NbtCompound {
    require(y in Byte.MIN_VALUE..Byte.MAX_VALUE) { "Section Y $y cannot be stored in an NBT Byte" }
    val fields = mappings.writeProperties(chunkSection.properties, NbtPropertyScope.Section, SECTION_NBT_FIELDS)
    fields["Y"] = NbtByte(y.toByte())
    chunkSection.terrain?.let { terrain ->
        require(y in chunkLayout) { "Section terrain Y $y is outside the build range" }
        terrain.requireShape()
        fields["block_states"] = encodeNbtPalette(terrain.blockStates, 4, ::encodeBlockState)
        fields["biomes"] = encodeNbtPalette(terrain.biomes, 1) { biomeId -> NbtString(biomeId.toString()) }
    }
    chunkSection.lighting.blockLight?.let { fields["BlockLight"] = it.toNbt() }
    chunkSection.lighting.skyLight?.let { fields["SkyLight"] = it.toNbt() }
    return NbtCompound(fields)
}

internal fun decodeBlockState(nbtTag: NbtTag): BlockState {
    val nbtCompound = nbtTag.compound()
    return BlockState(
        BlockId.parse(nbtCompound.string("Name")),
        StateProperties(nbtCompound.optionalTag<NbtCompound>("Properties")?.value?.mapValues { (_, tag) -> tag.string() }
            .orEmpty()),
    )
}

internal fun encodeBlockState(blockState: BlockState): NbtCompound = NbtCompound(buildMap {
    put("Name", NbtString(blockState.blockId.toString()))
    if (blockState.properties.size != 0) {
        put("Properties", NbtCompound(blockState.properties.associate { (name, value) -> name to NbtString(value) }))
    }
})

private fun <T : Any> decodeNbtPalette(
    nbtCompound: NbtCompound,
    size: Int,
    minimumBits: Int,
    decode: (NbtTag) -> T,
): PalettedContainer<T> {
    val palette = nbtCompound.requiredTag<NbtList>("palette").value.map(decode)
    require(palette.isNotEmpty()) { "An NBT palette must not be empty" }
    if (palette.size == 1) return PalettedContainer(size, palette.single())
    val bits = maxOf(minimumBits, bitsForPaletteSize(palette.size))
    return PalettedContainer.fromPalette(palette, unpackNbtValues(nbtCompound.requiredTag("data"), bits, size))
}

private fun <T : Any> encodeNbtPalette(
    palettedContainer: PalettedContainer<T>,
    minimumBits: Int,
    encode: (T) -> NbtTag,
): NbtCompound {
    val compactPalette = palettedContainer.compactSnapshot()
    return NbtCompound(buildMap {
        put("palette", NbtList(compactPalette.values.map(encode)))
        if (compactPalette.values.size > 1) {
            put("data", packNbtValues(compactPalette.rawIds, maxOf(minimumBits, compactPalette.bitsPerEntry)))
        }
    })
}

internal fun decodeBlockEntity(
    nbtCompound: NbtCompound,
    mappings: NbtPropertyReadMappings,
): Pair<BlockPosition, BlockEntity> {
    val blockEntityTypeId = BlockEntityTypeId.parse(nbtCompound.string("id"))
    return nbtCompound.blockPosition() to BlockEntity(
        blockEntityTypeId,
        decodeComponents(nbtCompound.optionalTag<NbtCompound>("components") ?: NbtCompound(emptyMap()), mappings),
        mappings.readProperties(
            nbtCompound,
            NbtPropertyScope("block_entity", blockEntityTypeId.toString()),
            BLOCK_ENTITY_NBT_FIELDS
        ),
    )
}

internal fun encodeBlockEntity(
    blockPosition: BlockPosition,
    blockEntity: BlockEntity,
    mappings: NbtPropertyWriteMappings,
): NbtCompound {
    val fields = mappings.writeProperties(
        blockEntity.properties,
        NbtPropertyScope("block_entity", blockEntity.blockEntityTypeId.toString()),
        BLOCK_ENTITY_NBT_FIELDS,
    )
    fields["id"] = NbtString(blockEntity.blockEntityTypeId.toString())
    fields["x"] = NbtInt(blockPosition.x)
    fields["y"] = NbtInt(blockPosition.y)
    fields["z"] = NbtInt(blockPosition.z)
    if (blockEntity.components.entries.isNotEmpty()) fields["components"] =
        encodeComponents(blockEntity.components, mappings)
    return NbtCompound(fields)
}

private val SECTION_NBT_FIELDS = setOf("Y", "block_states", "biomes", "BlockLight", "SkyLight")
private val BLOCK_ENTITY_NBT_FIELDS = setOf("id", "x", "y", "z", "components")
