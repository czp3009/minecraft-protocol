package com.hiczp.minecraft.protocol.configuration.vanilla

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.RegistryTags
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import kotlin.io.encoding.Base64

internal data class VanillaConfigurationSnapshot(
    val offeredKnownPacks: List<KnownPack>,
    val enabledFeatureFlags: Set<Identifier>,
    val completeSynchronizedRegistryPackets: List<ClientboundRegistryDataPacket>,
    val knownPackSynchronizedRegistryPackets: List<ClientboundRegistryDataPacket>,
    val registryTags: List<RegistryTags>,
)

internal fun decodeVanillaConfigurationSnapshot(): VanillaConfigurationSnapshot {
    val clientboundSelectKnownPacks = decodeConfigurationPacket(
        packetId = 0x0E,
        packetPayloadChunks = VanillaConfigurationPacketPayloads.offeredKnownPacksPayloadChunks,
    ) as ClientboundSelectKnownPacks
    val clientboundUpdateEnabledFeaturesPacket = decodeConfigurationPacket(
        packetId = 0x0C,
        packetPayloadChunks = VanillaConfigurationPacketPayloads.enabledFeatureFlagsPayloadChunks,
    ) as ClientboundUpdateEnabledFeaturesPacket
    val completeSynchronizedRegistryPackets =
        VanillaConfigurationPacketPayloads.completeSynchronizedRegistryPacketPayloadChunks.map { packetPayloadChunks ->
            decodeConfigurationPacket(0x07, packetPayloadChunks) as ClientboundRegistryDataPacket
        }
    val knownPackSynchronizedRegistryPackets =
        VanillaConfigurationPacketPayloads.knownPackSynchronizedRegistryPacketPayloadChunks.map { packetPayloadChunks ->
            decodeConfigurationPacket(0x07, packetPayloadChunks) as ClientboundRegistryDataPacket
        }
    val clientboundUpdateTagsPacket = decodeConfigurationPacket(
        packetId = 0x0D,
        packetPayloadChunks = VanillaConfigurationPacketPayloads.registryTagsPayloadChunks,
    ) as ClientboundUpdateTagsPacket

    return VanillaConfigurationSnapshot(
        offeredKnownPacks = clientboundSelectKnownPacks.knownPacks,
        enabledFeatureFlags = clientboundUpdateEnabledFeaturesPacket.features,
        completeSynchronizedRegistryPackets = completeSynchronizedRegistryPackets,
        knownPackSynchronizedRegistryPackets = knownPackSynchronizedRegistryPackets,
        registryTags = clientboundUpdateTagsPacket.tags,
    )
}

private fun decodeConfigurationPacket(
    packetId: Int,
    packetPayloadChunks: List<String>,
): Packet = MinecraftPacketRegistry.decodePayload(
    connectionState = ConnectionState.CONFIGURATION,
    packetDirection = PacketDirection.CLIENTBOUND,
    id = packetId,
    payload = Base64.decode(packetPayloadChunks.joinToString(separator = "")),
)
