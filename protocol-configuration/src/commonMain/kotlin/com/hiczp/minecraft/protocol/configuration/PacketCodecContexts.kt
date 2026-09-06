package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.model.type.RegistryIdMap
import com.hiczp.minecraft.protocol.model.type.RegistryIdMapping
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema

/**
 * Resolves the base registry context described by one Configuration exchange.
 * The caller-selected [staticRegistrySchema] supplies locally known registries and
 * block states; every synchronized packet replaces the raw-ID mapping for its
 * registry.
 */
fun ConfigurationData.resolveSynchronizedRegistryContext(
    synchronizedRegistryPackets: List<ClientboundRegistryDataPacket>,
    staticRegistrySchema: StaticRegistrySchema = this.staticRegistrySchema,
): PacketCodecContext {
    val basePacketCodecContext = if (staticRegistrySchema === this.staticRegistrySchema) {
        completePacketCodecContext
    } else {
        staticRegistrySchema.resolve()
    }
    return basePacketCodecContext.withSynchronizedRegistries(synchronizedRegistryPackets)
}

/** Overlays synchronized registries, whose entry order defines their raw IDs, onto this codec context. */
fun PacketCodecContext.withSynchronizedRegistries(
    synchronizedRegistryPackets: List<ClientboundRegistryDataPacket>,
): PacketCodecContext = withRegistries(
    synchronizedRegistryPackets.map { clientboundRegistryDataPacket ->
        RegistryIdMap(
            clientboundRegistryDataPacket.registry,
            clientboundRegistryDataPacket.entries.mapIndexed { rawId, registryEntry ->
                RegistryIdMapping(registryEntry.id, rawId)
            },
        )
    },
)
