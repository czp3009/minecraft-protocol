package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

internal fun decodeStructures(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): ChunkStructures {
    val starts =
        nbtCompound.optionalTag<NbtCompound>("starts")?.value.orEmpty().entries.associateTo(linkedMapOf()) { (id, tag) ->
            val start = tag.compound()
            val structureId = StructureId.parse(id)
            val value = if (start.string("id") == "INVALID") StructureStart.Invalid else StructureStart.Valid(
                ChunkPosition(start.int("ChunkX"), start.int("ChunkZ")), start.int("references", 0),
                start.requiredTag<NbtList>("Children").value.mapTo(mutableListOf()) {
                    decodePiece(
                        it.compound(),
                        mappings
                    )
                },
                mappings.readProperties(start, NbtPropertyScope("structure_start", id), START_NBT_FIELDS),
            )
            structureId to value
        }
    val references: MutableMap<StructureId, MutableSet<ChunkPosition>> =
        nbtCompound.optionalTag<NbtCompound>("References")?.value.orEmpty().entries.associateTo(linkedMapOf()) { (id, tag) ->
            val values = tag as? NbtLongArray
                ?: throw NbtPropertyFormatException("Structure references must be an NBT Long Array")
            StructureId.parse(id) to values.value.mapTo(linkedSetOf(), MinecraftCoordinates::chunkFromPacked)
        }
    return ChunkStructures(
        starts,
        references,
        mappings.readProperties(nbtCompound, NbtPropertyScope("structures"), STRUCTURES_NBT_FIELDS)
    )
}

internal fun encodeStructures(value: ChunkStructures, mappings: NbtPropertyWriteMappings): NbtCompound {
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("structures"), STRUCTURES_NBT_FIELDS)
    fields["starts"] = NbtCompound(value.starts.entries.associate { (structureId, start) ->
        val tag = when (start) {
            StructureStart.Invalid -> NbtCompound(mapOf("id" to NbtString("INVALID")))
            is StructureStart.Valid -> {
                val startFields = mappings.writeProperties(
                    start.properties,
                    NbtPropertyScope("structure_start", structureId.toString()),
                    START_NBT_FIELDS
                )
                startFields["id"] = NbtString(structureId.toString())
                startFields["ChunkX"] = NbtInt(start.chunkPosition.x)
                startFields["ChunkZ"] = NbtInt(start.chunkPosition.z)
                startFields["references"] = NbtInt(start.references)
                startFields["Children"] = NbtList(start.pieces.map { encodePiece(it, mappings) })
                NbtCompound(startFields)
            }
        }
        structureId.toString() to tag
    })
    fields["References"] = NbtCompound(value.references.entries.associate { (id, positions) ->
        id.toString() to NbtLongArray(positions.map(MinecraftCoordinates::packedChunk).toLongArray())
    })
    return NbtCompound(fields)
}

private fun decodePiece(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): StructurePiece {
    val pieceTypeId = StructurePieceTypeId.parse(nbtCompound.string("id"))
    val bounds = nbtCompound.requiredTag<NbtIntArray>("BB")
    require(bounds.size == 6) { "A structure piece bounding box needs six coordinates" }
    val orientation = nbtCompound.int("O", -1)
    return StructurePiece(
        pieceTypeId,
        BoundingBox(BlockPosition(bounds[0], bounds[1], bounds[2]), BlockPosition(bounds[3], bounds[4], bounds[5])),
        if (orientation == -1) null else HORIZONTAL_DIRECTIONS[orientation.mod(HORIZONTAL_DIRECTIONS.size)],
        nbtCompound.int("GD", 0),
        mappings.readProperties(
            nbtCompound,
            NbtPropertyScope("structure_piece", pieceTypeId.toString()),
            PIECE_NBT_FIELDS
        ),
    )
}

private fun encodePiece(value: StructurePiece, mappings: NbtPropertyWriteMappings): NbtCompound {
    val fields = mappings.writeProperties(
        value.properties,
        NbtPropertyScope("structure_piece", value.pieceTypeId.toString()),
        PIECE_NBT_FIELDS
    )
    val orientation = value.orientation?.let {
        HORIZONTAL_DIRECTIONS.indexOf(it)
            .also { index -> require(index >= 0) { "A structure piece orientation must be horizontal" } }
    } ?: -1
    fields["id"] = NbtString(value.pieceTypeId.toString())
    fields["BB"] = NbtIntArray(
        intArrayOf(
            value.boundingBox.min.x, value.boundingBox.min.y, value.boundingBox.min.z,
            value.boundingBox.max.x, value.boundingBox.max.y, value.boundingBox.max.z
        )
    )
    fields["O"] = NbtInt(orientation)
    fields["GD"] = NbtInt(value.genDepth)
    return NbtCompound(fields)
}

internal fun decodeUpgradeData(
    nbtCompound: NbtCompound,
    chunkLayout: ChunkLayout,
    mappings: NbtPropertyReadMappings
): UpgradeData {
    val indices = linkedMapOf<Int, MutableList<LocalBlockPosition>>()
    nbtCompound.optionalTag<NbtCompound>("Indices")?.forEachEntry { key, tag ->
        val index = key.toInt()
        require(index in 0 until chunkLayout.sectionCount) { "Upgrade Section index $index is outside the dimension" }
        val array = tag as? NbtIntArray ?: throw NbtPropertyFormatException("Upgrade indices must be NBT Int Arrays")
        indices[chunkLayout.minSectionY + index] = array.value.mapTo(mutableListOf(), LocalBlockPosition::fromIndex)
    }
    val sides = when (val tag = nbtCompound["Sides"]) {
        null -> 0
        is NbtByte -> tag.value.toInt()
        is NbtInt -> tag.value
        else -> throw NbtPropertyFormatException("Upgrade Sides must be an NBT Byte or Int")
    }
    return UpgradeData(
        Direction8.entries.filterTo(linkedSetOf()) { sides and (1 shl it.ordinal) != 0 }, indices,
        decodeSavedTicks(
            nbtCompound.optionalTag("neighbor_block_ticks") ?: NbtList(emptyList()),
            mappings,
            BlockId::parse
        ),
        decodeSavedTicks(
            nbtCompound.optionalTag("neighbor_fluid_ticks") ?: NbtList(emptyList()),
            mappings,
            FluidId::parse
        ),
        mappings.readProperties(nbtCompound, NbtPropertyScope("upgrade_data"), UPGRADE_NBT_FIELDS),
    )
}

internal fun encodeUpgradeData(
    value: UpgradeData,
    chunkLayout: ChunkLayout,
    mappings: NbtPropertyWriteMappings
): NbtCompound {
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("upgrade_data"), UPGRADE_NBT_FIELDS)
    fields["Sides"] = NbtByte(value.sides.fold(0) { mask, direction -> mask or (1 shl direction.ordinal) }.toByte())
    if (value.indices.isNotEmpty()) fields["Indices"] = NbtCompound(value.indices.entries.associate { (y, positions) ->
        require(y in chunkLayout) { "Upgrade Section Y $y is outside the dimension" }
        (y - chunkLayout.minSectionY).toString() to NbtIntArray(positions.map { it.index }.toIntArray())
    })
    if (value.neighborBlockTicks.isNotEmpty()) fields["neighbor_block_ticks"] =
        encodeSavedTicks(value.neighborBlockTicks, mappings, BlockId::toString)
    if (value.neighborFluidTicks.isNotEmpty()) fields["neighbor_fluid_ticks"] =
        encodeSavedTicks(value.neighborFluidTicks, mappings, FluidId::toString)
    return NbtCompound(fields)
}

internal fun decodeBlendingData(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): BlendingData {
    val heights = nbtCompound.optionalTag<NbtList>("heights")?.value?.mapTo(mutableListOf()) { tag ->
        val value =
            (tag as? NbtDouble)?.value ?: throw NbtPropertyFormatException("Blending heights must be NBT Doubles")
        value.takeUnless { it == Double.MAX_VALUE }
    } ?: MutableList<Double?>(BLENDING_COLUMN_COUNT) { null }
    require(heights.size == BLENDING_COLUMN_COUNT) { "Blending data needs $BLENDING_COLUMN_COUNT height columns" }
    return BlendingData(
        nbtCompound.int("min_section"), nbtCompound.int("max_section"), heights, null, null,
        mappings.readProperties(nbtCompound, NbtPropertyScope("blending_data"), BLENDING_NBT_FIELDS)
    )
}

internal fun encodeBlendingData(value: BlendingData, mappings: NbtPropertyWriteMappings): NbtCompound {
    require(value.heights.size == BLENDING_COLUMN_COUNT) { "Blending data needs $BLENDING_COLUMN_COUNT height columns" }
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("blending_data"), BLENDING_NBT_FIELDS)
    fields["min_section"] = NbtInt(value.minSection)
    fields["max_section"] = NbtInt(value.maxSection)
    if (value.heights.any { it != null }) fields["heights"] =
        NbtList(value.heights.map { NbtDouble(it ?: Double.MAX_VALUE) })
    return NbtCompound(fields)
}

private val HORIZONTAL_DIRECTIONS = listOf(Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST)
private val STRUCTURES_NBT_FIELDS = setOf("starts", "References")
private val START_NBT_FIELDS = setOf("id", "ChunkX", "ChunkZ", "references", "Children")
private val PIECE_NBT_FIELDS = setOf("id", "BB", "O", "GD")
private val UPGRADE_NBT_FIELDS = setOf("Sides", "Indices", "neighbor_block_ticks", "neighbor_fluid_ticks")
private val BLENDING_NBT_FIELDS = setOf("min_section", "max_section", "heights")

// BlendingData: two L-shaped boundaries, with (2 * (quartWidth - 1) + 1) and (2 * quartWidth + 1) columns.
internal const val BLENDING_COLUMN_COUNT: Int = 4 * BIOME_SECTION_SIDE
