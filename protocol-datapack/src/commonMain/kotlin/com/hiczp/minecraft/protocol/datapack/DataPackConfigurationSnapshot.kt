package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.RegistryTags
import com.hiczp.minecraft.protocol.model.type.TagDefinition

/** Manually constructible capture of the data-pack-related packets visible to a client. */
class DataPackConfigurationSnapshot(
    offeredKnownPacks: List<KnownPack>,
    enabledFeatureFlags: Set<Identifier>,
    synchronizedRegistryPackets: List<RegistryDataPacket>,
    registryTags: List<RegistryTags>,
) {
    val offeredKnownPacks: List<KnownPack> = offeredKnownPacks.toList()
    val enabledFeatureFlags: Set<Identifier> = enabledFeatureFlags.toSet()
    val synchronizedRegistryPackets: List<RegistryDataPacket> =
        synchronizedRegistryPackets.map { registryDataPacket ->
            RegistryDataPacket(registryDataPacket.registryId, registryDataPacket.entries.toList())
        }
    val registryTags: List<RegistryTags> = registryTags.map { registryTags ->
        RegistryTags(
            registryTags.registry,
            registryTags.tags.map { tagDefinition ->
                TagDefinition(tagDefinition.name, tagDefinition.entries.toList())
            },
        )
    }

    override fun equals(other: Any?): Boolean =
        other is DataPackConfigurationSnapshot &&
                offeredKnownPacks == other.offeredKnownPacks &&
                enabledFeatureFlags == other.enabledFeatureFlags &&
                synchronizedRegistryPackets == other.synchronizedRegistryPackets &&
                registryTags == other.registryTags

    override fun hashCode(): Int {
        var result = offeredKnownPacks.hashCode()
        result = 31 * result + enabledFeatureFlags.hashCode()
        result = 31 * result + synchronizedRegistryPackets.hashCode()
        return 31 * result + registryTags.hashCode()
    }
}
