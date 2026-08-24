package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.packet.FeatureFlagsPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * Fully constructible server-side Configuration data.
 *
 * This is the final in-memory stage accepted directly by `MinecraftServerNegotiationOptions.protocolData`. The two
 * registry snapshots let a caller use Known Packs compaction without coupling the structure to a disk data pack.
 */
class DataPackProtocolDataSet(
    override val minecraftVersion: String,
    override val protocolVersion: Int,
    knownPacks: List<KnownPack>,
    featureFlags: FeatureFlagsPacket,
    completeRegistries: List<RegistryDataPacket>,
    knownPackRegistries: List<RegistryDataPacket> = completeRegistries,
    tags: ConfigurationUpdateTagsPacket,
    override val staticRegistries: StaticRegistrySchema,
    resolvedRegistryContext: ProtocolRegistryContext? = null,
) : ProtocolDataSet {
    override val knownPacks: List<KnownPack> = knownPacks.toList()
    override val featureFlags: FeatureFlagsPacket = FeatureFlagsPacket(featureFlags.featureFlags.toSet())
    val completeRegistries: List<RegistryDataPacket> = completeRegistries.map(RegistryDataPacket::snapshot)
    val knownPackRegistries: List<RegistryDataPacket> = knownPackRegistries.map(RegistryDataPacket::snapshot)
    override val tags: ConfigurationUpdateTagsPacket = tags.snapshot()
    override val registryContext: ProtocolRegistryContext = resolvedRegistryContext ?: resolveContext()

    init {
        val completeRegistryIds = this.completeRegistries.map(RegistryDataPacket::registryId)
        require(minecraftVersion.isNotBlank()) { "A protocol data set requires a Minecraft version" }
        require(protocolVersion >= 0) { "A protocol version must be non-negative" }
        require(this.knownPacks.distinct().size == this.knownPacks.size) { "Known Packs contains duplicates" }
        require(completeRegistryIds.distinct().size == completeRegistryIds.size) {
            "Complete synchronized registries contains duplicate registry IDs"
        }
        val compactRegistryIds = this.knownPackRegistries.map(RegistryDataPacket::registryId)
        require(compactRegistryIds == completeRegistryIds) {
            "Complete and Known Packs registry snapshots have different registry order"
        }
        this.completeRegistries.zip(this.knownPackRegistries).forEach { (complete, compact) ->
            require(complete.entries.map(RegistryEntry::id) == compact.entries.map(RegistryEntry::id)) {
                "Complete and Known Packs entries differ for ${complete.registryId}"
            }
        }
    }

    override fun registryPackets(clientKnownPacks: List<KnownPack>): List<RegistryDataPacket> =
        if (clientKnownPacks == knownPacks) knownPackRegistries else completeRegistries

    private fun resolveContext(): ProtocolRegistryContext {
        val synchronized = completeRegistries.map { packet ->
            ProtocolRegistry(
                packet.registryId,
                packet.entries.mapIndexed { rawId, entry -> ProtocolRegistryEntry(entry.id, rawId) },
            )
        }
        return staticRegistries.resolve().withRegistries(synchronized)
    }
}

private fun RegistryDataPacket.snapshot(): RegistryDataPacket = RegistryDataPacket(registryId, entries.toList())

private fun ConfigurationUpdateTagsPacket.snapshot(): ConfigurationUpdateTagsPacket = ConfigurationUpdateTagsPacket(
    registries.map { registry ->
        RegistryTags(
            registry.registry,
            registry.tags.map { tag -> TagDefinition(tag.name, tag.entries.toList()) },
        )
    },
)
