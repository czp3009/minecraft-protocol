package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * Fully constructible server-side Configuration data.
 *
 * This is the final in-memory stage accepted directly by `MinecraftServerNegotiationOptions.protocolData`. The two
 * registry packet sequences let a caller use Known Packs compaction without coupling the structure to a disk data pack.
 */
class ResolvedProtocolData(
    override val minecraftVersion: String,
    override val protocolVersion: Int,
    override val offeredKnownPacks: List<KnownPack>,
    override val enabledFeatureFlags: Set<Identifier>,
    val completeSynchronizedRegistryPackets: List<RegistryDataPacket>,
    val knownPackSynchronizedRegistryPackets: List<RegistryDataPacket> = completeSynchronizedRegistryPackets,
    override val registryTags: List<RegistryTags>,
    override val staticRegistrySchema: StaticRegistrySchema,
    completeProtocolRegistryContext: ProtocolRegistryContext? = null,
) : ProtocolData {
    override val completeProtocolRegistryContext: ProtocolRegistryContext =
        completeProtocolRegistryContext ?: resolveProtocolRegistryContext(
            staticRegistrySchema,
            completeSynchronizedRegistryPackets,
        )

    init {
        val completeRegistryIds = completeSynchronizedRegistryPackets.map(RegistryDataPacket::registryId)
        require(minecraftVersion.isNotBlank()) { "Protocol data requires a Minecraft version" }
        require(protocolVersion >= 0) { "A protocol version must be non-negative" }
        require(offeredKnownPacks.distinct().size == offeredKnownPacks.size) {
            "Offered Known Packs contains duplicates"
        }
        require(completeRegistryIds.distinct().size == completeRegistryIds.size) {
            "Complete synchronized registries contains duplicate registry IDs"
        }
        val knownPackRegistryIds = knownPackSynchronizedRegistryPackets.map(RegistryDataPacket::registryId)
        require(knownPackRegistryIds == completeRegistryIds) {
            "Complete and Known Packs registry packet sequences have different registry order"
        }
        completeSynchronizedRegistryPackets.zip(knownPackSynchronizedRegistryPackets)
            .forEach { (completeRegistry, knownPackRegistry) ->
                require(
                    completeRegistry.entries.map(RegistryEntry::id) ==
                            knownPackRegistry.entries.map(RegistryEntry::id),
                ) {
                    "Complete and Known Packs entries differ for ${completeRegistry.registryId}"
                }
            }
        require(registryTags.map(RegistryTags::registry).distinct().size == registryTags.size) {
            "Registry tags contains duplicate registry IDs"
        }
    }

    override fun synchronizedRegistryPackets(acceptedKnownPacks: List<KnownPack>): List<RegistryDataPacket> =
        if (acceptedKnownPacks == offeredKnownPacks) {
            knownPackSynchronizedRegistryPackets
        } else {
            completeSynchronizedRegistryPackets
        }

    override fun equals(other: Any?): Boolean =
        other is ResolvedProtocolData &&
                minecraftVersion == other.minecraftVersion &&
                protocolVersion == other.protocolVersion &&
                offeredKnownPacks == other.offeredKnownPacks &&
                enabledFeatureFlags == other.enabledFeatureFlags &&
                completeSynchronizedRegistryPackets == other.completeSynchronizedRegistryPackets &&
                knownPackSynchronizedRegistryPackets == other.knownPackSynchronizedRegistryPackets &&
                registryTags == other.registryTags &&
                staticRegistrySchema == other.staticRegistrySchema &&
                completeProtocolRegistryContext == other.completeProtocolRegistryContext

    override fun hashCode(): Int {
        var result = minecraftVersion.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + offeredKnownPacks.hashCode()
        result = 31 * result + enabledFeatureFlags.hashCode()
        result = 31 * result + completeSynchronizedRegistryPackets.hashCode()
        result = 31 * result + knownPackSynchronizedRegistryPackets.hashCode()
        result = 31 * result + registryTags.hashCode()
        result = 31 * result + staticRegistrySchema.hashCode()
        return 31 * result + completeProtocolRegistryContext.hashCode()
    }
}

private fun resolveProtocolRegistryContext(
    staticRegistrySchema: StaticRegistrySchema,
    completeSynchronizedRegistryPackets: List<RegistryDataPacket>,
): ProtocolRegistryContext {
    val synchronizedProtocolRegistries = completeSynchronizedRegistryPackets.map { registryDataPacket ->
        ProtocolRegistry(
            registryDataPacket.registryId,
            registryDataPacket.entries.mapIndexed { rawId, registryEntry ->
                ProtocolRegistryEntry(registryEntry.id, rawId)
            },
        )
    }
    return staticRegistrySchema.resolve().withRegistries(synchronizedProtocolRegistries)
}
