package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * Fully constructible server-side Configuration data.
 *
 * This is the final in-memory stage accepted directly by `MinecraftServerNegotiationOptions.configurationData`. The two
 * registry packet sequences let a caller use Known Packs compaction without coupling the structure to a disk data pack.
 */
class ResolvedConfigurationData(
    override val offeredKnownPacks: List<KnownPack>,
    override val enabledFeatureFlags: Set<Identifier>,
    val completeSynchronizedRegistryPackets: List<ClientboundRegistryDataPacket>,
    val knownPackSynchronizedRegistryPackets: List<ClientboundRegistryDataPacket> = completeSynchronizedRegistryPackets,
    override val registryTags: List<RegistryTags>,
    override val staticRegistrySchema: StaticRegistrySchema,
    completePacketCodecContext: PacketCodecContext? = null,
) : ConfigurationData {
    override val completePacketCodecContext: PacketCodecContext =
        completePacketCodecContext
            ?: staticRegistrySchema.resolve().withSynchronizedRegistries(completeSynchronizedRegistryPackets)

    override fun synchronizedRegistryPackets(acceptedKnownPacks: List<KnownPack>): List<ClientboundRegistryDataPacket> =
        if (acceptedKnownPacks == offeredKnownPacks) {
            knownPackSynchronizedRegistryPackets
        } else {
            completeSynchronizedRegistryPackets
        }
    override fun equals(other: Any?): Boolean =
        other is ResolvedConfigurationData &&
                offeredKnownPacks == other.offeredKnownPacks &&
                enabledFeatureFlags == other.enabledFeatureFlags &&
                completeSynchronizedRegistryPackets == other.completeSynchronizedRegistryPackets &&
                knownPackSynchronizedRegistryPackets == other.knownPackSynchronizedRegistryPackets &&
                registryTags == other.registryTags &&
                staticRegistrySchema == other.staticRegistrySchema &&
                completePacketCodecContext == other.completePacketCodecContext

    override fun hashCode(): Int {
        var result = offeredKnownPacks.hashCode()
        result = 31 * result + enabledFeatureFlags.hashCode()
        result = 31 * result + completeSynchronizedRegistryPackets.hashCode()
        result = 31 * result + knownPackSynchronizedRegistryPackets.hashCode()
        result = 31 * result + registryTags.hashCode()
        result = 31 * result + staticRegistrySchema.hashCode()
        return 31 * result + completePacketCodecContext.hashCode()
    }
}
