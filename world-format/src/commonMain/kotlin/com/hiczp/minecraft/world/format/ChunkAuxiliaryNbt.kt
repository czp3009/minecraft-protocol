package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

internal fun decodeHeightmaps(
    nbtCompound: NbtCompound,
    chunkLayout: ChunkLayout,
    mappings: NbtPropertyReadMappings,
): ChunkHeightmaps {
    val heightmaps = ChunkHeightmaps()
    val bits = bitsForPaletteSize(chunkLayout.height + 1)
    nbtCompound.forEachEntry { name, tag ->
        if (tag is NbtLongArray) {
            val values =
                unpackNbtValues(tag, bits, MinecraftCoordinates.SECTION_SIDE * MinecraftCoordinates.SECTION_SIDE)
            require(values.all { it in 0..chunkLayout.height }) { "Heightmap values exceed the dimension height" }
            heightmaps.maps[HeightmapType(name)] =
                Heightmap(IntArray(values.size) { values[it] + chunkLayout.minBlockY })
        } else {
            heightmaps.properties[name] = mappings.read(NbtPropertyScope("heightmaps"), name, tag)
        }
    }
    return heightmaps
}

internal fun encodeHeightmaps(
    heightmaps: ChunkHeightmaps,
    chunkLayout: ChunkLayout,
    mappings: NbtPropertyWriteMappings,
): NbtCompound {
    val fields = mappings.writeProperties(
        heightmaps.properties, NbtPropertyScope("heightmaps"), heightmaps.maps.keys.map { it.serializationKey }.toSet(),
    )
    heightmaps.maps.forEach { (type, heightmap) ->
        val values = IntArray(MinecraftCoordinates.SECTION_SIDE * MinecraftCoordinates.SECTION_SIDE) { index ->
            val y =
                requireNotNull(heightmap[index]) { "Heightmap ${type.serializationKey} column $index is unknown" }
            val height = y.toLong() - chunkLayout.minBlockY
            require(height in 0..chunkLayout.height.toLong()) { "Heightmap Y $y exceeds the dimension bounds" }
            height.toInt()
        }
        fields[type.serializationKey] = packNbtValues(values, bitsForPaletteSize(chunkLayout.height + 1))
    }
    return NbtCompound(fields)
}

internal fun <T : Any> decodeSavedTicks(
    nbtList: NbtList,
    mappings: NbtPropertyReadMappings,
    type: (String) -> T,
): MutableList<SavedTick<T>> = nbtList.value.mapTo(mutableListOf()) { tag ->
    val tick = tag.compound()
    val priority = tick.int("p", 0)
    SavedTick(
        type(tick.string("i")), tick.blockPosition(), tick.int("t"),
        TickPriority.entries.firstOrNull { it.value == priority }
            ?: if (priority < TickPriority.EXTREMELY_HIGH.value) TickPriority.EXTREMELY_HIGH else TickPriority.EXTREMELY_LOW,
        mappings.readProperties(tick, NbtPropertyScope("tick"), TICK_NBT_FIELDS),
    )
}

internal fun <T : Any> decodeScheduledTicks(
    nbtList: NbtList,
    tickBase: Long,
    mappings: NbtPropertyReadMappings,
    type: (String) -> T,
): MutableList<ScheduledTick<T>> {
    val savedTicks = decodeSavedTicks(nbtList, mappings, type)
    return savedTicks.mapIndexedTo(mutableListOf()) { index, savedTick ->
        ScheduledTick(
            savedTick.type, savedTick.blockPosition, tickBase + savedTick.delay, savedTick.priority,
            index.toLong() - savedTicks.size, savedTick.properties,
        )
    }
}

internal fun <T : Any> encodeSavedTicks(
    savedTicks: List<SavedTick<T>>,
    mappings: NbtPropertyWriteMappings,
    name: (T) -> String,
): NbtList = NbtList(savedTicks.map { savedTick ->
    val fields = mappings.writeProperties(savedTick.properties, NbtPropertyScope("tick"), TICK_NBT_FIELDS)
    fields["i"] = NbtString(name(savedTick.type))
    fields["x"] = NbtInt(savedTick.blockPosition.x)
    fields["y"] = NbtInt(savedTick.blockPosition.y)
    fields["z"] = NbtInt(savedTick.blockPosition.z)
    fields["t"] = NbtInt(savedTick.delay)
    fields["p"] = NbtInt(savedTick.priority.value)
    NbtCompound(fields)
})

internal fun <T : Any> encodeScheduledTicks(
    scheduledTicks: List<ScheduledTick<T>>,
    tickBase: Long,
    mappings: NbtPropertyWriteMappings,
    name: (T) -> String,
): NbtList = encodeSavedTicks(scheduledTicks.sortedBy { it.subTickOrder }.map { tick ->
    // Official ScheduledTick.toSavedTick performs lsub followed by l2i, including wrapping and negative delays.
    SavedTick(tick.type, tick.blockPosition, (tick.triggerTick - tickBase).toInt(), tick.priority, tick.properties)
}, mappings, name)

internal fun decodePostProcessing(nbtList: NbtList, chunkLayout: ChunkLayout): ChunkPostProcessing {
    require(nbtList.size <= chunkLayout.sectionCount) { "PostProcessing exceeds the dimension Section count" }
    val positions = arrayOfNulls<MutableList<LocalBlockPosition>>(chunkLayout.sectionCount)
    repeat(nbtList.size) { index ->
        val values = mutableListOf<LocalBlockPosition>()
        nbtList[index].list().forEach { position ->
            val packed = (position as? NbtShort)?.value?.toInt()
                ?: throw NbtPropertyFormatException("PostProcessing positions must be NBT Shorts")
            values.add(LocalBlockPosition(packed and 15, packed shr 4 and 15, packed shr 8 and 15))
        }
        if (values.isNotEmpty()) positions[index] = values
    }
    return ChunkPostProcessing(chunkLayout.minSectionY, positions)
}

internal fun encodePostProcessing(value: ChunkPostProcessing, chunkLayout: ChunkLayout): NbtList {
    value.positions.forEachIndexed { index, positions ->
        require(positions.isNullOrEmpty() || value.sectionMinY + index in chunkLayout) { "PostProcessing contains an out-of-range Section" }
    }
    return NbtList(List(chunkLayout.sectionCount) { index ->
        NbtList(
            value.positions.getOrNull(chunkLayout.minSectionY + index - value.sectionMinY).orEmpty().map { position ->
            NbtShort((position.x or (position.y shl 4) or (position.z shl 8)).toShort())
        })
    })
}

private val TICK_NBT_FIELDS = setOf("i", "x", "y", "z", "t", "p")
