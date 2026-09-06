package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound

internal fun decodeComponents(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): DataComponentMap =
    DataComponentMap(linkedMapOf<ComponentId, PropertyValue<*>>().also { entries ->
        nbtCompound.forEachEntry { name, nbtTag ->
            val componentId = ComponentId.parse(name)
            require(componentId !in entries) { "Duplicate component $componentId" }
            entries[componentId] = mappings.read(NbtPropertyScope("component"), componentId.value, nbtTag)
        }
    })

internal fun encodeComponents(dataComponentMap: DataComponentMap, mappings: NbtPropertyWriteMappings): NbtCompound =
    NbtCompound(buildMap {
        dataComponentMap.entries.forEach { (componentId, propertyValue) ->
            mappings.write(NbtPropertyScope("component"), componentId.toString(), propertyValue)
                ?.let { put(componentId.toString(), it) }
        }
    })

internal fun readComponentPatch(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): DataComponentPatch {
    val entries = linkedMapOf<ComponentId, ComponentPatchEntry>()
    nbtCompound.forEachEntry { name, tag ->
        val removed = name.startsWith('!')
        val componentId = ComponentId.parse(if (removed) name.substring(1) else name)
        require(componentId !in entries) { "Duplicate component $componentId in patch" }
        entries[componentId] = if (removed) {
            require(tag is NbtCompound && tag.size == 0) { "A removed component must have an empty compound value" }
            ComponentPatchEntry.Removed
        } else ComponentPatchEntry.SetValue(mappings.read(NbtPropertyScope("component"), componentId.toString(), tag))
    }
    return DataComponentPatch(entries)
}

internal fun writeComponentPatch(value: DataComponentPatch, mappings: NbtPropertyWriteMappings): NbtCompound =
    NbtCompound(buildMap {
        value.entries.forEach { (componentId, entry) ->
            when (entry) {
                ComponentPatchEntry.Removed -> put("!$componentId", NbtCompound(emptyMap()))
                is ComponentPatchEntry.SetValue -> mappings.write(
                    NbtPropertyScope("component"),
                    componentId.toString(),
                    entry.propertyValue
                )
                    ?.let { put(componentId.toString(), it) }
            }
        }
    })
