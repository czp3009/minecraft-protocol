package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistry
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryEntry
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema

/**
 * Resolves the base registry context described by one Configuration exchange.
 * The caller-selected [staticRegistries] supplies locally known registries and
 * block states; every synchronized packet replaces the raw-ID mapping for its
 * registry.
 */
fun ProtocolDataSet.resolveSynchronizedRegistryContext(
    registries: List<RegistryDataPacket>,
    staticRegistries: StaticRegistrySchema = this.staticRegistries,
): ProtocolRegistryContext {
    require(registries.map(RegistryDataPacket::registryId).distinct().size == registries.size) {
        "Configuration provided duplicate synchronized registries"
    }
    val synchronizedBiomeSize = registries.singleOrNull {
        it.registryId == ProtocolRegistryContext.BIOME_REGISTRY
    }?.entries?.size
    val biomeSize = requireNotNull(
        synchronizedBiomeSize ?: staticRegistries.registries[ProtocolRegistryContext.BIOME_REGISTRY]?.size,
    ) {
        "Configuration did not provide a biome registry and the static schema has none"
    }
    require(biomeSize > 0) {
        "The synchronized biome registry is empty"
    }
    val base = if (staticRegistries === this.staticRegistries) {
        registryContext
    } else {
        staticRegistries.resolve()
    }
    val changedRegistries = registries.mapNotNull { packet ->
        val matchesBase = base.registry(packet.registryId)?.let { registry ->
            registry.entries.size == packet.entries.size && packet.entries.withIndex().all { (rawId, entry) ->
                registry[rawId]?.id == entry.id
            }
        } == true
        if (matchesBase) {
            null
        } else {
            ProtocolRegistry(
                packet.registryId,
                packet.entries.mapIndexed { rawId, entry ->
                    ProtocolRegistryEntry(entry.id, rawId)
                },
            )
        }
    }
    return if (changedRegistries.isEmpty()) {
        base
    } else {
        base.withRegistries(changedRegistries)
    }
}

/**
 * Returns this context with the chunk height selected by [login]. The active
 * dimension type is resolved by its synchronized raw ID. When Known Packs
 * omitted the entry NBT, only that entry's matching version data is used as a
 * fallback.
 */
fun ProtocolRegistryContext.withPlayLoginDimension(
    login: PlayLoginPacket,
    registries: List<RegistryDataPacket>,
    protocolData: ProtocolDataSet,
): ProtocolRegistryContext {
    val dimension = MinecraftDimensionLayout.from(login, registries, protocolData)
    return withChunkSectionCount(dimension.sectionCount)
}
