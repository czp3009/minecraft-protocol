package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistry
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryEntry
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema

/**
 * Resolves the base registry context described by one Configuration exchange.
 * The caller-selected [staticRegistrySchema] supplies locally known registries and
 * block states; every synchronized packet replaces the raw-ID mapping for its
 * registry.
 */
fun ProtocolData.resolveSynchronizedRegistryContext(
    synchronizedRegistryPackets: List<RegistryDataPacket>,
    staticRegistrySchema: StaticRegistrySchema = this.staticRegistrySchema,
): ProtocolRegistryContext {
    require(
        synchronizedRegistryPackets.map(RegistryDataPacket::registryId).distinct().size ==
                synchronizedRegistryPackets.size,
    ) {
        "Configuration provided duplicate synchronized registries"
    }
    val synchronizedBiomeRegistrySize = synchronizedRegistryPackets.singleOrNull { registryDataPacket ->
        registryDataPacket.registryId == ProtocolRegistryContext.BIOME_REGISTRY
    }?.entries?.size
    val biomeRegistrySize = requireNotNull(
        synchronizedBiomeRegistrySize
            ?: staticRegistrySchema.registries[ProtocolRegistryContext.BIOME_REGISTRY]?.size,
    ) {
        "Configuration did not provide a biome registry and the static schema has none"
    }
    require(biomeRegistrySize > 0) {
        "The synchronized biome registry is empty"
    }
    val baseProtocolRegistryContext = if (staticRegistrySchema === this.staticRegistrySchema) {
        completeProtocolRegistryContext
    } else {
        staticRegistrySchema.resolve()
    }
    val changedProtocolRegistries = synchronizedRegistryPackets.mapNotNull { registryDataPacket ->
        val matchesBaseProtocolRegistry =
            baseProtocolRegistryContext.registry(registryDataPacket.registryId)?.let { protocolRegistry ->
                protocolRegistry.entries.size == registryDataPacket.entries.size &&
                        registryDataPacket.entries.withIndex().all { (rawId, registryEntry) ->
                            protocolRegistry[rawId]?.id == registryEntry.id
                        }
            } == true
        if (matchesBaseProtocolRegistry) {
            null
        } else {
            ProtocolRegistry(
                registryDataPacket.registryId,
                registryDataPacket.entries.mapIndexed { rawId, registryEntry ->
                    ProtocolRegistryEntry(registryEntry.id, rawId)
                },
            )
        }
    }
    return if (changedProtocolRegistries.isEmpty()) {
        baseProtocolRegistryContext
    } else {
        baseProtocolRegistryContext.withRegistries(changedProtocolRegistries)
    }
}

/**
 * Returns this context with the chunk height selected by [playLoginPacket]. The active
 * dimension type is resolved by its synchronized raw ID. When Known Packs
 * omitted the entry NBT, only that entry's matching version data is used as a
 * fallback.
 */
fun ProtocolRegistryContext.withPlayLoginDimensionLayout(
    playLoginPacket: PlayLoginPacket,
    synchronizedRegistryPackets: List<RegistryDataPacket>,
    protocolData: ProtocolData,
): ProtocolRegistryContext {
    val minecraftDimensionLayout = MinecraftDimensionLayout.from(
        playLoginPacket,
        synchronizedRegistryPackets,
        protocolData,
    )
    return withChunkSectionCount(minecraftDimensionLayout.sectionCount)
}
