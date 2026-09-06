package com.hiczp.minecraft.protocol.configuration

import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * Protocol data needed to configure a client for the repository-selected release.
 *
 * Static client-known registries are exposed separately through typed
 * catalogues. This interface owns the registries and tags synchronized during
 * the Configuration state.
 */
interface ConfigurationData {
    val offeredKnownPacks: List<KnownPack>
    val enabledFeatureFlags: Set<Identifier>
    val registryTags: List<RegistryTags>
    val staticRegistrySchema: StaticRegistrySchema

    /** Complete default ID context, including Configuration-synchronized registries. */
    val completePacketCodecContext: PacketCodecContext

    /**
     * Returns the synchronized registry packets for a client's Known Packs
     * response.
     *
     * An implementation may omit entry data for a Known Packs selection it recognizes. Callers that do not recognize
     * the selection return complete registry NBT.
     */
    fun synchronizedRegistryPackets(acceptedKnownPacks: List<KnownPack>): List<ClientboundRegistryDataPacket>
}
