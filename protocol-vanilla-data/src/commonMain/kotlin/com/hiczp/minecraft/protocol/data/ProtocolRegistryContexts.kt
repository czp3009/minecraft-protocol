package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.packet.PlayLoginPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

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
    require(login.spawnInfo.dimension in login.levels) {
        "Play Login selected dimension ${login.spawnInfo.dimension}, but it is absent from the advertised levels"
    }
    val dimensionTypeRegistry = requireNotNull(
        registries.singleOrNull { it.registryId == DIMENSION_TYPE_REGISTRY },
    ) {
        "Configuration must provide exactly one synchronized registry $DIMENSION_TYPE_REGISTRY"
    }
    val dimensionTypeId = login.spawnInfo.dimensionTypeId
    val dimensionType = requireNotNull(
        dimensionTypeRegistry.entries.getOrNull(dimensionTypeId),
    ) {
        "Play Login selected absent dimension-type registry ID $dimensionTypeId"
    }
    val dimension = if (dimensionType.data == null) {
        MinecraftDimensionLayout.from(protocolData, dimensionType.id)
    } else {
        MinecraftDimensionLayout.from(
            listOf(dimensionTypeRegistry),
            dimensionTypeId,
        )
    }
    return withChunkSectionCount(dimension.sectionCount)
}

private val DIMENSION_TYPE_REGISTRY = Identifier("dimension_type")
