package com.hiczp.minecraft.protocol.datapack

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
    val baseProtocolRegistryContext = if (staticRegistrySchema === this.staticRegistrySchema) {
        completeProtocolRegistryContext
    } else {
        staticRegistrySchema.resolve()
    }
    val synchronizedProtocolRegistries = synchronizedRegistryPackets.map { registryDataPacket ->
        ProtocolRegistry(
            registryDataPacket.registryId,
            registryDataPacket.entries.mapIndexed { rawId, registryEntry ->
                ProtocolRegistryEntry(registryEntry.id, rawId)
            },
        )
    }
    val resolvedProtocolRegistryContext = baseProtocolRegistryContext.withRegistries(synchronizedProtocolRegistries)
    val matchesBaseProtocolRegistryContext = synchronizedProtocolRegistries.all { protocolRegistry ->
        baseProtocolRegistryContext.registry(protocolRegistry.id) == protocolRegistry
    }
    return if (matchesBaseProtocolRegistryContext) {
        baseProtocolRegistryContext
    } else {
        resolvedProtocolRegistryContext
    }
}
