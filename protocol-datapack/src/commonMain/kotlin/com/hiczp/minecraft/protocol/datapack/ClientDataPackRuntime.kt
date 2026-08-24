package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.ConfigurationClientboundKnownPacksPacket
import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.packet.FeatureFlagsPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/** Manually constructible capture of the data-pack-related packets visible to a client. */
class ReceivedDataPackConfiguration(
    knownPacks: List<KnownPack>,
    featureFlags: Set<Identifier>,
    registries: List<RegistryDataPacket>,
    tags: List<RegistryTags>,
) {
    val knownPacks: List<KnownPack> = knownPacks.toList()
    val featureFlags: Set<Identifier> = featureFlags.toSet()
    val registries: List<RegistryDataPacket> = registries.map { packet ->
        RegistryDataPacket(packet.registryId, packet.entries.toList())
    }
    val tags: List<RegistryTags> = tags.map { registry ->
        RegistryTags(
            registry.registry,
            registry.tags.map { tag -> TagDefinition(tag.name, tag.entries.toList()) },
        )
    }

    init {
        require(this.knownPacks.distinct().size == this.knownPacks.size) { "Received Known Packs contains duplicates" }
        require(this.registries.map(RegistryDataPacket::registryId).distinct().size == this.registries.size) {
            "Received Configuration contains duplicate synchronized registries"
        }
        require(this.tags.map(RegistryTags::registry).distinct().size == this.tags.size) {
            "Received Configuration contains duplicate tag registries"
        }
    }

    constructor(
        knownPacks: ConfigurationClientboundKnownPacksPacket?,
        featureFlags: FeatureFlagsPacket?,
        registries: List<RegistryDataPacket>,
        tags: ConfigurationUpdateTagsPacket?,
    ) : this(
        knownPacks = knownPacks?.knownPacks.orEmpty(),
        featureFlags = featureFlags?.featureFlags.orEmpty(),
        registries = registries,
        tags = tags?.registries.orEmpty(),
    )
}

data class ClientDataPackTag(
    val registry: Identifier,
    val id: Identifier,
    val entries: List<ProtocolRegistryEntry>,
)

/**
 * Runtime-ready registry and tag view reconstructed from Configuration packets.
 *
 * It is deliberately not a `DataPack`: the wire does not contain recipes, loot tables, functions, advancements, or
 * other server-only resources. [configuration] retains the exact received NBT for applications with typed codecs.
 */
class ClientDataPackRuntime(
    val configuration: ReceivedDataPackConfiguration,
    val registryContext: ProtocolRegistryContext,
    tags: List<ClientDataPackTag>,
) {
    val tags: List<ClientDataPackTag> = tags.map { tag ->
        ClientDataPackTag(tag.registry, tag.id, tag.entries.toList())
    }

    private val tagsById: Map<Pair<Identifier, Identifier>, ClientDataPackTag> = this.tags.associateBy { tag ->
        tag.registry to tag.id
    }

    init {
        require(tagsById.size == this.tags.size) { "Client data-pack runtime contains duplicate tags" }
    }

    fun tag(
        registry: Identifier,
        id: Identifier,
    ): ClientDataPackTag? = tagsById[registry to id]
}

fun ReceivedDataPackConfiguration.resolveRuntime(
    protocolData: ProtocolDataSet,
    staticRegistries: StaticRegistrySchema = protocolData.staticRegistries,
    remoteRegistries: RemoteRegistrySnapshot = RemoteRegistrySnapshot.Empty,
): ClientDataPackRuntime {
    val resolvedStatic = staticRegistries.resolve(remoteRegistries)
    val synchronized = registries.map { packet ->
        ProtocolRegistry(
            packet.registryId,
            packet.entries.mapIndexed { rawId, entry -> ProtocolRegistryEntry(entry.id, rawId) },
        )
    }
    val context = protocolData.registryContext
        .withStaticRegistryResolution(resolvedStatic)
        .withRegistries(synchronized)
    require(context.requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY).size > 0) {
        "The synchronized biome registry is empty"
    }
    return resolveRuntime(context)
}

/** Resolves received tags against a context already installed by a vanilla or loader negotiation profile. */
fun ReceivedDataPackConfiguration.resolveRuntime(
    registryContext: ProtocolRegistryContext,
): ClientDataPackRuntime {
    val runtimeTags = tags.flatMap { registryTags ->
        val registry = registryContext.requireRegistry(registryTags.registry)
        registryTags.tags.map { tag ->
            ClientDataPackTag(
                registry = registryTags.registry,
                id = tag.name,
                entries = tag.entries.map { rawId ->
                    registry[rawId] ?: throw IllegalArgumentException(
                        "Tag ${tag.name} in ${registryTags.registry} contains unknown raw ID $rawId",
                    )
                },
            )
        }
    }
    return ClientDataPackRuntime(this, registryContext, runtimeTags)
}
