package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import kotlin.uuid.Uuid

internal fun readBrain(nbtTag: NbtTag, mappings: NbtPropertyReadMappings): BrainState {
    val compound = nbtTag.compound()
    val memories = linkedMapOf<String, MemorySlot>()
    compound.optionalTag<NbtCompound>("memories")?.forEachEntry { name, tag ->
        val slot = tag.compound()
        memories[name] = MemorySlot(
            slot["value"]?.let { mappings.read(NbtPropertyScope("memory", name), "value", it) },
            slot.optionalTag<NbtLong>("ttl")?.value,
            mappings.readProperties(slot, NbtPropertyScope("memory", name), MEMORY_FIELDS)
        )
    }
    return BrainState(
        memories,
        null,
        null,
        null,
        null,
        mappings.readProperties(compound, NbtPropertyScope("brain"), setOf("memories"))
    )
}

internal fun writeBrain(value: BrainState, mappings: NbtPropertyWriteMappings): NbtCompound {
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("brain"), setOf("memories"))
    fields["memories"] = NbtCompound(buildMap {
        value.memories.forEach { (id, slot) ->
            // Brain saves only present memories; an explicitly empty runtime slot remains a runtime fact.
            slot.value?.let { memory ->
                val slotFields =
                    mappings.writeProperties(slot.properties, NbtPropertyScope("memory", id), MEMORY_FIELDS)
                mappings.write(NbtPropertyScope("memory", id), "value", memory)?.let { slotFields["value"] = it }
                slot.timeToLive?.let { slotFields["ttl"] = NbtLong(it) }
                put(id, NbtCompound(slotFields))
            }
        }
    })
    return NbtCompound(fields)
}

internal fun readVillagerData(nbtTag: NbtTag, mappings: NbtPropertyReadMappings): VillagerData {
    val compound = nbtTag.compound()
    return VillagerData(
        compound.string("type", "minecraft:plains"),
        compound.string("profession", "minecraft:none"), compound.int("level", 1),
        mappings.readProperties(compound, NbtPropertyScope("villager_data"), VILLAGER_FIELDS)
    )
}

internal fun writeVillagerData(value: VillagerData, mappings: NbtPropertyWriteMappings): NbtCompound {
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("villager_data"), VILLAGER_FIELDS)
    fields["type"] = NbtString(value.type)
    fields["profession"] = NbtString(value.profession)
    fields["level"] = NbtInt(value.level)
    return NbtCompound(fields)
}

internal fun readGossips(nbtTag: NbtTag, mappings: NbtPropertyReadMappings): Gossips {
    val entries = linkedMapOf<Uuid, MutableMap<String, Int>>()
    nbtTag.list().forEach { tag ->
        val gossip = tag.compound()
        val target = gossip.requiredTag<NbtIntArray>("Target").toUuid()
        val type = gossip.string("Type")
        require(entries.getOrPut(target) { linkedMapOf() }
            .put(type, gossip.int("Value")) == null) { "Duplicate gossip for $target and $type" }
    }
    return Gossips(entries)
}

internal fun writeGossips(value: Gossips, mappings: NbtPropertyWriteMappings): NbtList =
    NbtList(value.entries.flatMap { (target, types) ->
        types.map { (type, amount) ->
            NbtCompound(
                mapOf(
                    "Target" to target.toNbtIntArray(),
                    "Type" to NbtString(type),
                    "Value" to NbtInt(amount)
                )
            )
        }
    })

private val MEMORY_FIELDS = setOf("value", "ttl")
private val VILLAGER_FIELDS = setOf("type", "profession", "level")
internal val GOSSIP_FIELDS = setOf("Target", "Type", "Value")
