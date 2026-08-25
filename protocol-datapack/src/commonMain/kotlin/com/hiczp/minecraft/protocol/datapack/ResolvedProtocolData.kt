package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * Fully constructible server-side Configuration data.
 *
 * This is the final in-memory stage accepted directly by `MinecraftServerNegotiationOptions.protocolData`. The two
 * registry snapshots let a caller use Known Packs compaction without coupling the structure to a disk data pack.
 */
class ResolvedProtocolData(
    override val minecraftVersion: String,
    override val protocolVersion: Int,
    offeredKnownPacks: List<KnownPack>,
    enabledFeatureFlags: Set<Identifier>,
    completeSynchronizedRegistryPackets: List<RegistryDataPacket>,
    knownPackSynchronizedRegistryPackets: List<RegistryDataPacket> = completeSynchronizedRegistryPackets,
    registryTags: List<RegistryTags>,
    override val staticRegistrySchema: StaticRegistrySchema,
    completeProtocolRegistryContext: ProtocolRegistryContext? = null,
) : ProtocolData {
    override val offeredKnownPacks: List<KnownPack> = offeredKnownPacks.toList()
    override val enabledFeatureFlags: Set<Identifier> = enabledFeatureFlags.toSet()
    val completeSynchronizedRegistryPackets: List<RegistryDataPacket> =
        completeSynchronizedRegistryPackets.map(RegistryDataPacket::snapshot)
    val knownPackSynchronizedRegistryPackets: List<RegistryDataPacket> =
        knownPackSynchronizedRegistryPackets.map(RegistryDataPacket::snapshot)
    override val registryTags: List<RegistryTags> = registryTags.map(RegistryTags::snapshot)
    override val completeProtocolRegistryContext: ProtocolRegistryContext =
        completeProtocolRegistryContext ?: resolveProtocolRegistryContext()

    init {
        val completeRegistryIds = this.completeSynchronizedRegistryPackets.map(RegistryDataPacket::registryId)
        require(minecraftVersion.isNotBlank()) { "Protocol data requires a Minecraft version" }
        require(protocolVersion >= 0) { "A protocol version must be non-negative" }
        require(this.offeredKnownPacks.distinct().size == this.offeredKnownPacks.size) {
            "Offered Known Packs contains duplicates"
        }
        require(completeRegistryIds.distinct().size == completeRegistryIds.size) {
            "Complete synchronized registries contains duplicate registry IDs"
        }
        val knownPackRegistryIds = this.knownPackSynchronizedRegistryPackets.map(RegistryDataPacket::registryId)
        require(knownPackRegistryIds == completeRegistryIds) {
            "Complete and Known Packs registry snapshots have different registry order"
        }
        this.completeSynchronizedRegistryPackets.zip(this.knownPackSynchronizedRegistryPackets)
            .forEach { (completeRegistry, knownPackRegistry) ->
                require(
                    completeRegistry.entries.map(RegistryEntry::id) ==
                            knownPackRegistry.entries.map(RegistryEntry::id),
                ) {
                    "Complete and Known Packs entries differ for ${completeRegistry.registryId}"
                }
            }
        require(this.registryTags.map(RegistryTags::registry).distinct().size == this.registryTags.size) {
            "Registry tags contains duplicate registry IDs"
        }
    }

    override fun synchronizedRegistryPackets(acceptedKnownPacks: List<KnownPack>): List<RegistryDataPacket> =
        if (acceptedKnownPacks == offeredKnownPacks) {
            knownPackSynchronizedRegistryPackets
        } else {
            completeSynchronizedRegistryPackets
        }

    private fun resolveProtocolRegistryContext(): ProtocolRegistryContext {
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
}

private fun RegistryDataPacket.snapshot(): RegistryDataPacket = RegistryDataPacket(registryId, entries.toList())

private fun RegistryTags.snapshot(): RegistryTags = RegistryTags(
    registry,
    tags.map { tagDefinition -> TagDefinition(tagDefinition.name, tagDefinition.entries.toList()) },
)
