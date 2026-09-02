package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.*

data class ClientRegistryTag(
    val registryId: Identifier,
    val tagId: Identifier,
    val protocolRegistryEntries: List<ProtocolRegistryEntry>,
)

/**
 * Registry and tag view reconstructed from the data-pack-related Configuration payloads.
 *
 * This is not a [com.hiczp.minecraft.world.format.datapack.DataPack]: the wire does not contain recipes, loot tables,
 * functions, advancements, or other server-only resources. [dataPackConfigurationSnapshot] retains the received NBT
 * for applications that provide typed codecs.
 */
data class ClientRegistryView(
    val dataPackConfigurationSnapshot: DataPackConfigurationSnapshot,
    val protocolRegistryContext: ProtocolRegistryContext,
    val clientRegistryTags: List<ClientRegistryTag>,
) {
    private val clientRegistryTagsById: Map<Pair<Identifier, Identifier>, ClientRegistryTag> =
        clientRegistryTags.associateBy { clientRegistryTag ->
            clientRegistryTag.registryId to clientRegistryTag.tagId
        }

    init {
        require(clientRegistryTagsById.size == clientRegistryTags.size) {
            "Client registry view contains duplicate tags"
        }
    }

    fun tag(
        registryId: Identifier,
        tagId: Identifier,
    ): ClientRegistryTag? = clientRegistryTagsById[registryId to tagId]
}

fun DataPackConfigurationSnapshot.resolveClientRegistryView(
    protocolData: ProtocolData,
    staticRegistrySchema: StaticRegistrySchema = protocolData.staticRegistrySchema,
    remoteRegistrySnapshot: RemoteRegistrySnapshot = RemoteRegistrySnapshot.Empty,
): ClientRegistryView {
    val resolvedStaticRegistryContext = staticRegistrySchema.resolve(remoteRegistrySnapshot)
    val synchronizedProtocolRegistries = synchronizedRegistryPackets.map { registryDataPacket ->
        ProtocolRegistry(
            registryDataPacket.registryId,
            registryDataPacket.entries.mapIndexed { rawId, registryEntry ->
                ProtocolRegistryEntry(registryEntry.id, rawId)
            },
        )
    }
    val protocolRegistryContext = protocolData.completeProtocolRegistryContext
        .withStaticRegistryResolution(resolvedStaticRegistryContext)
        .withRegistries(synchronizedProtocolRegistries)
    return resolveClientRegistryView(protocolRegistryContext)
}

/** Resolves received tags against a context already installed by a vanilla or loader negotiation profile. */
fun DataPackConfigurationSnapshot.resolveClientRegistryView(
    protocolRegistryContext: ProtocolRegistryContext,
): ClientRegistryView {
    val clientRegistryTags = registryTags.flatMap { registryTags ->
        val protocolRegistry = protocolRegistryContext.requireRegistry(registryTags.registry)
        registryTags.tags.map { tagDefinition ->
            ClientRegistryTag(
                registryId = registryTags.registry,
                tagId = tagDefinition.name,
                protocolRegistryEntries = tagDefinition.entries.map { rawId ->
                    protocolRegistry[rawId] ?: throw IllegalArgumentException(
                        "Tag ${tagDefinition.name} in ${registryTags.registry} contains unknown raw ID $rawId",
                    )
                },
            )
        }
    }
    return ClientRegistryView(this, protocolRegistryContext, clientRegistryTags)
}
