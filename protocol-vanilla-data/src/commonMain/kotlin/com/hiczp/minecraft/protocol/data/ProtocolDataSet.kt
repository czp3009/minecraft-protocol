package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.packet.FeatureFlagsPacket
import com.hiczp.minecraft.protocol.model.packet.RegistryDataPacket
import com.hiczp.minecraft.protocol.model.type.KnownPack

/**
 * Version-bound protocol data needed to configure a vanilla client.
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

    /**
     * Returns the synchronized registry packets for a client's Known Packs
     * response.
     *
     * Vanilla only omits entry data when the response exactly matches the
     * offered list. Any other response receives complete registry NBT.
     */
    fun registryPackets(clientKnownPacks: List<KnownPack>): List<RegistryDataPacket>
}
