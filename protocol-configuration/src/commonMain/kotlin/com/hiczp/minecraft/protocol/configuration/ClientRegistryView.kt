package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.protocol.model.type.*

data class ClientRegistryTag(
    val registryId: Identifier,
    val tagId: Identifier,
    val registryIdMapEntries: List<RegistryIdMapping>,
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
    val packetCodecContext: PacketCodecContext,
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
    configurationData: ConfigurationData,
    staticRegistrySchema: StaticRegistrySchema = configurationData.staticRegistrySchema,
    remoteRegistrySnapshot: RemoteRegistrySnapshot = RemoteRegistrySnapshot.Empty,
): ClientRegistryView {
    val resolvedStaticRegistryContext = staticRegistrySchema.resolve(remoteRegistrySnapshot)
    val packetCodecContext = configurationData.completePacketCodecContext
        .withStaticRegistryResolution(resolvedStaticRegistryContext)
        .withSynchronizedRegistries(synchronizedRegistryPackets)
    return resolveClientRegistryView(packetCodecContext)
}

/** Resolves received tags against a context already installed by a vanilla or loader negotiation profile. */
fun DataPackConfigurationSnapshot.resolveClientRegistryView(
    packetCodecContext: PacketCodecContext,
): ClientRegistryView {
    val clientRegistryTags = registryTags.flatMap { registryTags ->
        val registryIdMap = packetCodecContext.requireRegistry(registryTags.registry)
        registryTags.tags.map { tagDefinition ->
            ClientRegistryTag(
                registryId = registryTags.registry,
                tagId = tagDefinition.name,
                registryIdMapEntries = tagDefinition.entries.map { rawId ->
                    registryIdMap[rawId] ?: throw IllegalArgumentException(
                        "Tag ${tagDefinition.name} in ${registryTags.registry} contains unknown raw ID $rawId",
                    )
                },
            )
        }
    }
    return ClientRegistryView(this, packetCodecContext, clientRegistryTags)
}
