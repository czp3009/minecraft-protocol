package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.packet.FeatureFlagsPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.StaticRegistrySchema

/**
 * Version-bound protocol data needed to configure a client.
 *
 * Static client-known registries are exposed separately through typed
 * catalogues. This interface owns the registries and tags synchronized during
 * the Configuration state.
 */
interface ProtocolDataSet {
    val minecraftVersion: String
    val protocolVersion: Int
    val knownPacks: List<KnownPack>
    val featureFlags: FeatureFlagsPacket
    val tags: ConfigurationUpdateTagsPacket
    val staticRegistries: StaticRegistrySchema

    /** Complete default ID context, including Configuration-synchronized registries. */
    val registryContext: ProtocolRegistryContext

    /**
     * Returns the synchronized registry packets for a client's Known Packs
     * response.
     *
     * An implementation may omit entry data for a Known Packs selection it recognizes. Callers that do not recognize
     * the selection return complete registry NBT.
     */
    fun registryPackets(clientKnownPacks: List<KnownPack>): List<RegistryDataPacket>
}
