package com.hiczp.minecraft.protocol.configuration.vanilla

import com.hiczp.minecraft.protocol.model.packet.ClientboundRegistryDataPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundSelectKnownPacks
import com.hiczp.minecraft.protocol.model.packet.ClientboundUpdateEnabledFeaturesPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.protocol.model.type.RegistryTags
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormat
import kotlinx.serialization.DeserializationStrategy
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
        packetPayloadChunks = VanillaConfigurationPacketPayloads.offeredKnownPacksPayloadChunks,
        deserializationStrategy = ClientboundSelectKnownPacks.serializer(),
    )
    val clientboundUpdateEnabledFeaturesPacket = decodeConfigurationPacket(
        packetPayloadChunks = VanillaConfigurationPacketPayloads.enabledFeatureFlagsPayloadChunks,
        deserializationStrategy = ClientboundUpdateEnabledFeaturesPacket.serializer(),
    )
    val completeSynchronizedRegistryPackets =
        VanillaConfigurationPacketPayloads.completeSynchronizedRegistryPacketPayloadChunks.map { packetPayloadChunks ->
            decodeConfigurationPacket(packetPayloadChunks, ClientboundRegistryDataPacket.serializer())
        }
    val knownPackSynchronizedRegistryPackets =
        VanillaConfigurationPacketPayloads.knownPackSynchronizedRegistryPacketPayloadChunks.map { packetPayloadChunks ->
            decodeConfigurationPacket(packetPayloadChunks, ClientboundRegistryDataPacket.serializer())
        }
    val clientboundUpdateTagsPacket = decodeConfigurationPacket(
        packetPayloadChunks = VanillaConfigurationPacketPayloads.registryTagsPayloadChunks,
        deserializationStrategy = ClientboundUpdateTagsPacket.serializer(),
    )

    return VanillaConfigurationSnapshot(
        offeredKnownPacks = clientboundSelectKnownPacks.knownPacks,
        enabledFeatureFlags = clientboundUpdateEnabledFeaturesPacket.features,
        completeSynchronizedRegistryPackets = completeSynchronizedRegistryPackets,
        knownPackSynchronizedRegistryPackets = knownPackSynchronizedRegistryPackets,
        registryTags = clientboundUpdateTagsPacket.tags,
    )
}

private fun <T> decodeConfigurationPacket(
    packetPayloadChunks: List<String>,
    deserializationStrategy: DeserializationStrategy<T>,
): T = MinecraftPacketPayloadFormat.decodeFromByteArray(
    deserializationStrategy,
    Base64.decode(packetPayloadChunks.joinToString(separator = "")),
)
