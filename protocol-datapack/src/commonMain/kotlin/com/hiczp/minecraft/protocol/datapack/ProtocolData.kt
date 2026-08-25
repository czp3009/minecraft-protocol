package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * Version-bound protocol data needed to configure a client.
 *
 * Static client-known registries are exposed separately through typed
 * catalogues. This interface owns the registries and tags synchronized during
 * the Configuration state.
 */
interface ProtocolData {
    val minecraftVersion: String
    val protocolVersion: Int
    val offeredKnownPacks: List<KnownPack>
    val enabledFeatureFlags: Set<Identifier>
    val registryTags: List<RegistryTags>
    val staticRegistrySchema: StaticRegistrySchema

    /** Complete default ID context, including Configuration-synchronized registries. */
    val completeProtocolRegistryContext: ProtocolRegistryContext

    /**
     * Returns the synchronized registry packets for a client's Known Packs
     * response.
     *
     * An implementation may omit entry data for a Known Packs selection it recognizes. Callers that do not recognize
     * the selection return complete registry NBT.
     */
    fun synchronizedRegistryPackets(acceptedKnownPacks: List<KnownPack>): List<RegistryDataPacket>
}
